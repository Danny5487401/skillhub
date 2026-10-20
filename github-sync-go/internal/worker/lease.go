package worker

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/iflytek/skillhub/github-sync-go/internal/controlplane"
	"github.com/iflytek/skillhub/github-sync-go/internal/github"
)

// ExecuteRepository renews the Java lease while processing and stops all
// downstream work as soon as renewal fails or shutdown begins.
func ExecuteRepository(ctx context.Context, control *controlplane.Client,
	task *controlplane.ClaimedTask, limits RepositoryLimits) error {
	if task == nil || task.Kind != "REPOSITORY_SYNC" || task.LeaseExpiresAt.IsZero() {
		return errors.New("invalid leased repository task")
	}
	return executeLeasedTask(ctx, control, task, func(workContext context.Context) error {
		return ProcessRepository(workContext, control, task, limits)
	})
}

func ExecuteDiscovery(ctx context.Context, control *controlplane.Client,
	task *controlplane.ClaimedTask, limits DiscoveryLimits) error {
	if task == nil || task.Kind != "DISCOVERY" || task.LeaseExpiresAt.IsZero() {
		return errors.New("invalid leased discovery task")
	}
	return executeLeasedTask(ctx, control, task, func(workContext context.Context) error {
		return ProcessDiscovery(workContext, control, task, limits)
	})
}

func executeLeasedTask(ctx context.Context, control *controlplane.Client,
	task *controlplane.ClaimedTask, process func(context.Context) error) error {
	workContext, cancel := context.WithCancel(ctx)
	defer cancel()
	renewDone := make(chan error, 1)
	go func() {
		renewDone <- keepLease(workContext, cancel, control, task)
	}()
	workErr := runPlatformTask(workContext, control, task, process)
	cancel()
	renewErr := <-renewDone
	if ctx.Err() != nil {
		return ctx.Err()
	}
	if renewErr != nil {
		return renewErr
	}
	if workErr == nil {
		return nil
	}
	if errors.Is(workErr, context.Canceled) {
		return workErr
	}
	failureReason, retryAt := classifyFailure(workErr, task.SourceID != 0)
	reportContext, reportCancel := context.WithTimeout(ctx, 20*time.Second)
	defer reportCancel()
	credentialErr := reportCredentialProblem(reportContext, control, task, workErr)
	var upstream *github.APIError
	var quota *controlplane.GithubQuota
	if errors.As(workErr, &upstream) {
		quota = credentialQuota(upstream.Quota)
	}
	_, reportErr := control.Fail(reportContext, task.TaskID, task.Lease,
		failureReason, safeFailureDetail(workErr), retryAt, quota)
	if reportErr != nil {
		return reportErr
	}
	if task.PlatformControlVersion != nil {
		// Fail has settled all acquisition; acknowledge a pause racing that report.
		_, ackErr := (&platformExecution{control: control, task: task}).acknowledge(reportContext)
		if ackErr != nil {
			return ackErr
		}
	}
	if credentialErr != nil {
		return credentialErr
	}
	return workErr
}

func safeFailureDetail(err error) string {
	var upstream *github.APIError
	if errors.As(err, &upstream) {
		return fmt.Sprintf("GITHUB_HTTP_%d:%s", upstream.Status, upstream.Code)
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return "WORKER_TIMEOUT"
	}
	return "WORKER_ERROR"
}

func reportCredentialProblem(ctx context.Context, control *controlplane.Client,
	task *controlplane.ClaimedTask, err error) error {
	// Official requests already persisted their lease-derived observation. The
	// legacy RATE_LIMITED status cools the entire credential, including CORE
	// after a SEARCH-only primary limit, so it must not be reported again.
	if task.OfficialDiscovery != nil || repositoryNeedsOfficialControl(task) {
		return nil
	}
	if task.Credential == nil || task.Credential.CredentialID == "" {
		return nil
	}
	var upstream *github.APIError
	if !errors.As(err, &upstream) {
		return nil
	}
	quota := credentialQuota(upstream.Quota)
	switch upstream.Code {
	case "AUTH_INVALID":
		return control.ReportCredentialStatus(ctx, task.TaskID, task.Lease,
			task.Credential.CredentialID, "AUTH_INVALID", time.Time{}, quota)
	case "GITHUB_RATE_LIMITED":
		return control.ReportCredentialStatus(ctx, task.TaskID, task.Lease,
			task.Credential.CredentialID, "RATE_LIMITED", futureRetry(upstream.RetryAt), quota)
	default:
		return nil
	}
}

func credentialQuota(observed github.Quota) *controlplane.GithubQuota {
	if observed.Remaining < 0 || observed.ResetAt.IsZero() {
		return nil
	}
	resource := strings.ToUpper(observed.Resource)
	switch resource {
	case "CORE", "SEARCH", "GRAPHQL":
	default:
		resource = "OTHER"
	}
	return &controlplane.GithubQuota{
		Resource: resource, Remaining: observed.Remaining, ResetAt: observed.ResetAt,
	}
}

func observeCredentialQuota(control *controlplane.Client,
	task *controlplane.ClaimedTask) func(context.Context, github.Quota) {
	if task.Credential == nil || task.Credential.CredentialID == "" {
		return nil
	}
	return func(ctx context.Context, observed github.Quota) {
		quota := credentialQuota(observed)
		if quota == nil {
			return
		}
		reportCtx, cancel := context.WithTimeout(ctx, 2*time.Second)
		defer cancel()
		_ = control.ReportCredentialStatus(reportCtx, task.TaskID, task.Lease,
			task.Credential.CredentialID, "OBSERVED", time.Time{}, quota)
	}
}

func keepLease(ctx context.Context, cancel context.CancelFunc, control *controlplane.Client,
	task *controlplane.ClaimedTask) error {
	expiresAt := task.LeaseExpiresAt
	for {
		remaining := time.Until(expiresAt)
		if remaining <= 0 {
			cancel()
			return errors.New("GitHub sync lease expired")
		}
		wait := remaining / 3
		if wait > time.Minute {
			wait = time.Minute
		}
		if wait < time.Second {
			wait = time.Second
		}
		timer := time.NewTimer(wait)
		select {
		case <-ctx.Done():
			timer.Stop()
			return nil
		case <-timer.C:
		}
		var err error
		expiresAt, err = control.Renew(ctx, task.TaskID, task.Lease)
		if err != nil {
			cancel()
			return err
		}
	}
}

func classifyFailure(err error, registeredSource bool) (string, time.Time) {
	var upstream *github.APIError
	if errors.As(err, &upstream) {
		switch upstream.Code {
		case "GITHUB_RATE_LIMITED":
			return "GITHUB_RATE_LIMITED", futureRetry(upstream.RetryAt)
		case "SOURCE_UNREADABLE":
			if registeredSource {
				return "SOURCE_UNREADABLE", time.Time{}
			}
		}
	}
	return "RETRYABLE_ACQUISITION", time.Time{}
}
