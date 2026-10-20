package worker

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/iflytek/skillhub/github-sync-go/internal/controlplane"
	"github.com/iflytek/skillhub/github-sync-go/internal/github"
)

type officialFixture struct {
	before                  func(context.Context) error
	after                   func(context.Context, github.Quota, error) error
	search                  func(int) (github.SearchPage, error)
	owner                   func(int) github.OwnerIdentity
	list                    func(string, int) github.OwnerPage
	searches, owners, lists int
}

func (f *officialFixture) SetRequestPolicy(before func(context.Context) error, after func(context.Context, github.Quota, error) error) {
	f.before, f.after = before, after
}
func (f *officialFixture) observe(ctx context.Context, resource string, err error) error {
	if e := f.after(ctx, github.Quota{Resource: resource, Remaining: 100, ResetAt: time.Now().Add(time.Hour)}, err); e != nil {
		return e
	}
	return err
}
func (f *officialFixture) SearchOfficialRepositories(ctx context.Context, _ github.OfficialRepositoryQuery, _ github.OfficialRepositoryRange, _ time.Time, page int) (github.SearchPage, github.Quota, error) {
	if err := f.before(ctx); err != nil {
		return github.SearchPage{}, github.Quota{}, err
	}
	f.searches++
	p, err := f.search(page)
	return p, github.Quota{}, f.observe(ctx, "SEARCH", err)
}
func (f *officialFixture) GetOwnerByID(ctx context.Context, id int64) (github.OwnerIdentity, github.Quota, error) {
	if err := f.before(ctx); err != nil {
		return github.OwnerIdentity{}, github.Quota{}, err
	}
	f.owners++
	o := f.owner(f.owners)
	return o, github.Quota{}, f.observe(ctx, "CORE", nil)
}
func (f *officialFixture) ListPublicOwnerRepositories(ctx context.Context, login string, _ github.OwnerType, page int) (github.OwnerPage, github.Quota, error) {
	if err := f.before(ctx); err != nil {
		return github.OwnerPage{}, github.Quota{}, err
	}
	f.lists++
	p := f.list(login, page)
	return p, github.Quota{}, f.observe(ctx, "CORE", nil)
}

type officialHarness struct {
	t           *testing.T
	control     *controlplane.Client
	accepted    string
	attempts    []controlplane.DiscoveryBatch
	completions []struct {
		Coverage            string
		DiscoveryCheckpoint string
		RetryAt             time.Time
	}
	paused                *string
	pause                 bool
	rejectCandidate       bool
	loseCandidateResponse bool
	quotaFailure          bool
	observations          []controlplane.OfficialQuotaObservation
	channel               string
}

