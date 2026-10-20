package worker

import (
	"context"
	"encoding/json"
	"errors"
	"math"
	"slices"
	"time"

	"github.com/iflytek/skillhub/github-sync-go/internal/controlplane"
	"github.com/iflytek/skillhub/github-sync-go/internal/github"
)

// These are continuation delays, not calibrated budget profile allocations.
const officialBudgetRetryDelay = time.Second

type officialDiscoveryGitHub interface {
	SearchOfficialRepositories(context.Context, github.OfficialRepositoryQuery, github.OfficialRepositoryRange,
		time.Time, int) (github.SearchPage, github.Quota, error)
}

// The outer checkpoint identifies immutable Java-owned scope; cursor holds only
// bounded page-local progress. Revisit makes replay of a changed page unambiguous.
type officialPageCursor struct {
	Offset    int    `json:"offset"`
	Revisit   int64  `json:"revisit"`
	GapReason string `json:"gapReason,omitempty"`
}

func processOfficialDiscovery(ctx context.Context, control *controlplane.Client, task *controlplane.ClaimedTask,
	limits DiscoveryLimits, upstream officialDiscoveryGitHub) error {
	if task == nil || task.Kind != "DISCOVERY" || task.OfficialDiscovery == nil || task.Discovery != nil ||
		control == nil || upstream == nil || limits.MaxPagesPerLease < 1 {
		return errors.New("invalid official discovery task")
	}
	if _, err := installOfficialQuotaGuard(control, task, upstream); err != nil {
		return err
	}
	snapshot := task.OfficialDiscovery
	for _, capability := range []string{controlplane.OfficialDiscoveryCapability, controlplane.OfficialCheckpointCapability, controlplane.OfficialPauseCapability} {
		if !slices.Contains(task.RequiredCapabilities, capability) {
			return errors.New("official discovery task omits required lifecycle capabilities")
		}
	}
	if snapshot.Channel == "OWNER_EXPANSION" {
		ownerAPI, ok := upstream.(officialOwnerGitHub)
		if !ok {
			return errors.New("GitHub client lacks owner enumeration")
		}
		return processOfficialOwner(ctx, control, task, limits, ownerAPI)
	}
	if snapshot.FormatVersion != 1 || snapshot.QueryID == "" || snapshot.QueryVersion < 1 || snapshot.ShardID == "" ||
		snapshot.StrategyVersion == "" || snapshot.Budget.CandidateBudget < 1 || snapshot.Budget.MaxPages < 1 || snapshot.Budget.MaxRequests < 1 ||
		(snapshot.Channel != "REPOSITORY_METADATA" && snapshot.Channel != "README") ||
		(snapshot.Channel == "README" && snapshot.Query.Scope != "README") ||
		(snapshot.Channel == "REPOSITORY_METADATA" && snapshot.Query.Scope != "NAME_DESCRIPTION_TOPICS") {
		return errors.New("unsupported official discovery snapshot or channel")
	}
	query := github.OfficialRepositoryQuery{Kind: github.OfficialQueryKind(snapshot.Query.Kind), Value: snapshot.Query.Value,
		Scope: github.OfficialQueryScope(snapshot.Query.Scope)}
	window := github.OfficialRepositoryRange{Since: snapshot.Range.Since, Until: snapshot.Range.Until, Partition: snapshot.Range.Partition}
	if _, err := github.CompileOfficialRepositoryQuery(query, window, snapshot.FixedCutoffAt); err != nil {
		return err
	}
	checkpoint := snapshot.Checkpoint
	paused, err := pauseOfficialDiscoveryIfNeeded(ctx, control, task, checkpoint)
	if err != nil || paused {
		return err
	}
	progress, cursor, recoveryReason := decodeOfficialProgress(snapshot)
	if recoveryReason != "" {
		_, err := control.RecoverOfficialCheckpoint(ctx, task.TaskID, controlplane.OfficialCheckpointRecovery{
			Lease: task.Lease, Reason: recoveryReason, RejectedCheckpoint: checkpoint,
		})
		return err
	}
	if progress.Coverage == "COMPLETE" || progress.Coverage == "SPLIT" {
		_, err := control.CompleteOfficialDiscovery(ctx, task.TaskID, task.Lease, checkpoint, time.Time{}, "", task.OfficialDiscovery.Channel)
		return err
	}
	if checkpoint == "" || progress.Coverage == "GAP" {
		if progress.Coverage == "GAP" {
			cursor.Revisit++
		}
		progress.Coverage, progress.NextPage, cursor.Offset = "IN_PROGRESS", 1, 0
		cursor.GapReason = ""
		checkpoint, err = reportOfficialProgress(ctx, control, task, checkpoint, progress, cursor, nil, 0, nil, "", "")
		if err != nil {
			return handleOfficialBatchFailure(ctx, control, task, checkpoint, err)
		}
	}
	var requests, spent int64
	seen := make(map[int64]struct{})
	for {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		paused, err = pauseOfficialDiscoveryIfNeeded(ctx, control, task, checkpoint)
		if err != nil || paused {
			return err
		}
		if requests >= min(snapshot.Budget.MaxPages, snapshot.Budget.MaxRequests, int64(limits.MaxPagesPerLease)) || spent >= snapshot.Budget.CandidateBudget {
			return completeOfficialDeferred(ctx, control, task, checkpoint, "BUDGET", time.Now().Add(officialBudgetRetryDelay))
		}
		page, _, searchErr := upstream.SearchOfficialRepositories(ctx, query, window, snapshot.FixedCutoffAt, int(progress.NextPage))
		requests++
		if searchErr != nil {
			// Account for the attempted request without advancing the page/offset.
			cursor.Revisit++
			checkpoint, err = reportOfficialProgress(ctx, control, task, checkpoint, progress, cursor, nil, 1, nil, "", "")
			if err != nil {
				return handleOfficialBatchFailure(ctx, control, task, checkpoint, err)
			}
			var upstreamError *github.APIError
			if errors.As(searchErr, &upstreamError) && upstreamError.Code == "GITHUB_RATE_LIMITED" {
				retryAt := upstreamError.RetryAt
				if !retryAt.After(time.Now()) {
					retryAt = time.Now().Add(time.Minute)
				}
				return completeOfficialDeferred(ctx, control, task, checkpoint, "RATE_LIMIT", retryAt)
			}
			return searchErr
		}
		if page.LimitReached || (page.HasMore && progress.NextPage >= 10) {
			if cursor.GapReason == "" {
				cursor.GapReason = "RESULT_LIMIT"
			}
		}
		if page.IncompleteResults {
			cursor.GapReason = "INCOMPLETE_RESULTS"
		}
		candidates := toCandidates(page.Repositories)
		if cursor.Offset > len(candidates) {
			cursor.Offset = 0
			cursor.Revisit++
		}
		batch, end := selectDiscoveryBatch(candidates, cursor.Offset, int(min(snapshot.Budget.CandidateBudget-spent, 100)), seen)
		provenance := controlplane.OfficialProvenance{Channel: snapshot.Channel, QueryID: snapshot.QueryID,
			QueryVersion: snapshot.QueryVersion, StrategyVersion: snapshot.StrategyVersion, ShardID: snapshot.ShardID}
		for index := range batch {
			batch[index].OfficialProvenance = []controlplane.OfficialProvenance{provenance}
		}
		next, nextCursor := progress, cursor
		var children []controlplane.OfficialQueryRange
		gapReason := ""
		if end < len(candidates) {
			// Even an incomplete page may contain useful public repositories.
			// Drain it across leases before handing off or recording a gap.
			nextCursor.Offset = end
		} else {
			nextCursor.Offset = end
			if cursor.GapReason != "" {
				children = officialSplitRanges(snapshot)
			}
			if len(children) > 0 {
				next.Coverage = "SPLIT"
			} else if page.HasMore && progress.NextPage < 10 && cursor.GapReason != "INCOMPLETE_RESULTS" {
				// A result cap still allows the accessible pages to be harvested.
				// Retain the gap signal so page ten can never claim full coverage.
				next.NextPage++
				nextCursor.Offset = 0
			} else if cursor.GapReason != "" {
				next.Coverage, gapReason = "GAP", cursor.GapReason
				if snapshot.Range.Until.Unix()-snapshot.Range.Since.Unix() < 2 {
					gapReason = "UNSPLITTABLE"
				}
			} else {
				next.Coverage = "COMPLETE"
			}
		}
		acceptedCheckpoint, reportErr := reportOfficialProgress(ctx, control, task, checkpoint, next, nextCursor, batch, 1, children, gapReason, "")
		if reportErr != nil {
			return handleOfficialBatchFailure(ctx, control, task, checkpoint, reportErr)
		}
		checkpoint, progress, cursor = acceptedCheckpoint, next, nextCursor
		for _, candidate := range batch {
			seen[candidate.RepositoryID] = struct{}{}
		}
		spent += int64(len(batch))
		if progress.Coverage == "GAP" {
			return completeOfficialDeferred(ctx, control, task, checkpoint, "BUDGET", time.Now().Add(officialGapBackoff(cursor.Revisit)))
		}
		if progress.Coverage == "COMPLETE" || progress.Coverage == "SPLIT" {
			_, err = control.CompleteOfficialDiscovery(ctx, task.TaskID, task.Lease, checkpoint, time.Time{}, "", task.OfficialDiscovery.Channel)
			return err
		}
	}
}

