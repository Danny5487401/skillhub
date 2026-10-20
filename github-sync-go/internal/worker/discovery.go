package worker

import (
	"context"
	"encoding/json"
	"errors"
	"sort"
	"strings"
	"time"

	"github.com/iflytek/skillhub/github-sync-go/internal/controlplane"
	"github.com/iflytek/skillhub/github-sync-go/internal/github"
)

type DiscoveryLimits struct {
	MaxPagesPerLease int
}

type discoveryGitHub interface {
	SetQuotaObserver(func(context.Context, github.Quota))
	GetRepository(context.Context, string, string) (github.Repository, github.Quota, error)
	GetOwnerType(context.Context, string) (github.OwnerType, github.Quota, error)
	ListPublicOwnerRepositories(context.Context, string, github.OwnerType, int) (github.OwnerPage, github.Quota, error)
	SearchRepositories(context.Context, string, string, *time.Time, time.Time, int) (github.SearchPage, github.Quota, error)
}

type discoveryCursor struct {
	Version  int    `json:"v"`
	Entry    string `json:"entry"`
	Selector int    `json:"selector,omitempty"`
	Page     int    `json:"page"`
	Offset   int    `json:"offset,omitempty"`
	Path     string `json:"path,omitempty"`
	Revisit  int    `json:"revisit,omitempty"`
}

// ProcessDiscovery persists each cursor only after its candidate batch is
// accepted. The cursor contains no token or process-local business state.
func ProcessDiscovery(ctx context.Context, control *controlplane.Client,
	task *controlplane.ClaimedTask, limits DiscoveryLimits) error {
	if task == nil {
		return errors.New("invalid discovery task or worker limits")
	}
	if task.OfficialDiscovery != nil {
		return processOfficialDiscovery(ctx, control, task, limits, github.NewClient(credentialToken(task)))
	}
	return processDiscoveryWithGitHub(ctx, control, task, limits, github.NewClient(credentialToken(task)))
}