func resetOfficialQuota(t *testing.T) {
	reset := func() {
		officialProcessQuota.Lock()
		defer officialProcessQuota.Unlock()
		officialProcessQuota.globalUntil = time.Time{}
		officialProcessQuota.resourceUntil = make(map[string]time.Time)
		officialProcessQuota.failures = 0
		officialProcessQuota.lastFailure = time.Time{}
	}
	reset()
	t.Cleanup(reset)
}
func newOfficialHarness(t *testing.T, channel string) *officialHarness {
	t.Helper()
	resetOfficialQuota(t)
	h := &officialHarness{t: t, channel: channel}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case strings.HasSuffix(r.URL.Path, "/official-control"):
			if h.pause {
				_, _ = w.Write([]byte(`{"action":"PAUSE","configurationVersion":1,"reason":"DISABLED"}`))
			} else {
				_, _ = w.Write([]byte(`{"action":"CONTINUE","configurationVersion":1}`))
			}
		case strings.HasSuffix(r.URL.Path, "/official-quota"):
			var body struct {
				Observation  controlplane.OfficialQuotaObservation
				CredentialID string
				SubjectKey   string
			}
			if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
				t.Error(err)
			}
			if body.CredentialID != "" || body.SubjectKey != "" {
				t.Error("client selected quota subject")
			}
			h.observations = append(h.observations, body.Observation)
			if h.quotaFailure {
				w.WriteHeader(503)
			} else {
				w.WriteHeader(204)
			}
		case strings.HasSuffix(r.URL.Path, "/discovered-repositories"):
			var b controlplane.DiscoveryBatch
			if err := json.NewDecoder(r.Body).Decode(&b); err != nil {
				t.Error(err)
			}
			h.attempts = append(h.attempts, b)
			if h.rejectCandidate && len(b.Repositories) > 0 {
				h.pause = true
				w.WriteHeader(409)
				_, _ = w.Write([]byte(`{"code":"BACKPRESSURE_ACTIVE"}`))
				return
			}
			if b.Checkpoint != h.accepted {
				previous := ""
				if b.PreviousCheckpoint != nil {
					previous = *b.PreviousCheckpoint
				}
				if previous != h.accepted {
					t.Errorf("CAS previous %q != accepted %q", previous, h.accepted)
				}
				h.accepted = b.Checkpoint
			}
			if h.loseCandidateResponse && len(b.Repositories) > 0 {
				h.loseCandidateResponse = false
				w.WriteHeader(503)
				return
			}
			w.WriteHeader(202)
		case strings.HasSuffix(r.URL.Path, "/official-pause"):
			var body struct{ DiscoveryCheckpoint *string }
			_ = json.NewDecoder(r.Body).Decode(&body)
			h.paused = body.DiscoveryCheckpoint
			_, _ = w.Write([]byte(`{"status":"PAUSED"}`))
		case strings.HasSuffix(r.URL.Path, "/complete"):
			var body struct {
				Coverage            string
				DiscoveryCheckpoint string
				RetryAt             time.Time
			}
			_ = json.NewDecoder(r.Body).Decode(&body)
			h.completions = append(h.completions, body)
			if body.DiscoveryCheckpoint != h.accepted {
				t.Error("completion did not retain accepted checkpoint")
			}
			status := "ACQUISITION_COMPLETE"
			if body.Coverage == "INCOMPLETE" {
				status = "RETRY_WAIT"
				var cp controlplane.OfficialCheckpoint
				_ = json.Unmarshal([]byte(h.accepted), &cp)
				if cp.Coverage == "GAP" && h.channel != "OWNER_EXPANSION" {
					status = "PARTIAL"
				}
			}
			_ = json.NewEncoder(w).Encode(map[string]string{"status": status})
		default:
			t.Errorf("unexpected control path %s", r.URL.Path)
			w.WriteHeader(404)
		}
	}))
	t.Cleanup(server.Close)
	var err error
	h.control, err = controlplane.NewClient(server.URL, strings.Repeat("t", 32))
	if err != nil {
		t.Fatal(err)
	}
	return h
}
func officialTask(channel string) *controlplane.ClaimedTask {
	start := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	task := &controlplane.ClaimedTask{TaskID: "official-task", Kind: "DISCOVERY", Lease: controlplane.LeaseProof{Generation: 1, Token: strings.Repeat("a", 64)}, RequiredCapabilities: []string{controlplane.OfficialDiscoveryCapability, controlplane.OfficialCheckpointCapability, controlplane.OfficialPauseCapability}, OfficialDiscovery: &controlplane.OfficialDiscoverySnapshot{FormatVersion: 1, ConfigurationVersion: 1, StrategyVersion: "v1", QueryID: "query", QueryVersion: 1, ShardID: "shard", Channel: channel, Mode: "HISTORICAL", Query: controlplane.OfficialQuery{Kind: "KEYWORD", Value: "skills", Scope: "NAME_DESCRIPTION_TOPICS"}, Range: controlplane.OfficialQueryRange{Since: start, Until: start.Add(8 * time.Second)}, FixedCutoffAt: start.Add(time.Minute), Budget: controlplane.OfficialBudget{Profile: "BALANCED", MaxRequests: 10, MaxPages: 10, CandidateBudget: 100}}}
	if channel == "OWNER_EXPANSION" {
		s := task.OfficialDiscovery
		s.Mode = "OWNER_RECHECK"
		s.OwnerID = 42
		s.OwnerLogin = "old-name"
		s.SeedRepositoryID = 11
		s.ExpansionDepth = 1
		s.Query = controlplane.OfficialQuery{Kind: "OWNER", Value: "old-name", Scope: "OWNER_REPOSITORIES"}
		task.RequiredCapabilities = append(task.RequiredCapabilities, controlplane.OfficialOwnerCapability)
	}
	return task
}
func officialRepo(id, ownerID int64) github.Repository {
	r := github.Repository{ID: id, Name: "skills"}
	r.Owner.Login = "new-name"
	r.Owner.ID = ownerID
	return r
}
func officialRun(t *testing.T, h *officialHarness, task *controlplane.ClaimedTask, f *officialFixture) {
	t.Helper()
	if err := processOfficialDiscovery(context.Background(), h.control, task, DiscoveryLimits{MaxPagesPerLease: 10}, f); err != nil {
		t.Fatal(err)
	}
}
func decodedCP(t *testing.T, s string) controlplane.OfficialCheckpoint {
	t.Helper()
	var cp controlplane.OfficialCheckpoint
	if err := json.Unmarshal([]byte(s), &cp); err != nil {
		t.Fatal(err)
	}
	return cp
}