func decodeOfficialProgress(snapshot *controlplane.OfficialDiscoverySnapshot) (controlplane.OfficialCheckpoint, officialPageCursor, string) {
	progress := controlplane.OfficialCheckpoint{FormatVersion: 1, StrategyVersion: snapshot.StrategyVersion,
		QueryID: snapshot.QueryID, QueryVersion: snapshot.QueryVersion, ShardID: snapshot.ShardID, NextPage: 1, Coverage: "IN_PROGRESS"}
	cursor := officialPageCursor{}
	if snapshot.Checkpoint == "" {
		return progress, cursor, ""
	}
	progress = controlplane.OfficialCheckpoint{}
	if len(snapshot.Checkpoint) > 4096 || json.Unmarshal([]byte(snapshot.Checkpoint), &progress) != nil {
		return progress, cursor, "MALFORMED"
	}
	if progress.FormatVersion != 1 {
		return progress, cursor, "FORMAT_UNSUPPORTED"
	}
	if progress.StrategyVersion != snapshot.StrategyVersion || progress.QueryID != snapshot.QueryID ||
		progress.QueryVersion != snapshot.QueryVersion || progress.ShardID != snapshot.ShardID {
		return progress, cursor, "IDENTITY_MISMATCH"
	}
	ownerChannel := snapshot.Channel == "OWNER_EXPANSION"
	if progress.NextPage < 1 || progress.NextPage > math.MaxInt32 ||
		(!ownerChannel && (progress.NextPage > 11 || (progress.NextPage == 11 && progress.Coverage != "COMPLETE" && progress.Coverage != "SPLIT"))) ||
		(ownerChannel && progress.Coverage == "SPLIT") ||
		!slices.Contains([]string{"IN_PROGRESS", "COMPLETE", "SPLIT", "GAP"}, progress.Coverage) ||
		len(progress.Cursor) > 1024 || (progress.Cursor != "" && json.Unmarshal([]byte(progress.Cursor), &cursor) != nil) ||
		cursor.Offset < 0 || cursor.Offset > 100 || cursor.Revisit < 0 || cursor.Revisit >= math.MaxInt64-16 {
		return progress, cursor, "MALFORMED"
	}
	if progress.Cursor != "" {
		var fields map[string]json.RawMessage
		if json.Unmarshal([]byte(progress.Cursor), &fields) != nil || (len(fields) != 2 && len(fields) != 3) ||
			len(fields["offset"]) == 0 || len(fields["revisit"]) == 0 ||
			string(fields["offset"]) == "null" || string(fields["revisit"]) == "null" ||
			(len(fields) == 3 && len(fields["gapReason"]) == 0) ||
			(cursor.GapReason != "" && !slices.Contains([]string{"RESULT_LIMIT", "INCOMPLETE_RESULTS"}, cursor.GapReason)) {
			return progress, cursor, "MALFORMED"
		}
	}
	return progress, cursor, ""
}