func processDiscoveryWithGitHub(ctx context.Context, control *controlplane.Client,
	task *controlplane.ClaimedTask, limits DiscoveryLimits, upstream discoveryGitHub) error {
	if task == nil || task.Kind != "DISCOVERY" || task.Discovery == nil || task.OfficialDiscovery != nil ||
		limits.MaxPagesPerLease < 1 || task.Discovery.CandidateBudget < 1 || upstream == nil {
		return errors.New("invalid discovery task or worker limits")
	}
	snapshot := task.Discovery
	if snapshot.EntryType == "SEARCH_PREVIEW" {
		return processSearchPreview(ctx, control, task)
	}
	upstream.SetQuotaObserver(observeCredentialQuota(control, task))
	checkpoint := snapshot.Checkpoint
	var cursor discoveryCursor
	if checkpoint == "" {
		cursor = discoveryCursor{Version: 1, Entry: snapshot.EntryType, Page: 1}
		encoded, err := encodeCursor(cursor)
		if err != nil {
			return err
		}
		if err := control.ReportDiscoveryBatch(ctx, task.TaskID, task.Lease,
			controlplane.DiscoveryBatch{Checkpoint: encoded, Repositories: []controlplane.RepositoryCandidate{}}); err != nil {
			return err
		}
		checkpoint = encoded
	} else if err := json.Unmarshal([]byte(checkpoint), &cursor); err != nil {
		return errors.New("invalid persisted discovery cursor")
	}
	if cursor.Version != 1 || cursor.Entry != snapshot.EntryType || cursor.Page < 1 ||
		cursor.Offset < 0 || cursor.Selector < 0 || cursor.Revisit < 0 || len(cursor.Path) > 64 ||
		strings.Trim(cursor.Path, "LR") != "" {
		return errors.New("persisted discovery cursor does not match task")
	}
	if snapshot.EntryType == "CURATED_REPOSITORY" {
		if snapshot.Owner == "" || snapshot.RepositoryName == "" {
			return errors.New("curated repository task has no coordinate")
		}
		if cursor.Page == 1 {
			repository, _, err := upstream.GetRepository(ctx, snapshot.Owner, snapshot.RepositoryName)
			if err != nil {
				return completeDiscoveryError(ctx, control, task, checkpoint, err)
			}
			next := cursor
			next.Page = 2
			checkpoint, err = reportDiscoveryProgress(ctx, control, task, checkpoint, next,
				toCandidates([]github.Repository{repository}), 0)
			if err != nil {
				return err
			}
		} else if cursor.Page != 2 {
			return errors.New("invalid curated repository cursor")
		}
		_, err := control.CompleteDiscovery(ctx, task.TaskID, task.Lease, checkpoint, time.Time{}, "")
		return err
	}
	spent := 0
	pages := 0
	seen := make(map[int64]struct{})
	var ownerType github.OwnerType
	if snapshot.EntryType == "CURATED_OWNER" {
		var err error
		ownerType, _, err = upstream.GetOwnerType(ctx, snapshot.Owner)
		if err != nil {
			return completeDiscoveryError(ctx, control, task, checkpoint, err)
		}
	} else if snapshot.EntryType != "SEARCH_RULE" || snapshot.Criteria == nil ||
		snapshot.SearchWindow == nil || len(snapshot.Criteria.Selectors) == 0 {
		return errors.New("discovery task has no valid entry configuration")
	}
	var selectors []controlplane.SearchSelector
	if snapshot.EntryType == "SEARCH_RULE" {
		selectors = selectorsFor(snapshot)
	}
	for {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if snapshot.EntryType == "SEARCH_RULE" && cursor.Selector >= len(selectors) {
			_, err := control.CompleteDiscovery(ctx, task.TaskID, task.Lease, checkpoint, time.Time{}, "")
			return err
		}
		if spent >= snapshot.CandidateBudget || pages >= limits.MaxPagesPerLease {
			return deferDiscovery(ctx, control, task, checkpoint, "BUDGET", time.Now().Add(30*time.Minute))
		}
		var candidates []controlplane.RepositoryCandidate
		var hasMore bool
		var next discoveryCursor
		if snapshot.EntryType == "CURATED_OWNER" {
			page, _, err := upstream.ListPublicOwnerRepositories(ctx, snapshot.Owner, ownerType, cursor.Page)
			if err != nil {
				return completeDiscoveryError(ctx, control, task, checkpoint, err)
			}
			pages++
			candidates = toCandidates(page.Repositories)
			hasMore = page.HasMore
			next = cursor
		} else {
			from, until, err := searchBounds(snapshot.SearchWindow, cursor.Path)
			if err != nil {
				return err
			}
			selector := selectors[cursor.Selector]
			page, _, err := upstream.SearchRepositories(ctx, selector.Kind, selector.Value,
				&from, until, cursor.Page)
			if err != nil {
				return completeDiscoveryError(ctx, control, task, checkpoint, err)
			}
			pages++
			if page.LimitReached || page.IncompleteResults {
				if !from.Before(until) {
					return deferDiscovery(ctx, control, task, checkpoint, "BUDGET", time.Now().Add(time.Hour))
				}
				next = cursor
				next.Path += "L"
				next.Page, next.Offset = 1, 0
				var err error
				checkpoint, err = reportDiscoveryProgress(ctx, control, task, checkpoint, next, nil, 0)
				if err != nil {
					return err
				}
				cursor = next
				continue
			}
			candidates = toCandidates(page.Repositories)
			hasMore = page.HasMore
			next = cursor
		}
		if cursor.Offset > len(candidates) {
			// GitHub search pages can shrink between leases. Revisit the current
			// page and let Java deduplicate previously reported repository IDs.
			// Bump the cursor generation so replay cannot reuse an older checkpoint.
			if cursor.Revisit == int(^uint(0)>>1) {
				return errors.New("discovery page revisit limit reached")
			}
			cursor.Revisit++
			cursor.Offset = 0
			next = cursor
		}
		remaining := snapshot.CandidateBudget - spent
		batch, end := selectDiscoveryBatch(candidates, cursor.Offset, remaining, seen)
		deferred := len(candidates) - end
		if end < len(candidates) {
			next.Offset = end
		} else if hasMore {
			next.Page++
			next.Offset = 0
		} else if snapshot.EntryType == "CURATED_OWNER" {
			next.Page++
			next.Offset = 0
		} else {
			next = nextSearchCursor(cursor)
		}
		var err error
		checkpoint, err = reportDiscoveryProgress(ctx, control, task, checkpoint, next, batch, deferred)
		if err != nil {
			var apiError *controlplane.APIError
			if errors.As(err, &apiError) && apiError.Code == "CANDIDATE_BUDGET_EXCEEDED" {
				return deferDiscovery(ctx, control, task, checkpoint, "BUDGET", time.Now().Add(30*time.Minute))
			}
			return err
		}
		for _, candidate := range batch {
			seen[candidate.RepositoryID] = struct{}{}
		}
		spent += len(batch)
		cursor = next
		if snapshot.EntryType == "CURATED_OWNER" && !hasMore && deferred == 0 {
			_, err := control.CompleteDiscovery(ctx, task.TaskID, task.Lease, checkpoint, time.Time{}, "")
			return err
		}
	}
}

