package controlplane

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"slices"
	"time"
	"unicode/utf8"
)

// CompleteOfficialDiscovery releases a search GAP as PARTIAL for its siblings;
// owner GAPs retain RETRY_WAIT. The acknowledged checkpoint is sent unchanged.
func (c *Client) CompleteOfficialDiscovery(ctx context.Context, taskID string, lease LeaseProof,
	checkpoint string, retryAt time.Time, deferralReason, channel string) (string, error) {
	var progress OfficialCheckpoint
	if err := json.Unmarshal([]byte(checkpoint), &progress); err != nil || progress.FormatVersion != 1 ||
		!slices.Contains([]string{"REPOSITORY_METADATA", "README", "OWNER_EXPANSION"}, channel) ||
		(channel == "OWNER_EXPANSION" && progress.Coverage == "SPLIT") {
		return "", errors.New("invalid official discovery completion checkpoint")
	}
	deferredStatus := "RETRY_WAIT"
	switch progress.Coverage {
	case "GAP":
		if retryAt.IsZero() {
			return "", errors.New("official GAP completion requires a retry time")
		}
		if channel != "OWNER_EXPANSION" {
			deferredStatus = "PARTIAL"
		}
	case "IN_PROGRESS":
		if retryAt.IsZero() {
			return "", errors.New("unfinished official discovery requires a retry time")
		}
	case "COMPLETE", "SPLIT":
		if !retryAt.IsZero() {
			return "", errors.New("finished official shard cannot be deferred")
		}
	default:
		return "", errors.New("unsupported official discovery completion coverage")
	}
	return c.completeDiscovery(ctx, taskID, lease, checkpoint, retryAt, deferralReason, deferredStatus)
}

// HeartbeatWithCapabilities is separate from the legacy empty heartbeat so
// protocol scaffolding cannot accidentally advertise an executable capability.
func (c *Client) HeartbeatWithCapabilities(ctx context.Context, workerID string, capabilities []string) error {
	if !boundedOfficialString(workerID, 128) {
		return errors.New("invalid worker ID")
	}
	if err := validateCapabilities(capabilities); err != nil {
		return err
	}
	if capabilities == nil {
		capabilities = []string{}
	}
	status, err := c.post(ctx, "/heartbeat", map[string]any{
		"workerId": workerID, "protocolMajor": 1, "capabilities": capabilities,
	}, nil)
	if err != nil {
		return err
	}
	if status != http.StatusNoContent {
		return errors.New("internal API did not accept worker heartbeat")
	}
	return nil
}

func (c *Client) GetOfficialTaskControl(ctx context.Context, taskID string, lease LeaseProof) (OfficialTaskControl, error) {
	var control OfficialTaskControl
	_, err := c.post(ctx, "/tasks/"+url.PathEscape(taskID)+"/official-control", map[string]any{"lease": lease}, &control)
	if err != nil {
		return OfficialTaskControl{}, err
	}
	if control.ConfigurationVersion < 1 || (control.Action != "CONTINUE" && control.Action != "PAUSE") ||
		(control.Action == "PAUSE" && !slices.Contains([]string{"DISABLED", "SOURCE_PAUSED", "EXCLUDED", "CANDIDATE_CAPACITY", "REVIEW_BACKPRESSURE", "QUOTA_COOLDOWN"}, control.Reason)) {
		return OfficialTaskControl{}, errors.New("internal API returned invalid official task control")
	}
	if control.Reason == "QUOTA_COOLDOWN" && (control.RetryAt == nil || control.RetryAt.IsZero()) {
		return OfficialTaskControl{}, errors.New("quota cooldown requires retry time")
	}
	return control, nil
}

// PauseOfficialDiscovery saves only the last acknowledged checkpoint. A nil
// checkpoint is sent as null when no batch has been accepted yet.
func (c *Client) PauseOfficialDiscovery(ctx context.Context, taskID string, lease LeaseProof, checkpoint *string) error {
	if checkpoint != nil && (len(*checkpoint) > 4096 || !utf8.ValidString(*checkpoint)) {
		return errors.New("invalid official discovery pause checkpoint")
	}
	return c.pauseOfficial(ctx, taskID, map[string]any{
		"lease": lease, "kind": "DISCOVERY", "discoveryCheckpoint": checkpoint,
	})
}