func TestOfficialSearchPaginationAndReplay(t *testing.T) {
	h := newOfficialHarness(t, "REPOSITORY_METADATA")
	h.loseCandidateResponse = true
	f := &officialFixture{search: func(page int) (github.SearchPage, error) {
		return github.SearchPage{Repositories: []github.Repository{officialRepo(int64(page), 42)}, HasMore: page < 2}, nil
	}}
	officialRun(t, h, officialTask(h.channel), f)
	if f.searches != 2 || len(h.attempts) != 4 || len(h.completions) != 1 {
		t.Fatalf("searches=%d batches=%d completions=%d", f.searches, len(h.attempts), len(h.completions))
	}
	first, _ := json.Marshal(h.attempts[1])
	replay, _ := json.Marshal(h.attempts[2])
	if string(first) != string(replay) {
		t.Fatal("response-loss replay changed payload")
	}
	if cp := decodedCP(t, h.accepted); cp.Coverage != "COMPLETE" || cp.NextPage != 2 {
		t.Fatalf("checkpoint %+v", cp)
	}
}
func TestOfficialRejectedBatchPausesLastAcknowledgedCheckpoint(t *testing.T) {
	h := newOfficialHarness(t, "REPOSITORY_METADATA")
	h.rejectCandidate = true
	f := &officialFixture{search: func(int) (github.SearchPage, error) {
		return github.SearchPage{Repositories: []github.Repository{officialRepo(1, 42)}}, nil
	}}
	officialRun(t, h, officialTask(h.channel), f)
	if h.paused == nil || *h.paused != h.attempts[0].Checkpoint || *h.paused == h.attempts[1].Checkpoint || len(h.completions) != 0 {
		t.Fatal("rejected candidate advanced checkpoint or completed")
	}
}
func TestOfficialSearchCoverageBoundaries(t *testing.T) {
	for _, kind := range []string{"split", "gap", "unsplittable"} {
		t.Run(kind, func(t *testing.T) {
			h := newOfficialHarness(t, "REPOSITORY_METADATA")
			task := officialTask(h.channel)
			want := "GAP"
			if kind == "split" {
				task.OfficialDiscovery.Budget.MaxSplitDepth = 1
				task.OfficialDiscovery.Budget.MaxChildShards = 2
				want = "SPLIT"
			}
			if kind == "unsplittable" {
				task.OfficialDiscovery.Range.Until = task.OfficialDiscovery.Range.Since.Add(time.Second)
			}
			f := &officialFixture{search: func(int) (github.SearchPage, error) {
				return github.SearchPage{Repositories: []github.Repository{officialRepo(1, 42)}, IncompleteResults: true}, nil
			}}
			officialRun(t, h, task, f)
			b := h.attempts[len(h.attempts)-1]
			if b.OfficialBatch.Coverage != want || len(b.Repositories) != 1 {
				t.Fatalf("lost coverage/candidates: %+v", b)
			}
			if want == "SPLIT" {
				ranges := b.OfficialBatch.ChildRanges
				if len(ranges) != 2 || !ranges[0].Since.Equal(task.OfficialDiscovery.Range.Since) || !ranges[0].Until.Equal(ranges[1].Since) || !ranges[1].Until.Equal(task.OfficialDiscovery.Range.Until) {
					t.Fatal("split does not partition parent")
				}
			} else if !h.completions[0].RetryAt.After(time.Now().Add(4 * time.Minute)) {
				t.Fatal("GAP lacks backoff")
			}
			if kind == "unsplittable" && b.OfficialBatch.GapReason != "UNSPLITTABLE" {
				t.Fatal("missing unsplittable reason")
			}
		})
	}
}
func TestOfficialSplitResumeDoesNotSearch(t *testing.T) {
	h := newOfficialHarness(t, "REPOSITORY_METADATA")
	task := officialTask(h.channel)
	cp := controlplane.OfficialCheckpoint{FormatVersion: 1, StrategyVersion: "v1", QueryID: "query", QueryVersion: 1, ShardID: "shard", NextPage: 1, Coverage: "SPLIT", Cursor: `{"offset":0,"revisit":0}`}
	b, _ := json.Marshal(cp)
	task.OfficialDiscovery.Checkpoint = string(b)
	h.accepted = string(b)
	f := &officialFixture{search: func(int) (github.SearchPage, error) {
		t.Fatal("resumed parent was searched")
		return github.SearchPage{}, nil
	}}
	officialRun(t, h, task, f)
	if f.searches != 0 || len(h.completions) != 1 {
		t.Fatal("split resume did not only finish parent")
	}
}
func TestOfficialOwnerIdentityAndDepth(t *testing.T) {
	for _, kind := range []string{"renamed", "wrong-repo-owner", "rename-at-empty-tail", "recursive"} {
		t.Run(kind, func(t *testing.T) {
			h := newOfficialHarness(t, "OWNER_EXPANSION")
			task := officialTask(h.channel)
			f := &officialFixture{owner: func(call int) github.OwnerIdentity {
				login := "new-name"
				if kind == "rename-at-empty-tail" && call == 2 {
					login = "newer-name"
				}
				return github.OwnerIdentity{ID: 42, Login: login, Type: github.UserOwner}
			}, list: func(login string, page int) github.OwnerPage {
				if login != "new-name" {
					t.Error("used stale snapshot login")
				}
				if kind == "rename-at-empty-tail" {
					return github.OwnerPage{}
				}
				id := int64(42)
				if kind == "wrong-repo-owner" {
					id = 99
				}
				return github.OwnerPage{Repositories: []github.Repository{officialRepo(9, id)}}
			}}
			if kind == "recursive" {
				task.OfficialDiscovery.ExpansionDepth = 2
				if err := processOfficialDiscovery(context.Background(), h.control, task, DiscoveryLimits{MaxPagesPerLease: 10}, f); err == nil || f.owners != 0 {
					t.Fatal("recursive expansion made requests")
				}
				return
			}
			officialRun(t, h, task, f)
			if h.attempts[0].OfficialBatch.OwnerLogin != "new-name" {
				t.Fatal("canonical login not persisted before enumeration")
			}
			cp := decodedCP(t, h.accepted)
			if kind == "renamed" {
				if cp.Coverage != "COMPLETE" || f.owners != 2 || h.attempts[len(h.attempts)-1].Repositories[0].OfficialProvenance[0].SeedRepositoryID != 11 {
					t.Fatal("valid owner expansion incomplete")
				}
			} else if cp.Coverage != "IN_PROGRESS" {
				t.Fatal("identity race falsely completed")
			}
		})
	}
}
func TestOfficialOwnerResumeBeyondSearchLimit(t *testing.T) {
	h := newOfficialHarness(t, "OWNER_EXPANSION")
	task := officialTask(h.channel)
	cp := controlplane.OfficialCheckpoint{FormatVersion: 1, StrategyVersion: "v1", QueryID: "query", QueryVersion: 1, ShardID: "shard", NextPage: 12, Coverage: "IN_PROGRESS", Cursor: `{"offset":0,"revisit":0}`}
	b, _ := json.Marshal(cp)
	task.OfficialDiscovery.Checkpoint = string(b)
	h.accepted = string(b)
	f := &officialFixture{owner: func(int) github.OwnerIdentity {
		return github.OwnerIdentity{ID: 42, Login: "new-name", Type: github.UserOwner}
	}, list: func(_ string, page int) github.OwnerPage {
		if page != 12 {
			t.Error("lost resumed page")
		}
		return github.OwnerPage{}
	}}
	officialRun(t, h, task, f)
	if decodedCP(t, h.accepted).Coverage != "COMPLETE" {
		t.Fatal("owner page beyond ten rejected")
	}
}
func TestOfficialQuotaPersistenceFailureStopsLeaseAndOtherTokens(t *testing.T) {
	h := newOfficialHarness(t, "REPOSITORY_METADATA")
	h.quotaFailure = true
	f := &officialFixture{search: func(int) (github.SearchPage, error) { return github.SearchPage{HasMore: true}, nil }}
	err := processOfficialDiscovery(context.Background(), h.control, officialTask(h.channel), DiscoveryLimits{MaxPagesPerLease: 10}, f)
	if err == nil || f.searches != 1 || len(h.observations) != 1 {
		t.Fatalf("did not fail closed: %v searches=%d", err, f.searches)
	}
	if err := (&officialQuotaGuard{resource: "CORE"}).before(context.Background()); err == nil {
		t.Fatal("different resource/token bypassed persistence cooldown")
	}
}
func TestOfficialQuotaResourceAndAnonymousSemantics(t *testing.T) {
	for _, kind := range []string{"primary", "secondary", "anonymous-unknown", "git-failure", "auth"} {
		t.Run(kind, func(t *testing.T) {
			h := newOfficialHarness(t, "REPOSITORY_METADATA")
			g := &officialQuotaGuard{control: h.control, task: officialTask(h.channel), resource: "SEARCH"}
			quota := github.Quota{Resource: "SEARCH", Remaining: 0, ResetAt: time.Now().Add(time.Hour)}
			var err error
			switch kind {
			case "primary":
				err = g.after(context.Background(), quota, nil)
			case "secondary":
				quota.Remaining = 5
				err = g.after(context.Background(), quota, &github.APIError{Code: "GITHUB_RATE_LIMITED", SecondaryRateLimit: true, RetryAt: time.Now().Add(time.Minute)})
			case "anonymous-unknown":
				err = g.after(context.Background(), github.Quota{Remaining: -1}, nil)
			case "git-failure":
				err = g.afterGit(context.Background(), errors.New("git failed"))
			case "auth":
				err = g.after(context.Background(), github.Quota{Remaining: -1}, &github.APIError{Code: "AUTH_INVALID"})
			}
			obs := h.observations[0]
			if kind == "anonymous-unknown" {
				if err != nil || obs.State != "UNKNOWN" || obs.Remaining != nil || obs.ResetAt != nil {
					t.Fatal("unknown manufactured quota")
				}
				return
			}
			if err == nil {
				t.Fatal("limit/error did not stop current lease")
			}
			coreErr := (&officialQuotaGuard{resource: "CORE"}).before(context.Background())
			if (kind == "primary") != (coreErr == nil) {
				t.Fatal("primary resource isolation or secondary global sharing broken")
			}
			if kind == "git-failure" && (obs.Resource != "OTHER" || obs.State != "UNKNOWN" || obs.RetryAfterSeconds == nil) {
				t.Fatal("git invented REST quota")
			}
		})
	}
}