// The offset tracks raw page positions so a resumed lease does not depend on
// the in-memory seen set. Java also deduplicates repository IDs across leases.
func selectDiscoveryBatch(candidates []controlplane.RepositoryCandidate, offset, budget int,
	seen map[int64]struct{}) ([]controlplane.RepositoryCandidate, int) {
	batch := make([]controlplane.RepositoryCandidate, 0, min(budget, len(candidates)-offset))
	selected := make(map[int64]struct{})
	end := offset
	for end < len(candidates) && len(batch) < budget {
		candidate := candidates[end]
		end++
		if _, exists := seen[candidate.RepositoryID]; exists {
			continue
		}
		if _, exists := selected[candidate.RepositoryID]; exists {
			continue
		}
		selected[candidate.RepositoryID] = struct{}{}
		batch = append(batch, candidate)
	}
	return batch, end
}

// A preview only reads one search page per selector. Counts are estimates from
// repository metadata and can overlap; no discovery candidates are reported.
func processSearchPreview(ctx context.Context, control *controlplane.Client,
	task *controlplane.ClaimedTask) error {
	upstream := github.NewClient(credentialToken(task))
	upstream.SetQuotaObserver(observeCredentialQuota(control, task))
	return processSearchPreviewWithSearch(ctx, control, task, upstream.SearchRepositories)
}

func processSearchPreviewWithSearch(ctx context.Context, control *controlplane.Client,
	task *controlplane.ClaimedTask,
	search func(context.Context, string, string, *time.Time, time.Time, int) (github.SearchPage, github.Quota, error)) error {
	results, err := previewSearchResults(ctx, task.Discovery, search)
	if err != nil {
		return err
	}
	return control.CompletePreview(ctx, task.TaskID, task.Lease, results)
}

func previewSearchResults(ctx context.Context, snapshot *controlplane.DiscoverySnapshot,
	search func(context.Context, string, string, *time.Time, time.Time, int) (github.SearchPage, github.Quota, error)) ([]controlplane.PreviewSelectorResult, error) {
	if snapshot == nil || snapshot.Criteria == nil || snapshot.SearchWindow == nil ||
		len(snapshot.Criteria.Selectors) < 1 || len(snapshot.Criteria.Selectors) > 20 ||
		snapshot.SearchWindow.Until.IsZero() || snapshot.Checkpoint != "" {
		return nil, errors.New("invalid search preview snapshot")
	}
	results := make([]controlplane.PreviewSelectorResult, 0, len(snapshot.Criteria.Selectors))
	for _, selector := range selectorsFor(snapshot) {
		page, _, err := search(ctx, selector.Kind, selector.Value,
			nil, snapshot.SearchWindow.Until, 1)
		if err != nil {
			return nil, err
		}
		samples := toCandidates(page.Repositories)
		if len(samples) > 5 {
			samples = samples[:5]
		}
		results = append(results, controlplane.PreviewSelectorResult{
			Kind: selector.Kind, Value: selector.Value, Priority: selector.Priority,
			TotalCount: page.TotalCount, IncompleteResults: page.IncompleteResults,
			LimitReached: page.LimitReached, Samples: samples,
		})
	}
	return results, nil
}

