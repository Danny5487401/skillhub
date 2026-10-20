package worker

import (
	"context"
	"errors"
	"math"
	"slices"
	"strings"
	"time"

	"github.com/iflytek/skillhub/github-sync-go/internal/controlplane"
	"github.com/iflytek/skillhub/github-sync-go/internal/github"
)

type officialOwnerGitHub interface {
	GetOwnerByID(context.Context, int64) (github.OwnerIdentity, github.Quota, error)
	ListPublicOwnerRepositories(context.Context, string, github.OwnerType, int) (github.OwnerPage, github.Quota, error)
}

// Owner rechecks enumerate a changing public collection, not a pushed-time window.
// Stable IDs fence account renames and login reuse between API requests.
func processOfficialOwner(ctx context.Context, control *controlplane.Client, task *controlplane.ClaimedTask,
	limits DiscoveryLimits, upstream officialOwnerGitHub) error {
	snapshot := task.OfficialDiscovery
	if !slices.Contains(task.RequiredCapabilities, controlplane.OfficialOwnerCapability) ||
		snapshot.FormatVersion != 1 || snapshot.QueryID == "" || snapshot.QueryVersion < 1 || snapshot.ShardID == "" || snapshot.StrategyVersion == "" ||
		snapshot.OwnerID < 1 || snapshot.SeedRepositoryID < 1 || snapshot.ExpansionDepth != 1 ||
		snapshot.OwnerLogin == "" || snapshot.Query.Value != snapshot.OwnerLogin || snapshot.Range.Partition != "" ||
		snapshot.Mode != "OWNER_RECHECK" || snapshot.Query.Kind != "OWNER" || snapshot.Query.Scope != "OWNER_REPOSITORIES" ||
		snapshot.Budget.MaxRequests < 3 || snapshot.Budget.MaxPages < 1 || snapshot.Budget.CandidateBudget < 1 ||
		snapshot.Budget.MaxSplitDepth != 0 || snapshot.Budget.MaxChildShards != 0 {
		return errors.New("invalid official owner expansion snapshot")
	}
	checkpoint := snapshot.Checkpoint
	paused, err := pauseOfficialDiscoveryIfNeeded(ctx, control, task, checkpoint)
	if paused || err != nil {
		return err
	}
	progress, cursor, recoveryReason := decodeOfficialProgress(snapshot)
	if recoveryReason != "" {
		_, err = control.RecoverOfficialCheckpoint(ctx, task.TaskID, controlplane.OfficialCheckpointRecovery{
			Lease: task.Lease, Reason: recoveryReason, RejectedCheckpoint: checkpoint,
		})
		return err
	}
	if progress.Coverage == "COMPLETE" {
		_, err = control.CompleteOfficialDiscovery(ctx, task.TaskID, task.Lease, checkpoint, time.Time{}, "", snapshot.Channel)
		return err
	}
	if progress.Coverage == "GAP" {
		cursor.Revisit++
		progress.Coverage, progress.NextPage, cursor.Offset, cursor.GapReason = "IN_PROGRESS", 1, 0, ""
	}
	owner, _, lookupErr := upstream.GetOwnerByID(ctx, snapshot.OwnerID)
	canonicalLogin := ""
	if lookupErr == nil {
		if owner.ID != snapshot.OwnerID || owner.Login == "" {
			lookupErr = errors.New("owner identity changed")
		} else {
			canonicalLogin = owner.Login
		}
	}
	cursor.Revisit++
	checkpoint, err = reportOfficialProgress(ctx, control, task, checkpoint, progress, cursor, nil, 1, nil, "", canonicalLogin)
	if err != nil {
		return handleOfficialBatchFailure(ctx, control, task, checkpoint, err)
	}
	if lookupErr != nil {
		return deferOfficialOwnerError(ctx, control, task, checkpoint, lookupErr)
	}
	requests, pages, spent := int64(1), int64(0), int64(0)
	seen := make(map[int64]struct{})
	for {
		paused, err = pauseOfficialDiscoveryIfNeeded(ctx, control, task, checkpoint)
		if paused || err != nil {
			return err
		}
		// Reserve a second request to verify even an empty last page. With a minimum
		// budget of three, a fresh lease can always resolve identity and finish a page.
		if requests+2 > snapshot.Budget.MaxRequests || pages >= min(snapshot.Budget.MaxPages, int64(limits.MaxPagesPerLease)) || spent >= snapshot.Budget.CandidateBudget {
			return completeOfficialDeferred(ctx, control, task, checkpoint, "BUDGET", time.Now().Add(officialBudgetRetryDelay))
		}
		page, _, pageErr := upstream.ListPublicOwnerRepositories(ctx, owner.Login, owner.Type, int(progress.NextPage))
		requests++
		pages++
		used := int64(1)
		if pageErr == nil {
			for _, repository := range page.Repositories {
				if repository.Owner.ID != snapshot.OwnerID {
					pageErr = errors.New("repository owner identity changed during enumeration")
					break
				}
			}
		}
		if pageErr != nil {
			cursor.Revisit++
			checkpoint, err = reportOfficialProgress(ctx, control, task, checkpoint, progress, cursor, nil, used, nil, "", canonicalLogin)
			if err != nil {
				return handleOfficialBatchFailure(ctx, control, task, checkpoint, err)
			}
			return deferOfficialOwnerError(ctx, control, task, checkpoint, pageErr)
		}
		candidates := toCandidates(page.Repositories)
		if cursor.Offset > len(candidates) {
			cursor.Offset = 0
			cursor.Revisit++
		}
		batch, end := selectDiscoveryBatch(candidates, cursor.Offset, int(min(snapshot.Budget.CandidateBudget-spent, 100)), seen)
		next, nextCursor := progress, cursor
		nextCursor.Offset = end
		gapReason := ""
		if end == len(candidates) {
			if page.HasMore {
				if progress.NextPage == math.MaxInt32 {
					next.Coverage, gapReason = "GAP", "RESULT_LIMIT"
				} else {
					next.NextPage++
					nextCursor.Offset = 0
				}
			} else {
				paused, err = pauseOfficialDiscoveryIfNeeded(ctx, control, task, checkpoint)
				if paused || err != nil {
					return err
				}
				verified, _, verifyErr := upstream.GetOwnerByID(ctx, snapshot.OwnerID)
				requests++
				used++
				if verifyErr == nil && (verified.ID != owner.ID || verified.Type != owner.Type || !strings.EqualFold(verified.Login, owner.Login)) {
					verifyErr = errors.New("owner identity changed before final page confirmation")
				}
				if verifyErr != nil {
					// Retain the page; no candidate or completion from an unconfirmed tail is
					// committed. Retry resolves the canonical login again using the stable ID.
					cursor.Revisit++
					checkpoint, err = reportOfficialProgress(ctx, control, task, checkpoint, progress, cursor, nil, used, nil, "", canonicalLogin)
					if err != nil {
						return handleOfficialBatchFailure(ctx, control, task, checkpoint, err)
					}
					return deferOfficialOwnerError(ctx, control, task, checkpoint, verifyErr)
				}
				next.Coverage = "COMPLETE"
			}
		}
		provenance := controlplane.OfficialProvenance{Channel: snapshot.Channel, QueryID: snapshot.QueryID,
			QueryVersion: snapshot.QueryVersion, StrategyVersion: snapshot.StrategyVersion, ShardID: snapshot.ShardID,
			SeedRepositoryID: snapshot.SeedRepositoryID}
		for i := range batch {
			batch[i].OfficialProvenance = []controlplane.OfficialProvenance{provenance}
		}
		accepted, reportErr := reportOfficialProgress(ctx, control, task, checkpoint, next, nextCursor, batch, used, nil, gapReason, canonicalLogin)
		if reportErr != nil {
			return handleOfficialBatchFailure(ctx, control, task, checkpoint, reportErr)
		}
		checkpoint, progress, cursor = accepted, next, nextCursor
		for _, candidate := range batch {
			seen[candidate.RepositoryID] = struct{}{}
		}
		spent += int64(len(batch))
		if progress.Coverage == "COMPLETE" {
			_, err = control.CompleteOfficialDiscovery(ctx, task.TaskID, task.Lease, checkpoint, time.Time{}, "", snapshot.Channel)
			return err
		}
		if progress.Coverage == "GAP" {
			return completeOfficialDeferred(ctx, control, task, checkpoint, "BUDGET", time.Now().Add(officialGapBackoff(cursor.Revisit)))
		}
	}
}

func deferOfficialOwnerError(ctx context.Context, control *controlplane.Client, task *controlplane.ClaimedTask,
	checkpoint string, acquisitionErr error) error {
	reason, retryAt := "BUDGET", time.Now().Add(time.Minute)
	var apiError *github.APIError
	if errors.As(acquisitionErr, &apiError) && apiError.Code == "GITHUB_RATE_LIMITED" {
		reason = "RATE_LIMIT"
		if apiError.RetryAt.After(retryAt) {
			retryAt = apiError.RetryAt
		}
	}
	return completeOfficialDeferred(ctx, control, task, checkpoint, reason, retryAt)
}
