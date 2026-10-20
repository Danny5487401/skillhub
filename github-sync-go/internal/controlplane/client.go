package controlplane

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

const maxResponseBytes = 16 << 20

// Client speaks the versioned internal API. It never uses the GitHub proxy
// transport and never sends a service token to a redirect target.
type Client struct {
	baseURL string
	token   string
	http    *http.Client
}

func NewClient(baseURL, serviceToken string) (*Client, error) {
	parsed, err := url.Parse(baseURL)
	if err != nil || (parsed.Scheme != "http" && parsed.Scheme != "https") || parsed.Host == "" ||
		parsed.User != nil || parsed.RawQuery != "" || parsed.Fragment != "" ||
		len(strings.TrimSpace(serviceToken)) < 32 {
		return nil, errors.New("invalid internal API URL or missing service token")
	}
	return &Client{
		baseURL: strings.TrimRight(baseURL, "/"),
		token:   strings.TrimSpace(serviceToken),
		http: &http.Client{
			Timeout: 20 * time.Second,
			Transport: &http.Transport{
				Proxy:                 nil,
				MaxIdleConnsPerHost:   4,
				ResponseHeaderTimeout: 15 * time.Second,
			},
			CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
				return http.ErrUseLastResponse
			},
		},
	}, nil
}

func (c *Client) Heartbeat(ctx context.Context) error {
	_, err := c.post(ctx, "/heartbeat", struct{}{}, nil)
	return err
}

type LeaseProof struct {
	Generation int64  `json:"generation"`
	Token      string `json:"token"`
}

type PackageAdmission struct {
	AdmissionID string `json:"admissionId"`
}

type ClaimedTask struct {
	PlatformControlVersion *int64                     `json:"platformControlVersion,omitempty"`
	TaskID                 string                     `json:"taskId"`
	RunID                  string                     `json:"runId"`
	Kind                   string                     `json:"kind"`
	Mode                   string                     `json:"mode,omitempty"`
	NamespaceID            int64                      `json:"namespaceId"`
	SourceID               int64                      `json:"sourceId,omitempty"`
	Lease                  LeaseProof                 `json:"lease"`
	LeaseExpiresAt         time.Time                  `json:"leaseExpiresAt"`
	Discovery              *DiscoverySnapshot         `json:"discovery,omitempty"`
	OfficialDiscovery      *OfficialDiscoverySnapshot `json:"officialDiscovery,omitempty"`
	RequiredCapabilities   []string                   `json:"requiredCapabilities,omitempty"`
	Repository             *RepositorySnapshot        `json:"repository,omitempty"`
	Credential             *GithubCredential          `json:"credential,omitempty"`
	MaxPackageBytes        int64                      `json:"maxPackageBytes,omitempty"`
	MaxSingleFileBytes     int64                      `json:"maxSingleFileBytes,omitempty"`
	MaxFilesPerPackage     int                        `json:"maxFilesPerPackage,omitempty"`
	MaxUploadBytes         int64                      `json:"maxUploadBytes,omitempty"`
}

type GithubCredential struct {
	CredentialID string `json:"credentialId"`
	Token        string `json:"token"`
}

type GithubQuota struct {
	Resource  string    `json:"resource"`
	Remaining int       `json:"remaining"`
	ResetAt   time.Time `json:"resetAt"`
}

type DiscoverySnapshot struct {
	EntryType       string          `json:"entryType"`
	RuleID          string          `json:"ruleId,omitempty"`
	RuleVersion     int64           `json:"ruleVersion"`
	Criteria        *SearchCriteria `json:"criteria,omitempty"`
	SearchWindow    *SearchWindow   `json:"searchWindow,omitempty"`
	Owner           string          `json:"owner,omitempty"`
	RepositoryName  string          `json:"repositoryName,omitempty"`
	Checkpoint      string          `json:"checkpoint,omitempty"`
	CandidateBudget int             `json:"candidateBudget,omitempty"`
}

type SearchCriteria struct {
	Selectors []SearchSelector `json:"selectors"`
}