// PauseOfficialRepository also supports pausing before a commit is known. The
// server atomically checks the latest ownership, accepted paths and fixed commit.
func (c *Client) PauseOfficialRepository(ctx context.Context, taskID string, lease LeaseProof, progress OfficialRepositoryProgress) error {
	if progress.ObservedCommitSHA == nil {
		if progress.PathCheckpoint != nil || len(progress.IncompleteSubtrees) != 0 || len(progress.PathResults) != 0 {
			return errors.New("official repository pause has progress without a commit")
		}
	} else if !validOfficialCommit(*progress.ObservedCommitSHA) {
		return errors.New("invalid official repository pause commit")
	}
	if (progress.PathCheckpoint != nil && (!utf8.ValidString(*progress.PathCheckpoint) || utf8.RuneCountInString(*progress.PathCheckpoint) > 1024)) ||
		len(progress.IncompleteSubtrees) > 1000 || len(progress.PathResults) > 10000 {
		return errors.New("official repository pause exceeds progress limits")
	}
	for _, path := range progress.IncompleteSubtrees {
		if !boundedOfficialString(path, 1024) {
			return errors.New("invalid official incomplete subtree")
		}
	}
	seen := make(map[string]bool, len(progress.PathResults))
	for _, result := range progress.PathResults {
		if !boundedOfficialString(result.SkillPath, 1024) || seen[result.SkillPath] ||
			!utf8.ValidString(result.Reason) || utf8.RuneCountInString(result.Reason) > 500 ||
			!slices.Contains([]string{"ACCEPTED", "IGNORED", "RETRYABLE_FAILURE", "TERMINAL_FAILURE"}, result.Outcome) ||
			(result.Outcome == "ACCEPTED") != (result.IngestionID != "") {
			return errors.New("invalid official repository pause path result")
		}
		seen[result.SkillPath] = true
	}
	if progress.IncompleteSubtrees == nil {
		progress.IncompleteSubtrees = []string{}
	}
	if progress.PathResults == nil {
		progress.PathResults = []RepositoryPathResult{}
	}
	return c.pauseOfficial(ctx, taskID, map[string]any{
		"lease": lease, "kind": "REPOSITORY_SYNC", "repositoryProgress": progress,
	})
}

func validOfficialCommit(commit string) bool {
	if len(commit) != 40 {
		return false
	}
	for _, digit := range commit {
		if (digit < '0' || digit > '9') && (digit < 'a' || digit > 'f') {
			return false
		}
	}
	return true
}

// On APIError.Code CONTROL_CHANGED, callers must re-read control and retain
// their lease. This endpoint-specific code does not extend legacy enums.
func (c *Client) pauseOfficial(ctx context.Context, taskID string, request any) error {
	var response struct {
		Status string `json:"status"`
	}
	_, err := c.post(ctx, "/tasks/"+url.PathEscape(taskID)+"/official-pause", request, &response)
	if err != nil {
		return err
	}
	if response.Status != "PAUSED" {
		return errors.New("internal API did not pause official task")
	}
	return nil
}

// RecoverOfficialCheckpoint returns the replacement task ID, not a new lease.
// The server has retired the old task; the caller must stop writing to it.
func (c *Client) RecoverOfficialCheckpoint(ctx context.Context, taskID string, recovery OfficialCheckpointRecovery) (string, error) {
	if !slices.Contains([]string{"MALFORMED", "FORMAT_UNSUPPORTED", "IDENTITY_MISMATCH"}, recovery.Reason) ||
		len(recovery.RejectedCheckpoint) > 4096 || !utf8.ValidString(recovery.RejectedCheckpoint) {
		return "", errors.New("invalid official checkpoint recovery")
	}
	var response struct {
		Status            string `json:"status"`
		ReplacementTaskID string `json:"replacementTaskId"`
	}
	_, err := c.post(ctx, "/tasks/"+url.PathEscape(taskID)+"/official-checkpoint-recovery", recovery, &response)
	if err != nil {
		return "", err
	}
	if response.Status != "REPLAY_SCHEDULED" || response.ReplacementTaskID == "" {
		return "", errors.New("internal API did not schedule checkpoint replay")
	}
	return response.ReplacementTaskID, nil
}