func reportOfficialProgress(ctx context.Context, control *controlplane.Client, task *controlplane.ClaimedTask,
	previous string, progress controlplane.OfficialCheckpoint, cursor officialPageCursor,
	repositories []controlplane.RepositoryCandidate, requests int64, children []controlplane.OfficialQueryRange, gapReason, ownerLogin string) (string, error) {
	if ownerLogin != "" && task.OfficialDiscovery.Channel != "OWNER_EXPANSION" {
		return previous, errors.New("only owner expansion can report canonical owner login")
	}
	encodedCursor, err := json.Marshal(cursor)
	if err != nil {
		return previous, err
	}
	progress.Cursor = string(encodedCursor)
	encoded, err := json.Marshal(progress)
	if err != nil || len(encoded) > 4096 || string(encoded) == previous {
		return previous, errors.New("official discovery checkpoint did not advance or exceeds limit")
	}
	var previousCheckpoint *string
	if previous != "" {
		previousCheckpoint = &previous
	}
	batch := controlplane.DiscoveryBatch{
		PreviousCheckpoint: previousCheckpoint, Checkpoint: string(encoded), Repositories: repositories,
		OfficialBatch: &controlplane.OfficialDiscoveryBatch{ShardID: progress.ShardID, Coverage: progress.Coverage,
			RequestsUsed: requests, ChildRanges: children, GapReason: gapReason, OwnerLogin: ownerLogin},
	}
	err = control.ReportDiscoveryBatch(ctx, task.TaskID, task.Lease, batch)
	if err != nil && ctx.Err() == nil {
		var apiError *controlplane.APIError
		if !errors.As(err, &apiError) || apiError.Status >= 500 {
			// The first response may have been lost after commit. Replay the
			// identical payload; Java's batch digest prevents double accounting.
			err = control.ReportDiscoveryBatch(ctx, task.TaskID, task.Lease, batch)
		}
	}
	if err != nil {
		return previous, err
	}
	return string(encoded), nil
}