type SearchSelector struct {
	Kind     string `json:"kind"`
	Value    string `json:"value"`
	Priority string `json:"priority"`
}

type SearchWindow struct {
	Mode  string    `json:"mode"`
	Since time.Time `json:"since,omitempty"`
	Until time.Time `json:"until"`
}

type RepositorySnapshot struct {
	RepositoryID       int64    `json:"repositoryId"`
	Owner              string   `json:"owner"`
	Name               string   `json:"name"`
	FixedCommitSHA     string   `json:"fixedCommitSha,omitempty"`
	PathCheckpoint     string   `json:"pathCheckpoint,omitempty"`
	IncompleteSubtrees []string `json:"incompleteSubtrees,omitempty"`
	RetryPaths         []string `json:"retryPaths,omitempty"`
	AcceptedPaths      []string `json:"acceptedPaths,omitempty"`
	IgnorePatterns     []string `json:"ignorePatterns,omitempty"`
}

type RepositoryPathResult struct {
	SkillPath   string `json:"skillPath"`
	Outcome     string `json:"outcome"`
	IngestionID string `json:"ingestionId,omitempty"`
	Reason      string `json:"reason,omitempty"`
}

type RepositoryCompletion struct {
	CommitSHA          string
	PathCheckpoint     string
	IncompleteSubtrees []string
	PathResults        []RepositoryPathResult
	RetryAt            time.Time
	DeferralReason     string
}

type RepositoryCandidate struct {
	RepositoryID       int64                `json:"repositoryId"`
	Owner              string               `json:"owner"`
	Name               string               `json:"name"`
	DefaultBranch      string               `json:"defaultBranch,omitempty"`
	OfficialProvenance []OfficialProvenance `json:"officialProvenance,omitempty"`
}

// RepositoryMetadata is the canonical public identity returned by GitHub for
// the stable repository ID in a leased task.
type RepositoryMetadata struct {
	OwnerID       int64  `json:"ownerId,omitempty"`
	RepositoryID  int64  `json:"repositoryId"`
	Owner         string `json:"owner"`
	Name          string `json:"name"`
	DefaultBranch string `json:"defaultBranch"`
}

func (c *Client) UpdateRepositoryMetadata(ctx context.Context, taskID string,
	lease LeaseProof, metadata RepositoryMetadata) error {
	if metadata.RepositoryID <= 0 || metadata.Owner == "" || metadata.Name == "" ||
		metadata.DefaultBranch == "" || metadata.OwnerID < 0 {
		return errors.New("invalid GitHub repository metadata")
	}
	request := map[string]any{
		"lease": lease, "repositoryId": metadata.RepositoryID, "owner": metadata.Owner,
		"name": metadata.Name, "defaultBranch": metadata.DefaultBranch,
	}
	if metadata.OwnerID > 0 {
		request["ownerId"] = metadata.OwnerID
	}
	_, err := c.post(ctx, "/tasks/"+url.PathEscape(taskID)+"/repository-metadata", request, nil)
	return err
}

type DiscoveryBatch struct {
	PreviousCheckpoint *string                 `json:"previousCheckpoint"`
	Checkpoint         string                  `json:"checkpoint"`
	Repositories       []RepositoryCandidate   `json:"repositories"`
	DeferredCount      int                     `json:"deferredCount,omitempty"`
	DeferralReason     string                  `json:"deferralReason,omitempty"`
	OfficialBatch      *OfficialDiscoveryBatch `json:"officialBatch,omitempty"`
}

type PreviewSelectorResult struct {
	Kind              string                `json:"kind"`
	Value             string                `json:"value"`
	Priority          string                `json:"priority"`
	TotalCount        int                   `json:"totalCount"`
	IncompleteResults bool                  `json:"incompleteResults"`
	LimitReached      bool                  `json:"limitReached"`
	Samples           []RepositoryCandidate `json:"samples"`
}