func TestOfficialSearchLimitHarvestsAccessiblePagesBeforeGap(t *testing.T) {
	h := newOfficialHarness(t, "REPOSITORY_METADATA")
	f := &officialFixture{search: func(page int) (github.SearchPage, error) {
		return github.SearchPage{Repositories: []github.Repository{officialRepo(int64(page), 42)}, HasMore: true, LimitReached: true}, nil
	}}
	officialRun(t, h, officialTask(h.channel), f)
	last := h.attempts[len(h.attempts)-1]
	if f.searches != 10 || last.OfficialBatch.Coverage != "GAP" || last.OfficialBatch.GapReason != "RESULT_LIMIT" {
		t.Fatal("accessible search pages discarded or falsely complete")
	}
	total := 0
	for _, b := range h.attempts {
		total += len(b.Repositories)
	}
	if total != 10 {
		t.Fatalf("stored %d candidates", total)
	}
}
func TestOfficialOwnerBudgetResumeAndPause(t *testing.T) {
	h := newOfficialHarness(t, "OWNER_EXPANSION")
	task := officialTask(h.channel)
	task.OfficialDiscovery.Budget.MaxRequests = 3
	task.OfficialDiscovery.Budget.CandidateBudget = 1
	f := &officialFixture{owner: func(int) github.OwnerIdentity {
		return github.OwnerIdentity{ID: 42, Login: "new-name", Type: github.UserOwner}
	}, list: func(_ string, page int) github.OwnerPage {
		return github.OwnerPage{Repositories: []github.Repository{officialRepo(1, 42), officialRepo(2, 42)}}
	}}
	officialRun(t, h, task, f)
	cp := decodedCP(t, h.accepted)
	var cursor officialPageCursor
	_ = json.Unmarshal([]byte(cp.Cursor), &cursor)
	if cp.Coverage != "IN_PROGRESS" || cp.NextPage != 1 || cursor.Offset != 1 || f.owners != 1 {
		t.Fatal("candidate budget lost page offset")
	}
	task.OfficialDiscovery.Checkpoint = h.accepted
	task.Lease.Generation++
	h.pause = true
	officialRun(t, h, task, f)
	if h.paused == nil || *h.paused != task.OfficialDiscovery.Checkpoint || f.lists != 1 {
		t.Fatal("paused resume performed upstream request")
	}
	h.pause = false
	officialRun(t, h, task, f)
	last := h.attempts[len(h.attempts)-1]
	if len(last.Repositories) != 1 || last.Repositories[0].RepositoryID != 2 || last.OfficialBatch.Coverage != "COMPLETE" || last.OfficialBatch.RequestsUsed != 2 {
		t.Fatal("resume duplicated candidate or skipped tail identity cost")
	}
}
func TestOfficialSearchFailurePersistsAttemptWithoutAdvancingPage(t *testing.T) {
	h := newOfficialHarness(t, "REPOSITORY_METADATA")
	f := &officialFixture{search: func(int) (github.SearchPage, error) {
		return github.SearchPage{}, errors.New("controlled upstream error")
	}}
	if err := processOfficialDiscovery(context.Background(), h.control, officialTask(h.channel), DiscoveryLimits{MaxPagesPerLease: 10}, f); err == nil {
		t.Fatal("upstream error swallowed")
	}
	last := h.attempts[len(h.attempts)-1]
	cp := decodedCP(t, h.accepted)
	if len(h.attempts) != 2 || last.OfficialBatch.RequestsUsed != 1 || cp.NextPage != 1 || cp.Coverage != "IN_PROGRESS" || len(h.completions) != 0 {
		t.Fatal("failed search advanced or lost request accounting")
	}
}