// Child tasks are registered atomically by Java. The snapshot carries remaining
// depth and root-wide child capacity; zero/one remaining child cannot bisect.
// Go never scans unacknowledged children; Java rechecks the root budget on write.
func officialSplitRanges(snapshot *controlplane.OfficialDiscoverySnapshot) []controlplane.OfficialQueryRange {
	start, end := snapshot.Range.Since.Unix(), snapshot.Range.Until.Unix()
	if snapshot.Budget.MaxSplitDepth < 1 || snapshot.Budget.MaxChildShards < 2 || end-start < 2 {
		return nil
	}
	middle := time.Unix(start+(end-start)/2, 0).UTC()
	return []controlplane.OfficialQueryRange{
		{Since: snapshot.Range.Since, Until: middle},
		{Since: middle, Until: snapshot.Range.Until},
	}
}

func pauseOfficialDiscoveryIfNeeded(ctx context.Context, control *controlplane.Client, task *controlplane.ClaimedTask, checkpoint string) (bool, error) {
	for attempts := 0; attempts < 3; attempts++ {
		state, err := control.GetOfficialTaskControl(ctx, task.TaskID, task.Lease)
		if err != nil || state.Action == "CONTINUE" {
			return false, err
		}
		var acknowledged *string
		if checkpoint != "" {
			acknowledged = &checkpoint
		}
		err = control.PauseOfficialDiscovery(ctx, task.TaskID, task.Lease, acknowledged)
		if err == nil {
			return true, nil
		}
		var apiError *controlplane.APIError
		if !errors.As(err, &apiError) || apiError.Code != "CONTROL_CHANGED" {
			return false, err
		}
	}
	return false, errors.New("official discovery control changed repeatedly")
}

// A refused batch never advances the acknowledged checkpoint. Control is
// re-read before deciding whether to pause or release the lease for capacity.
func handleOfficialBatchFailure(ctx context.Context, control *controlplane.Client, task *controlplane.ClaimedTask,
	checkpoint string, reportErr error) error {
	var apiError *controlplane.APIError
	if !errors.As(reportErr, &apiError) ||
		(apiError.Code != "BACKPRESSURE_ACTIVE" && apiError.Code != "CANDIDATE_BUDGET_EXCEEDED") {
		return reportErr
	}
	paused, err := pauseOfficialDiscoveryIfNeeded(ctx, control, task, checkpoint)
	if err != nil || paused {
		return err
	}
	retryAt := time.Now().Add(time.Minute)
	if checkpoint == "" {
		// Complete requires a persisted checkpoint. If the very first batch
		// was refused, release through fail without inventing any progress.
		_, err := control.Fail(ctx, task.TaskID, task.Lease, "RETRYABLE_ACQUISITION",
			"", retryAt, nil)
		return err
	}
	return completeOfficialDeferred(ctx, control, task, checkpoint, "CAPACITY", retryAt)
}

func completeOfficialDeferred(ctx context.Context, control *controlplane.Client, task *controlplane.ClaimedTask,
	checkpoint, reason string, retryAt time.Time) error {
	_, err := control.CompleteOfficialDiscovery(ctx, task.TaskID, task.Lease, checkpoint, retryAt, reason, task.OfficialDiscovery.Channel)
	return err
}

// A repeatedly unresolved fixed range retains its retry generation across
// leases. Normal work-budget exhaustion does not use this gap backoff.
func officialGapBackoff(revisit int64) time.Duration {
	return min(5*time.Minute*time.Duration(int64(1)<<min(revisit, 7)), 6*time.Hour)
}