func (c *Client) CompletePreview(ctx context.Context, taskID string, lease LeaseProof,
	results []PreviewSelectorResult) error {
	if len(results) < 1 || len(results) > 20 {
		return errors.New("invalid preview result count")
	}
	var response struct {
		Status string `json:"status"`
	}
	_, err := c.post(ctx, "/tasks/"+url.PathEscape(taskID)+"/preview-results", map[string]any{
		"lease": lease, "selectors": results,
	}, &response)
	if err != nil {
		return err
	}
	if response.Status != "ACQUISITION_COMPLETE" {
		return errors.New("internal API did not complete preview")
	}
	return nil
}

// APIError intentionally omits the server's free-form message, which can
// contain sensitive upstream details. Callers can act on Code and RetryAt.
type APIError struct {
	Status  int
	Code    string
	RetryAt time.Time
}

func (e *APIError) Error() string {
	return fmt.Sprintf("internal API returned HTTP %d (%s)", e.Status, e.SafeCode())
}

// SafeCode is suitable for logs. The response code is untrusted HTTP content.
func (e *APIError) SafeCode() string {
	switch e.Code {
	case "ADMISSION_STALE", "BACKPRESSURE_ACTIVE", "CANDIDATE_BUDGET_EXCEEDED",
		"CHECKPOINT_CONFLICT", "COMMIT_MISMATCH", "CONTROL_CHANGED", "PLATFORM_SCOPE_MISMATCH", "COVERAGE_CONFLICT",
		"COVERAGE_UNSUPPORTED", "INVALID_REQUEST", "LEASE_STALE",
		"PACKAGE_TOO_LARGE", "PATH_IGNORED", "PROTOCOL_UNSUPPORTED",
		"REPOSITORY_MISMATCH", "RESULT_CONFLICT", "UPLOAD_MISMATCH",
		"UPLOAD_NOT_READY", "UPLOAD_TICKET_INVALID", "SERVICE_UNAVAILABLE":
		return e.Code
	default:
		return "CONTROL_PLANE_ERROR"
	}
}

func (c *Client) Claim(ctx context.Context, workerID, kind string) (*ClaimedTask, error) {
	return c.ClaimWithCapabilities(ctx, workerID, kind, nil)
}

// ClaimWithCapabilities is opt-in. Capability declarations must only be supplied
// by an executor that implements their full lifecycle, including safe pause.
func (c *Client) ClaimWithCapabilities(ctx context.Context, workerID, kind string, capabilities []string) (*ClaimedTask, error) {
	if workerID == "" || len(workerID) > 128 ||
		(kind != "DISCOVERY" && kind != "REPOSITORY_SYNC") {
		return nil, errors.New("invalid worker ID or task kind")
	}
	if err := validateCapabilities(capabilities); err != nil {
		return nil, err
	}
	var task ClaimedTask
	request := map[string]any{
		"workerId": workerID, "protocolMajor": 1, "kind": kind,
	}
	if len(capabilities) > 0 {
		request["capabilities"] = capabilities
	}
	status, err := c.post(ctx, "/tasks/claim", request, &task)
	if err != nil {
		return nil, err
	}
	if status == http.StatusNoContent {
		return nil, nil
	}
	if task.TaskID == "" || task.RunID == "" || task.NamespaceID <= 0 ||
		task.Lease.Generation < 1 || task.Lease.Token == "" || task.LeaseExpiresAt.IsZero() ||
		task.Kind != kind {
		return nil, errors.New("internal API returned an incomplete task")
	}
	if err := validateOfficialTask(task, capabilities); err != nil {
		return nil, err
	}
	if task.OfficialDiscovery != nil {
		return &task, nil
	}
	if task.Kind == "DISCOVERY" && (task.Discovery == nil || task.Discovery.RuleVersion < 1 ||
		task.Discovery.CandidateBudget < 1 ||
		(task.Discovery.EntryType == "SEARCH_RULE" &&
			(task.Discovery.Criteria == nil || task.Discovery.SearchWindow == nil)) ||
		(task.Discovery.EntryType == "CURATED_OWNER" && task.Discovery.Owner == "")) {
		return nil, errors.New("internal API returned an incomplete discovery task")
	}
	if task.Kind == "DISCOVERY" && task.Discovery.EntryType == "CURATED_REPOSITORY" &&
		(task.Discovery.Owner == "" || task.Discovery.RepositoryName == "") {
		return nil, errors.New("internal API returned an incomplete discovery task")
	}
	if task.Kind == "REPOSITORY_SYNC" && (task.Repository == nil || task.Repository.RepositoryID <= 0 ||
		task.Repository.Owner == "" || task.Repository.Name == "" ||
		(task.Mode != "FULL" && task.Mode != "HEAD_ONLY")) {
		return nil, errors.New("internal API returned an incomplete repository task")
	}
	if task.Kind == "REPOSITORY_SYNC" && task.Mode == "FULL" &&
		(task.MaxPackageBytes < 1 || task.MaxSingleFileBytes < 1 ||
			task.MaxFilesPerPackage < 1 || task.MaxUploadBytes < 1) {
		return nil, errors.New("internal API returned no package limits")
	}
	return &task, nil
}

