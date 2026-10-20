package worker

import (
	"context"
	"errors"
	"os"
	"testing"
	"time"

	"github.com/iflytek/skillhub/github-sync-go/internal/controlplane"
	"github.com/iflytek/skillhub/github-sync-go/internal/github"
)

type changingSearchPageFixture struct {
	curatedRepositoryFixture
	phase string
}

func (fixture changingSearchPageFixture) SearchRepositories(_ context.Context, kind, _ string,
	_ *time.Time, _ time.Time, page int) (github.SearchPage, github.Quota, error) {
	if page != 1 {
		return github.SearchPage{}, github.Quota{}, errors.New("unexpected search page")
	}
	if kind == "TOPIC" && fixture.phase == "shrunk" {
		return github.SearchPage{}, github.Quota{}, nil
	}
	if kind != "KEYWORD" {
		return github.SearchPage{}, github.Quota{}, errors.New("unexpected search selector")
	}
	ids := []int64{9400001, 9400002, 9400003, 9400004, 9400005}
	if fixture.phase == "shrunk" {
		ids = []int64{9400002, 9400006}
	}
	repositories := make([]github.Repository, 0, len(ids))
	for _, id := range ids {
		repository := github.Repository{ID: id, Name: "public-skills", DefaultBranch: "main"}
		repository.Owner.Login = "fixture-owner"
		repositories = append(repositories, repository)
	}
	return github.SearchPage{Repositories: repositories}, github.Quota{}, nil
}

// Java starts the same discovery task in two Go processes. The second lease
// reads a shorter GitHub page than the first and must not lose the new ID.
func TestInteropChangingSearchPage(t *testing.T) {
	phase := os.Getenv("SKILLHUB_INTEROP_SEARCH_PAGE_PHASE")
	if phase == "" {
		t.Skip("requires a Java HTTP changing search page fixture")
	}
	if phase != "initial" && phase != "shrunk" {
		t.Fatal("invalid search page fixture phase")
	}
	control, err := controlplane.NewClient(os.Getenv("SKILLHUB_INTEROP_JAVA_URL"),
		os.Getenv("SKILLHUB_INTEROP_SERVICE_TOKEN"))
	if err != nil {
		t.Fatal(err)
	}
	task, err := control.ClaimWithCapabilities(context.Background(), "interop-changing-page-worker", "DISCOVERY", []string{controlplane.PlatformPauseCapability})
	if err != nil {
		t.Fatal(err)
	}
	if task == nil || task.PlatformControlVersion == nil || task.TaskID != os.Getenv("SKILLHUB_INTEROP_TASK_ID") ||
		task.Discovery == nil || task.Discovery.EntryType != "SEARCH_RULE" ||
		task.Discovery.CandidateBudget != 3 {
		t.Fatal("Java did not lease the expected bounded search task")
	}
	if err := executeLeasedTask(context.Background(), control, task, func(ctx context.Context) error {
		return processDiscoveryWithGitHub(ctx, control, task,
			DiscoveryLimits{MaxPagesPerLease: 5}, changingSearchPageFixture{phase: phase})
	}); err != nil {
		t.Fatal(err)
	}
}
