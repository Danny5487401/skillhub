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

type shrinkingSearchFixture struct{ curatedRepositoryFixture }

func (shrinkingSearchFixture) SearchRepositories(_ context.Context, _, _ string,
	_ *time.Time, _ time.Time, page int) (github.SearchPage, github.Quota, error) {
	if page != 1 {
		return github.SearchPage{}, github.Quota{}, errors.New("unexpected search page")
	}
	repositories := []github.Repository{{ID: 11, Name: "first"}, {ID: 12, Name: "second"}}
	for i := range repositories {
		repositories[i].Owner.Login = "public-owner"
	}
	return github.SearchPage{Repositories: repositories}, github.Quota{}, nil
}

func TestProcessDiscoveryRevisitsShrinkingPage(t *testing.T) {
	previous := `{"v":1,"entry":"SEARCH_RULE","page":1,"offset":3}`
	var reported controlplane.DiscoveryBatch
	var completed bool
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		switch {
		case strings.HasSuffix(request.URL.Path, "/discovered-repositories"):
			if err := json.NewDecoder(request.Body).Decode(&reported); err != nil ||
				reported.PreviousCheckpoint == nil || *reported.PreviousCheckpoint != previous {
				writer.WriteHeader(http.StatusBadRequest)
				return
			}
			writer.WriteHeader(http.StatusAccepted)
		case strings.HasSuffix(request.URL.Path, "/complete"):
			var body struct {
				DiscoveryCheckpoint string `json:"discoveryCheckpoint"`
			}
			if err := json.NewDecoder(request.Body).Decode(&body); err != nil ||
				body.DiscoveryCheckpoint != reported.Checkpoint {
				writer.WriteHeader(http.StatusBadRequest)
				return
			}
			completed = true
			writer.Header().Set("Content-Type", "application/json")
			_, _ = writer.Write([]byte(`{"status":"ACQUISITION_COMPLETE"}`))
		default:
			writer.WriteHeader(http.StatusNotFound)
		}
	}))
	defer server.Close()
	control, err := controlplane.NewClient(server.URL, strings.Repeat("t", 32))
	if err != nil {
		t.Fatal(err)
	}
	task := &controlplane.ClaimedTask{
		TaskID: "shrinking-page", Kind: "DISCOVERY",
		Lease: controlplane.LeaseProof{Generation: 2, Token: strings.Repeat("a", 64)},
		Discovery: &controlplane.DiscoverySnapshot{
			EntryType: "SEARCH_RULE", Checkpoint: previous, CandidateBudget: 3,
			Criteria: &controlplane.SearchCriteria{Selectors: []controlplane.SearchSelector{
				{Kind: "KEYWORD", Value: "agent skills", Priority: "CORE"},
			}},
			SearchWindow: &controlplane.SearchWindow{
				Mode: "FULL_RECHECK", Until: time.Now(),
			},
		},
	}
	if err := processDiscoveryWithGitHub(context.Background(), control, task,
		DiscoveryLimits{MaxPagesPerLease: 2}, shrinkingSearchFixture{}); err != nil {
		t.Fatal(err)
	}
	var next discoveryCursor
	if err := json.Unmarshal([]byte(reported.Checkpoint), &next); err != nil {
		t.Fatal(err)
	}
	if !completed || len(reported.Repositories) != 2 ||
		reported.Repositories[0].RepositoryID != 11 || reported.Repositories[1].RepositoryID != 12 ||
		next.Revisit != 1 || next.Selector != 1 {
		t.Fatalf("shrinking page was not revisited: completed=%v batch=%+v cursor=%+v",
			completed, reported.Repositories, next)
	}
}