func (c *Client) Renew(ctx context.Context, taskID string, lease LeaseProof) (time.Time, error) {
	var response struct {
		LeaseExpiresAt time.Time `json:"leaseExpiresAt"`
	}
	_, err := c.post(ctx, "/tasks/"+url.PathEscape(taskID)+"/renew", map[string]any{
		"lease": lease,
	}, &response)
	if err != nil {
		return time.Time{}, err
	}
	if response.LeaseExpiresAt.IsZero() {
		return time.Time{}, errors.New("internal API returned no lease expiration")
	}
	return response.LeaseExpiresAt, nil
}

// AdmitPackage reserves capacity before the worker requests any file content for this Skill.
func (c *Client) AdmitPackage(ctx context.Context, taskID string, lease LeaseProof,
	commitSHA, skillPath string) (PackageAdmission, error) {
	var admission PackageAdmission
	_, err := c.post(ctx, "/tasks/"+url.PathEscape(taskID)+"/package-admissions", map[string]any{
		"lease": lease, "commitSha": commitSHA, "skillPath": skillPath,
	}, &admission)
	if err != nil {
		return PackageAdmission{}, err
	}
	if admission.AdmissionID == "" {
		return PackageAdmission{}, errors.New("internal API returned no package admission ID")
	}
	return admission, nil
}

// ReportDiscoveryBatch persists leads and advances an opaque checkpoint atomically.
// A replay with the same batch succeeds; a stale previous checkpoint is rejected.
func (c *Client) ReportDiscoveryBatch(ctx context.Context, taskID string,
	lease LeaseProof, batch DiscoveryBatch) error {
	if err := validateOfficialBatch(batch); err != nil {
		return err
	}
	if batch.Checkpoint == "" || len(batch.Checkpoint) > 4096 || len(batch.Repositories) > 100 ||
		batch.DeferredCount < 0 || (batch.DeferredCount > 0 && batch.DeferralReason == "") {
		return errors.New("invalid discovery batch")
	}
	for _, repository := range batch.Repositories {
		if repository.RepositoryID <= 0 || repository.Owner == "" || repository.Name == "" {
			return errors.New("invalid discovery repository candidate")
		}
	}
	if batch.Repositories == nil {
		batch.Repositories = []RepositoryCandidate{}
	}
	status, err := c.post(ctx, "/tasks/"+url.PathEscape(taskID)+"/discovered-repositories",
		struct {
			Lease LeaseProof `json:"lease"`
			DiscoveryBatch
		}{Lease: lease, DiscoveryBatch: batch}, nil)
	if err != nil {
		return err
	}
	if status != http.StatusAccepted {
		return errors.New("internal API did not accept discovery batch")
	}
	if observer, ok := ctx.Value(checkpointObserverKey{}).(func(string)); ok {
		observer(batch.Checkpoint)
	}
	return nil
}

// CompleteDiscovery closes a fully covered search or releases its lease for a
// delayed continuation. The checkpoint must already have been acknowledged.
func (c *Client) CompleteDiscovery(ctx context.Context, taskID string, lease LeaseProof,
	checkpoint string, retryAt time.Time, deferralReason string) (string, error) {
	return c.completeDiscovery(ctx, taskID, lease, checkpoint, retryAt, deferralReason, "RETRY_WAIT")
}

