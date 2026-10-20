package github

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"
)

const (
	apiBaseURL       = "https://api.github.com"
	apiVersion       = "2026-03-10"
	maxResponseBytes = 9 << 20
)

var ErrResponseTooLarge = errors.New("GitHub API response exceeds size limit")

var (
	ownerPattern = regexp.MustCompile(`^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$`)
	repoPattern  = regexp.MustCompile(`^[A-Za-z0-9_.-]{1,100}$`)
	shaPattern   = regexp.MustCompile(`^[0-9a-f]{40}$`)
)

type Client struct {
	http          *http.Client
	token         string
	quotaObserver func(context.Context, Quota)
	beforeRequest func(context.Context) error
	afterRequest  func(context.Context, Quota, error) error
}

func (c *Client) SetQuotaObserver(observer func(context.Context, Quota)) {
	c.quotaObserver = observer
}

// SetRequestPolicy installs fail-closed hooks for official acquisition only.
func (c *Client) SetRequestPolicy(before func(context.Context) error, after func(context.Context, Quota, error) error) {
	c.beforeRequest, c.afterRequest = before, after
}

func NewClient(token string) *Client {
	return &Client{
		http: &http.Client{
			Timeout: 30 * time.Second,
			Transport: &http.Transport{
				Proxy:                 http.ProxyFromEnvironment,
				MaxIdleConnsPerHost:   4,
				ResponseHeaderTimeout: 20 * time.Second,
			},
			CheckRedirect: func(request *http.Request, previous []*http.Request) error {
				if len(previous) >= 4 || request.URL.Scheme != "https" ||
					request.URL.Host != "api.github.com" || request.URL.User != nil ||
					request.URL.Fragment != "" {
					return errors.New("unsafe GitHub API redirect")
				}
				return nil
			},
		},
		token: token,
	}
}

type Repository struct {
	ID            int64  `json:"id"`
	Name          string `json:"name"`
	Private       bool   `json:"private"`
	DefaultBranch string `json:"default_branch"`
	Owner         struct {
		Login string `json:"login"`
		ID    int64  `json:"id"`
	} `json:"owner"`
}

type Reference struct {
	Object struct {
		Type string `json:"type"`
		SHA  string `json:"sha"`
	} `json:"object"`
}

type Commit struct {
	SHA  string `json:"sha"`
	Tree struct {
		SHA string `json:"sha"`
	} `json:"tree"`
}

type TreeEntry struct {
	Path string `json:"path"`
	Mode string `json:"mode"`
	Type string `json:"type"`
	Size int64  `json:"size"`
	SHA  string `json:"sha"`
}

type Tree struct {
	SHA       string      `json:"sha"`
	Truncated bool        `json:"truncated"`
	Entries   []TreeEntry `json:"tree"`
}

type Quota struct {
	Resource  string
	Remaining int
	ResetAt   time.Time
}

type APIError struct {
	Status             int
	Code               string
	RetryAt            time.Time
	Quota              Quota
	SecondaryRateLimit bool
}

func (e *APIError) Error() string {
	return fmt.Sprintf("GitHub API returned HTTP %d (%s)", e.Status, e.Code)
}

func (c *Client) GetRepository(ctx context.Context, owner, name string) (Repository, Quota, error) {
	if err := validateRepository(owner, name); err != nil {
		return Repository{}, Quota{}, err
	}
	var repository Repository
	quota, err := c.get(ctx, "/repos/"+owner+"/"+name, &repository)
	if err != nil {
		return Repository{}, quota, err
	}
	if repository.Private || repository.ID <= 0 || repository.Owner.Login == "" || repository.Name == "" {
		return Repository{}, quota, errors.New("repository is private or has incomplete metadata")
	}
	return repository, quota, nil
}

func (c *Client) GetHead(ctx context.Context, repository Repository) (string, Quota, error) {
	if err := validateRepository(repository.Owner.Login, repository.Name); err != nil {
		return "", Quota{}, err
	}
	if repository.DefaultBranch == "" {
		return "", Quota{}, errors.New("repository has no default branch")
	}
	// Branch names may contain '/', so escape each segment while retaining the separator.
	segments := strings.Split(repository.DefaultBranch, "/")
	for i, segment := range segments {
		if segment == "" || segment == "." || segment == ".." {
			return "", Quota{}, errors.New("invalid default branch")
		}
		segments[i] = url.PathEscape(segment)
	}
	var reference Reference
	quota, err := c.get(ctx, "/repos/"+repository.Owner.Login+"/"+repository.Name+
		"/git/ref/heads/"+strings.Join(segments, "/"), &reference)
	if err != nil {
		return "", quota, err
	}
	if reference.Object.Type != "commit" || !shaPattern.MatchString(reference.Object.SHA) {
		return "", quota, errors.New("default branch did not resolve to a commit")
	}
	return reference.Object.SHA, quota, nil
}

func (c *Client) GetCommitTreeSHA(ctx context.Context, owner, name, commitSHA string) (string, Quota, error) {
	if err := validateRepository(owner, name); err != nil {
		return "", Quota{}, err
	}
	if !shaPattern.MatchString(commitSHA) {
		return "", Quota{}, errors.New("invalid commit SHA")
	}
	var commit Commit
	quota, err := c.get(ctx, "/repos/"+owner+"/"+name+"/git/commits/"+commitSHA, &commit)
	if err != nil {
		return "", quota, err
	}
	if commit.SHA != commitSHA || !shaPattern.MatchString(commit.Tree.SHA) {
		return "", quota, errors.New("GitHub commit does not match the fixed revision")
	}
	return commit.Tree.SHA, quota, nil
}