func TestOfficialOwnerPageOverflowIsGap(t *testing.T) {
	h := newOfficialHarness(t, "OWNER_EXPANSION")
	task := officialTask(h.channel)
	cp := controlplane.OfficialCheckpoint{FormatVersion: 1, StrategyVersion: "v1", QueryID: "query", QueryVersion: 1, ShardID: "shard", NextPage: 2147483647, Coverage: "IN_PROGRESS", Cursor: `{"offset":0,"revisit":0}`}
	encoded, _ := json.Marshal(cp)
	task.OfficialDiscovery.Checkpoint = string(encoded)
	h.accepted = string(encoded)
	f := &officialFixture{owner: func(int) github.OwnerIdentity {
		return github.OwnerIdentity{ID: 42, Login: "new-name", Type: github.UserOwner}
	}, list: func(_ string, page int) github.OwnerPage {
		if page != 2147483647 {
			t.Fatal("page overflow before request")
		}
		return github.OwnerPage{HasMore: true}
	}}
	officialRun(t, h, task, f)
	last := h.attempts[len(h.attempts)-1]
	if decodedCP(t, h.accepted).NextPage != 2147483647 || last.OfficialBatch.Coverage != "GAP" || last.OfficialBatch.GapReason != "RESULT_LIMIT" || h.completions[0].Coverage != "INCOMPLETE" {
		t.Fatal("owner overflow claimed completion")
	}
}