func (c *Client) completeDiscovery(ctx context.Context, taskID string, lease LeaseProof,
	checkpoint string, retryAt time.Time, deferralReason, deferredStatus string) (string, error) {
	if checkpoint == "" || len(checkpoint) > 4096 ||
		(retryAt.IsZero() && deferralReason != "") ||
		(!retryAt.IsZero() && !retryAt.After(time.Now())) {
		return "", errors.New("invalid discovery completion")
	}
	coverage := "COMPLETE"
	if !retryAt.IsZero() {
		coverage = "INCOMPLETE"
	}
	request := map[string]any{
		"lease": lease, "coverage": coverage, "discoveryCheckpoint": checkpoint,
	}
	if !retryAt.IsZero() {
		request["retryAt"] = retryAt
		if deferralReason != "" {
			request["deferralReason"] = deferralReason
		}
	}
	var response struct {
		Status string `json:"status"`
	}
	_, err := c.post(ctx, "/tasks/"+url.PathEscape(taskID)+"/complete", request, &response)
	if err != nil {
		return "", err
	}
	if (coverage == "COMPLETE" && response.Status != "ACQUISITION_COMPLETE") ||
		(coverage == "INCOMPLETE" && response.Status != deferredStatus) {
		return "", errors.New("internal API returned inconsistent discovery status")
	}
	return response.Status, nil
}

// CompleteHeadOnly records an observed commit without claiming package acquisition finished.
func (c *Client) CompleteHeadOnly(ctx context.Context, taskID string, lease LeaseProof, observedCommitSHA string) error {
	if len(observedCommitSHA) != 40 {
		return errors.New("observed commit SHA must have 40 characters")
	}
	var response struct {
		Status string `json:"status"`
	}
	_, err := c.post(ctx, "/tasks/"+url.PathEscape(taskID)+"/complete", map[string]any{
		"lease": lease, "coverage": "HEAD_ONLY", "observedCommitSha": observedCommitSHA,
	}, &response)
	if err != nil {
		return err
	}
	if response.Status != "ACQUISITION_COMPLETE" {
		return errors.New("internal API did not complete HEAD_ONLY task")
	}
	return nil
}

// CompleteRepository closes a fixed-revision acquisition or records a delayed
// continuation. Accepted paths must already have been acknowledged by Java.
func (c *Client) CompleteRepository(ctx context.Context, taskID string, lease LeaseProof,
	completion RepositoryCompletion) (string, error) {
	if len(completion.CommitSHA) != 40 || len(completion.PathCheckpoint) > 1024 ||
		len(completion.IncompleteSubtrees) > 1000 || len(completion.PathResults) > 10000 {
		return "", errors.New("invalid repository completion")
	}
	for _, digit := range completion.CommitSHA {
		if digit < '0' || digit > '9' && (digit < 'a' || digit > 'f') {
			return "", errors.New("invalid repository commit SHA")
		}
	}
	coverage := "COMPLETE"
	if !completion.RetryAt.IsZero() {
		if !completion.RetryAt.After(time.Now()) {
			return "", errors.New("repository retry time must be in the future")
		}
		coverage = "INCOMPLETE"
	} else if completion.DeferralReason != "" || len(completion.IncompleteSubtrees) > 0 {
		return "", errors.New("complete repository has deferred coverage")
	}
	for _, result := range completion.PathResults {
		if result.SkillPath == "" || (result.Outcome == "ACCEPTED") != (result.IngestionID != "") {
			return "", errors.New("invalid repository path result")
		}
		switch result.Outcome {
		case "ACCEPTED", "IGNORED", "TERMINAL_FAILURE":
		case "RETRYABLE_FAILURE":
			if coverage == "COMPLETE" {
				return "", errors.New("complete repository has retryable path")
			}
		default:
			return "", errors.New("invalid repository path outcome")
		}
	}
	request := map[string]any{
		"lease": lease, "coverage": coverage, "observedCommitSha": completion.CommitSHA,
		"pathResults": completion.PathResults, "incompleteSubtrees": completion.IncompleteSubtrees,
	}
	if completion.PathCheckpoint != "" {
		request["pathCheckpoint"] = completion.PathCheckpoint
	}
	if coverage == "INCOMPLETE" {
		request["retryAt"] = completion.RetryAt
		if completion.DeferralReason != "" {
			request["deferralReason"] = completion.DeferralReason
		}
	}
	var response struct {
		Status string `json:"status"`
	}
	_, err := c.post(ctx, "/tasks/"+url.PathEscape(taskID)+"/complete", request, &response)
	if err != nil {
		return "", err
	}
	if coverage == "COMPLETE" && response.Status != "ACQUISITION_COMPLETE" && response.Status != "PARTIAL" ||
		coverage == "INCOMPLETE" && response.Status != "RETRY_WAIT" {
		return "", errors.New("internal API returned inconsistent repository status")
	}
	return response.Status, nil
}