func TestPreviewSearchResultsUsesOnePagePerSelectorAndPreservesWarnings(t *testing.T) {
	until := time.Date(2026, 9, 29, 0, 0, 0, 0, time.UTC)
	snapshot := &controlplane.DiscoverySnapshot{
		Criteria: &controlplane.SearchCriteria{Selectors: []controlplane.SearchSelector{
			{Kind: "TOPIC", Value: "agent-skills", Priority: "EXTENSION"},
			{Kind: "KEYWORD", Value: "agent skills", Priority: "CORE"},
		}},
		SearchWindow: &controlplane.SearchWindow{Mode: "FULL_RECHECK", Until: until},
	}
	var calls []string
	search := func(_ context.Context, kind, value string, since *time.Time,
		end time.Time, page int) (github.SearchPage, github.Quota, error) {
		if since != nil || !end.Equal(until) || page != 1 {
			t.Fatalf("unexpected preview search window or page: since=%v end=%s page=%d",
				since, end, page)
		}
		calls = append(calls, kind+":"+value)
		repositories := make([]github.Repository, 6)
		for i := range repositories {
			repositories[i].ID = int64(i + 1)
			repositories[i].Name = "skill"
			repositories[i].Owner.Login = "public-owner"
		}
		return github.SearchPage{
			Repositories: repositories, TotalCount: 1001,
			IncompleteResults: true, LimitReached: true,
		}, github.Quota{}, nil
	}

	results, err := previewSearchResults(context.Background(), snapshot, search)
	if err != nil {
		t.Fatal(err)
	}
	if len(calls) != 2 || calls[0] != "KEYWORD:agent skills" ||
		calls[1] != "TOPIC:agent-skills" || len(results) != 2 {
		t.Fatalf("unexpected selectors: calls=%v results=%+v", calls, results)
	}
	for _, result := range results {
		if result.TotalCount != 1001 || !result.IncompleteResults ||
			!result.LimitReached || len(result.Samples) != 5 {
			t.Fatalf("preview lost estimates or warnings: %+v", result)
		}
	}
}

func TestPreviewSearchResultsDoesNotReturnPartialResultsAfterFailure(t *testing.T) {
	snapshot := &controlplane.DiscoverySnapshot{
		Criteria: &controlplane.SearchCriteria{Selectors: []controlplane.SearchSelector{
			{Kind: "KEYWORD", Value: "agent skills", Priority: "CORE"},
			{Kind: "TOPIC", Value: "agent-skills", Priority: "EXTENSION"},
		}},
		SearchWindow: &controlplane.SearchWindow{Until: time.Now()},
	}
	calls := 0
	results, err := previewSearchResults(context.Background(), snapshot,
		func(context.Context, string, string, *time.Time, time.Time, int) (
			github.SearchPage, github.Quota, error) {
			calls++
			if calls == 2 {
				return github.SearchPage{}, github.Quota{}, errors.New("rate limited")
			}
			return github.SearchPage{TotalCount: 1}, github.Quota{}, nil
		})
	if err == nil || len(results) != 0 || calls != 2 {
		t.Fatalf("preview should fail atomically: results=%+v err=%v calls=%d",
			results, err, calls)
	}
}

func TestSelectDiscoveryBatchCountsUniqueRepositoriesAndKeepsRawOffset(t *testing.T) {
	candidates := []controlplane.RepositoryCandidate{
		{RepositoryID: 10}, {RepositoryID: 10}, {RepositoryID: 20},
		{RepositoryID: 20}, {RepositoryID: 30},
	}
	batch, offset := selectDiscoveryBatch(candidates, 0, 2, map[int64]struct{}{})
	if offset != 3 || len(batch) != 2 || batch[0].RepositoryID != 10 || batch[1].RepositoryID != 20 {
		t.Fatalf("first batch = %+v, offset = %d", batch, offset)
	}
	batch, offset = selectDiscoveryBatch(candidates, offset, 1,
		map[int64]struct{}{10: {}, 20: {}})
	if offset != len(candidates) || len(batch) != 1 || batch[0].RepositoryID != 30 {
		t.Fatalf("second batch = %+v, offset = %d", batch, offset)
	}
}

func TestSearchBoundsPartitionsFixedWindowWithoutOverlap(t *testing.T) {
	since := time.Date(2026, 9, 1, 0, 0, 0, 0, time.UTC)
	window := &controlplane.SearchWindow{
		Mode: "INCREMENTAL", Since: since, Until: since.Add(7 * time.Second),
	}
	leftFrom, leftUntil, err := searchBounds(window, "L")
	if err != nil {
		t.Fatal(err)
	}
	rightFrom, rightUntil, err := searchBounds(window, "R")
	if err != nil {
		t.Fatal(err)
	}
	if !leftFrom.Equal(since) || !leftUntil.Add(time.Second).Equal(rightFrom) ||
		!rightUntil.Equal(window.Until) || nextSearchCursor(discoveryCursor{Path: "L"}).Path != "R" {
		t.Fatalf("split windows are not contiguous: [%s, %s], [%s, %s]",
			leftFrom, leftUntil, rightFrom, rightUntil)
	}
}

func TestSelectDiscoveryBatchCanAdvancePastOnlyDuplicates(t *testing.T) {
	candidates := []controlplane.RepositoryCandidate{{RepositoryID: 10}, {RepositoryID: 10}}
	batch, offset := selectDiscoveryBatch(candidates, 0, 1, map[int64]struct{}{10: {}})
	if len(batch) != 0 || offset != len(candidates) {
		t.Fatalf("batch = %+v, offset = %d", batch, offset)
	}
}