// ReportOfficialCredentialStatus carries the optional observation while leaving
// the legacy credential-status enum and payload unchanged for existing callers.
func (c *Client) ReportOfficialCredentialStatus(ctx context.Context, taskID string, lease LeaseProof,
	credentialID, status string, retryAt time.Time, quota *GithubQuota, observation OfficialQuotaObservation) error {
	if !slices.Contains([]string{"OBSERVED", "AUTH_INVALID", "RATE_LIMITED"}, status) ||
		(status == "RATE_LIMITED") != !retryAt.IsZero() || credentialID == "" ||
		(status == "OBSERVED" && quota == nil) ||
		(observation.State == "UNKNOWN" && quota != nil) ||
		!slices.Contains([]string{"CORE", "SEARCH", "GRAPHQL", "OTHER"}, observation.Resource) || observation.ObservedAt.IsZero() ||
		!slices.Contains([]string{"OBSERVED", "UNKNOWN", "PRIMARY_EXHAUSTED", "SECONDARY_COOLDOWN", "AUTH_INVALID"}, observation.State) ||
		(observation.Remaining != nil && *observation.Remaining < 0) ||
		(observation.ResetAt != nil && observation.ResetAt.IsZero()) ||
		(observation.RetryAfterSeconds != nil && *observation.RetryAfterSeconds < 0) ||
		(observation.State == "UNKNOWN" && observation.Remaining != nil) ||
		((observation.State == "OBSERVED" || observation.State == "PRIMARY_EXHAUSTED") && (observation.Remaining == nil || observation.ResetAt == nil)) {
		return errors.New("invalid official credential observation")
	}
	request := map[string]any{
		"lease": lease, "credentialId": credentialID, "status": status, "officialQuotaObservation": observation,
	}
	if quota != nil {
		request["githubQuota"] = quota
	}
	if !retryAt.IsZero() {
		request["retryAt"] = retryAt
	}
	responseStatus, err := c.post(ctx, "/tasks/"+url.PathEscape(taskID)+"/credential-status", request, nil)
	if err != nil {
		return err
	}
	if responseStatus != http.StatusNoContent {
		return errors.New("internal API did not accept official credential observation")
	}
	return nil
}

// ReportOfficialQuota lets Java derive the quota subject from the lease, including
// anonymous requests. It never accepts a caller-selected credential or subject.
func (c *Client) ReportOfficialQuota(ctx context.Context, taskID string, lease LeaseProof, observation OfficialQuotaObservation) error {
	now := time.Now()
	if !slices.Contains([]string{"CORE", "SEARCH", "GRAPHQL", "OTHER"}, observation.Resource) || observation.ObservedAt.IsZero() ||
		observation.ObservedAt.Before(now.Add(-5*time.Minute)) || observation.ObservedAt.After(now.Add(5*time.Minute)) ||
		!slices.Contains([]string{"OBSERVED", "UNKNOWN", "PRIMARY_EXHAUSTED", "SECONDARY_COOLDOWN", "AUTH_INVALID"}, observation.State) ||
		(observation.Remaining != nil && *observation.Remaining < 0) ||
		(observation.ResetAt != nil && (observation.ResetAt.IsZero() || observation.ResetAt.After(now.Add(24*time.Hour)))) ||
		(observation.RetryAfterSeconds != nil && (*observation.RetryAfterSeconds < 0 || *observation.RetryAfterSeconds > 86400)) ||
		(observation.State == "UNKNOWN" && (observation.Remaining != nil || observation.ResetAt != nil)) ||
		(observation.State == "PRIMARY_EXHAUSTED" && (observation.Remaining == nil || *observation.Remaining != 0 || observation.ResetAt == nil || !observation.ResetAt.After(now))) ||
		((observation.State == "OBSERVED" || observation.State == "PRIMARY_EXHAUSTED") && (observation.Remaining == nil || observation.ResetAt == nil)) {
		return errors.New("invalid official quota observation")
	}
	status, err := c.post(ctx, "/tasks/"+url.PathEscape(taskID)+"/official-quota", map[string]any{"lease": lease, "observation": observation}, nil)
	if err != nil {
		return err
	}
	if status != http.StatusNoContent {
		return errors.New("internal API did not accept official quota observation")
	}
	return nil
}