// Fail releases the lease and returns the Java-owned retry time.
func (c *Client) Fail(ctx context.Context, taskID string, lease LeaseProof,
	reason, detail string, retryAt time.Time, quota *GithubQuota) (time.Time, error) {
	request := map[string]any{"lease": lease, "reason": reason}
	if detail != "" {
		request["detail"] = detail
	}
	if quota != nil {
		request["githubQuota"] = quota
	}
	if !retryAt.IsZero() {
		request["retryAt"] = retryAt
	}
	var response struct {
		Status  string    `json:"status"`
		RetryAt time.Time `json:"retryAt"`
	}
	_, err := c.post(ctx, "/tasks/"+url.PathEscape(taskID)+"/fail", request, &response)
	if err != nil {
		return time.Time{}, err
	}
	if response.Status != "RETRY_WAIT" || response.RetryAt.IsZero() {
		return time.Time{}, errors.New("internal API returned no retry schedule")
	}
	return response.RetryAt, nil
}

// ReportCredentialStatus updates only the credential bound to this current task lease.
func (c *Client) ReportCredentialStatus(ctx context.Context, taskID string, lease LeaseProof,
	credentialID, status string, retryAt time.Time, quota *GithubQuota) error {
	request := map[string]any{"lease": lease, "credentialId": credentialID, "status": status}
	if !retryAt.IsZero() {
		request["retryAt"] = retryAt
	}
	if quota != nil {
		request["githubQuota"] = quota
	}
	_, err := c.post(ctx, "/tasks/"+url.PathEscape(taskID)+"/credential-status", request, nil)
	return err
}

func (c *Client) post(ctx context.Context, path string, body any, target any) (int, error) {
	encoded, err := json.Marshal(body)
	if err != nil {
		return 0, err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+path, bytes.NewReader(encoded))
	if err != nil {
		return 0, err
	}
	requestID := make([]byte, 16)
	if _, err := rand.Read(requestID); err != nil {
		return 0, err
	}
	request.Header.Set("Authorization", "Bearer "+c.token)
	request.Header.Set("X-Request-Id", hex.EncodeToString(requestID))
	request.Header.Set("Content-Type", "application/json")
	response, err := c.http.Do(request)
	if err != nil {
		return 0, err
	}
	defer response.Body.Close()
	limited := io.LimitReader(response.Body, maxResponseBytes+1)
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		var apiError struct {
			Code    string    `json:"code"`
			RetryAt time.Time `json:"retryAt"`
		}
		_ = json.NewDecoder(limited).Decode(&apiError)
		return response.StatusCode, &APIError{Status: response.StatusCode, Code: apiError.Code, RetryAt: apiError.RetryAt}
	}
	if response.StatusCode == http.StatusNoContent || target == nil {
		return response.StatusCode, nil
	}
	contents, err := io.ReadAll(limited)
	if err != nil || len(contents) > maxResponseBytes {
		return 0, errors.New("internal API response exceeds size limit")
	}
	if err := json.Unmarshal(contents, target); err != nil {
		return 0, fmt.Errorf("decode internal API response: %w", err)
	}
	return response.StatusCode, nil
}