func (c *Client) GetTree(ctx context.Context, owner, name, treeSHA string, recursive bool) (Tree, Quota, error) {
	if err := validateRepository(owner, name); err != nil {
		return Tree{}, Quota{}, err
	}
	if !shaPattern.MatchString(treeSHA) {
		return Tree{}, Quota{}, errors.New("invalid tree SHA")
	}
	path := "/repos/" + owner + "/" + name + "/git/trees/" + treeSHA
	if recursive {
		path += "?recursive=1"
	}
	var tree Tree
	quota, err := c.get(ctx, path, &tree)
	if err != nil {
		return Tree{}, quota, err
	}
	if tree.SHA != treeSHA {
		return Tree{}, quota, errors.New("GitHub tree does not match the fixed revision")
	}
	return tree, quota, nil
}

func validateRepository(owner, name string) error {
	if !ownerPattern.MatchString(owner) || !repoPattern.MatchString(name) || name == "." || name == ".." {
		return errors.New("invalid GitHub repository coordinate")
	}
	return nil
}

// PublicCloneURL never embeds a credential in the remote URL.
func PublicCloneURL(owner, name string) (string, error) {
	if err := validateRepository(owner, name); err != nil {
		return "", err
	}
	return "https://github.com/" + owner + "/" + name + ".git", nil
}

func (c *Client) get(ctx context.Context, path string, target any) (quota Quota, resultErr error) {
	quota.Remaining = -1
	if err := CheckAcquisitionControl(ctx); err != nil {
		return quota, err
	}
	if c.beforeRequest != nil {
		if err := c.beforeRequest(ctx); err != nil {
			return quota, err
		}
	}
	if c.afterRequest != nil {
		defer func() {
			if err := c.afterRequest(ctx, quota, resultErr); err != nil {
				resultErr = err
			}
		}()
	}

	request, err := http.NewRequestWithContext(ctx, http.MethodGet, apiBaseURL+path, nil)
	if err != nil {
		return Quota{}, err
	}
	request.Header.Set("Accept", "application/vnd.github+json")
	request.Header.Set("X-GitHub-Api-Version", apiVersion)
	request.Header.Set("User-Agent", "SkillHub-GitHub-Sync")
	if c.token != "" {
		request.Header.Set("Authorization", "Bearer "+c.token)
	}
	response, err := c.http.Do(request)
	if err != nil {
		return quota, err
	}
	defer response.Body.Close()
	quota = parseQuota(response.Header)
	if c.quotaObserver != nil && quota.Remaining >= 0 && !quota.ResetAt.IsZero() {
		c.quotaObserver(ctx, quota)
	}
	if response.StatusCode != http.StatusOK {
		return quota, classifyError(response, quota)
	}
	contents, err := io.ReadAll(io.LimitReader(response.Body, maxResponseBytes+1))
	if err != nil {
		return quota, err
	}
	if len(contents) > maxResponseBytes {
		return quota, ErrResponseTooLarge
	}
	if err := json.Unmarshal(contents, target); err != nil {
		return quota, fmt.Errorf("decode GitHub API response: %w", err)
	}
	return quota, nil
}

func parseQuota(header http.Header) Quota {
	quota := Quota{Resource: header.Get("X-RateLimit-Resource"), Remaining: -1}
	if remaining, err := strconv.Atoi(header.Get("X-RateLimit-Remaining")); err == nil {
		quota.Remaining = remaining
	}
	if reset, err := strconv.ParseInt(header.Get("X-RateLimit-Reset"), 10, 64); err == nil {
		quota.ResetAt = time.Unix(reset, 0).UTC()
	}
	return quota
}

func classifyError(response *http.Response, quota Quota) error {
	status := response.StatusCode
	classified := &APIError{Status: status, Quota: quota, Code: "RETRYABLE_FAILURE"}
	var payload struct {
		Message string `json:"message"`
	}
	_ = json.NewDecoder(io.LimitReader(response.Body, 8192)).Decode(&payload)
	if status == http.StatusUnauthorized {
		classified.Code = "AUTH_INVALID"
	} else if status == http.StatusNotFound || status == http.StatusForbidden {
		classified.Code = "SOURCE_UNREADABLE"
	}
	if status == http.StatusTooManyRequests ||
		(status == http.StatusForbidden && (response.Header.Get("X-RateLimit-Remaining") == "0" ||
			response.Header.Get("Retry-After") != "" || strings.Contains(strings.ToLower(payload.Message), "rate limit"))) {
		classified.Code = "GITHUB_RATE_LIMITED"
		classified.SecondaryRateLimit = response.Header.Get("Retry-After") != "" ||
			strings.Contains(strings.ToLower(payload.Message), "secondary rate limit") || quota.Remaining > 0
		classified.RetryAt = rateLimitRetryAt(response.Header, quota)
	}
	return classified
}

func rateLimitRetryAt(header http.Header, quota Quota) time.Time {
	if seconds, err := strconv.Atoi(header.Get("Retry-After")); err == nil && seconds > 0 {
		return time.Now().Add(time.Duration(seconds) * time.Second)
	}
	if !quota.ResetAt.IsZero() && quota.Remaining == 0 {
		return quota.ResetAt
	}
	return time.Now().Add(time.Minute)
}