func selectorsFor(snapshot *controlplane.DiscoverySnapshot) []controlplane.SearchSelector {
	selectors := append([]controlplane.SearchSelector(nil), snapshot.Criteria.Selectors...)
	sort.SliceStable(selectors, func(i, j int) bool {
		return selectors[i].Priority == "CORE" && selectors[j].Priority != "CORE"
	})
	return selectors
}

func toCandidates(repositories []github.Repository) []controlplane.RepositoryCandidate {
	result := make([]controlplane.RepositoryCandidate, 0, len(repositories))
	for _, candidate := range repositories {
		result = append(result, controlplane.RepositoryCandidate{
			RepositoryID: candidate.ID, Owner: candidate.Owner.Login,
			Name: candidate.Name, DefaultBranch: candidate.DefaultBranch,
		})
	}
	return result
}

func reportDiscoveryProgress(ctx context.Context, control *controlplane.Client,
	task *controlplane.ClaimedTask, previous string, next discoveryCursor,
	repositories []controlplane.RepositoryCandidate, deferred int) (string, error) {
	encoded, err := encodeCursor(next)
	if err != nil {
		return "", err
	}
	if encoded == previous {
		return "", errors.New("discovery cursor did not advance")
	}
	reason := ""
	if deferred > 0 {
		reason = "BUDGET"
	}
	err = control.ReportDiscoveryBatch(ctx, task.TaskID, task.Lease, controlplane.DiscoveryBatch{
		PreviousCheckpoint: &previous, Checkpoint: encoded, Repositories: repositories,
		DeferredCount: deferred, DeferralReason: reason,
	})
	if err != nil {
		return "", err
	}
	return encoded, nil
}

func encodeCursor(cursor discoveryCursor) (string, error) {
	encoded, err := json.Marshal(cursor)
	if err != nil || len(encoded) > 4096 {
		return "", errors.New("discovery cursor exceeds protocol limit")
	}
	return string(encoded), nil
}

func searchBounds(window *controlplane.SearchWindow, path string) (time.Time, time.Time, error) {
	if window.Until.IsZero() || window.Mode != "FULL_RECHECK" && window.Mode != "INCREMENTAL" ||
		window.Mode == "INCREMENTAL" && window.Since.IsZero() {
		return time.Time{}, time.Time{}, errors.New("invalid fixed search window")
	}
	lower := int64(0)
	if window.Mode == "INCREMENTAL" {
		lower = window.Since.Unix()
	}
	upper := window.Until.Unix()
	if lower > upper {
		return time.Time{}, time.Time{}, errors.New("invalid fixed search bounds")
	}
	for _, step := range path {
		if lower >= upper {
			return time.Time{}, time.Time{}, errors.New("discovery split cursor exceeds search window")
		}
		middle := lower + (upper-lower)/2
		if step == 'L' {
			upper = middle
		} else if step == 'R' {
			lower = middle + 1
		} else {
			return time.Time{}, time.Time{}, errors.New("invalid discovery split cursor")
		}
	}
	return time.Unix(lower, 0).UTC(), time.Unix(upper, 0).UTC(), nil
}

func nextSearchCursor(current discoveryCursor) discoveryCursor {
	next := current
	next.Page, next.Offset = 1, 0
	for len(next.Path) > 0 {
		last := next.Path[len(next.Path)-1]
		next.Path = next.Path[:len(next.Path)-1]
		if last == 'L' {
			next.Path += "R"
			return next
		}
	}
	next.Selector++
	return next
}

func deferDiscovery(ctx context.Context, control *controlplane.Client,
	task *controlplane.ClaimedTask, checkpoint, reason string, retryAt time.Time) error {
	_, err := control.CompleteDiscovery(ctx, task.TaskID, task.Lease, checkpoint, futureRetry(retryAt), reason)
	return err
}

func completeDiscoveryError(ctx context.Context, control *controlplane.Client,
	task *controlplane.ClaimedTask, checkpoint string, err error) error {
	var upstream *github.APIError
	if errors.As(err, &upstream) && upstream.Code == "GITHUB_RATE_LIMITED" {
		if reportErr := reportCredentialProblem(ctx, control, task, err); reportErr != nil {
			return reportErr
		}
		return deferDiscovery(ctx, control, task, checkpoint, "RATE_LIMIT", upstream.RetryAt)
	}
	return err
}
