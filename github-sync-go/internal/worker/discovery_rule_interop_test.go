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

type searchRuleFixture struct {
	curatedRepositoryFixture
	calls         []string
	windows       []searchRuleWindow
	saturate      bool
	reportedLimit bool
}

type searchRuleWindow struct {
	from  time.Time
	until time.Time
}

func (fixture *searchRuleFixture) SearchRepositories(_ context.Context, kind, value string,
	since *time.Time, until time.Time, page int) (github.SearchPage, github.Quota, error) {
	if since == nil || until.IsZero() || page != 1 {
		return github.SearchPage{}, github.Quota{}, errors.New("search did not use its fixed first page")
	}
	fixture.calls = append(fixture.calls, kind+":"+value)
	fixture.windows = append(fixture.windows, searchRuleWindow{from: *since, until: until})
	if fixture.saturate && kind == "KEYWORD" && !fixture.reportedLimit {
		fixture.reportedLimit = true
		return github.SearchPage{TotalCount: 1001, LimitReached: true,
			IncompleteResults: true}, github.Quota{}, nil
	}
	first := github.Repository{ID: 987654322, Name: "skill-one", DefaultBranch: "main"}
	first.Owner.Login = "fixture-owner"
	if kind == "KEYWORD" && value == "agent skills" {
		return github.SearchPage{Repositories: []github.Repository{first}, TotalCount: 1}, github.Quota{}, nil
	}
	if kind == "TOPIC" && value == "agent-skills" {
		second := github.Repository{ID: 987654323, Name: "skill-two", DefaultBranch: "main"}
		second.Owner.Login = "fixture-owner"
		return github.SearchPage{Repositories: []github.Repository{first, second}, TotalCount: 2}, github.Quota{}, nil
	}
	return github.SearchPage{}, github.Quota{}, errors.New("unexpected search selector")
}

// Java supplies a versioned rule and fixed window; Go reports deduplicated leads.
func TestInteropSearchRuleDiscovery(t *testing.T) {
	if os.Getenv("SKILLHUB_INTEROP_SEARCH_RULE") != "1" {
		t.Skip("requires a Java HTTP search rule fixture")
	}
	control, err := controlplane.NewClient(os.Getenv("SKILLHUB_INTEROP_JAVA_URL"),
		os.Getenv("SKILLHUB_INTEROP_SERVICE_TOKEN"))
	if err != nil {
		t.Fatal(err)
	}
	task, err := control.ClaimWithCapabilities(context.Background(), "interop-search-rule-worker", "DISCOVERY", []string{controlplane.PlatformPauseCapability})
	if err != nil {
		t.Fatal(err)
	}
	if task == nil || task.PlatformControlVersion == nil || task.TaskID != os.Getenv("SKILLHUB_INTEROP_TASK_ID") ||
		task.Discovery == nil || task.Discovery.EntryType != "SEARCH_RULE" ||
		task.Discovery.RuleVersion != 2 || task.Discovery.SearchWindow == nil ||
		task.Discovery.SearchWindow.Mode != "FULL_RECHECK" || task.Credential != nil {
		t.Fatal("Java did not lease the expected anonymous versioned search rule")
	}
	fixture := &searchRuleFixture{saturate: os.Getenv("SKILLHUB_INTEROP_SEARCH_SATURATED") == "1"}
	if err := executeLeasedTask(context.Background(), control, task, func(ctx context.Context) error {
		return processDiscoveryWithGitHub(ctx, control, task,
			DiscoveryLimits{MaxPagesPerLease: 5}, fixture)
	}); err != nil {
		t.Fatal(err)
	}
	if len(fixture.windows) == 0 || !fixture.windows[0].from.Equal(time.Unix(0, 0)) ||
		fixture.windows[0].until.Unix() != task.Discovery.SearchWindow.Until.Unix() {
		t.Fatal("discovery changed the Java-provided full search window")
	}
	if fixture.saturate {
		if len(fixture.calls) != 4 || fixture.calls[0] != "KEYWORD:agent skills" ||
			fixture.calls[1] != "KEYWORD:agent skills" ||
			fixture.calls[2] != "KEYWORD:agent skills" ||
			fixture.calls[3] != "TOPIC:agent-skills" ||
			!fixture.windows[1].from.Equal(fixture.windows[0].from) ||
			!fixture.windows[2].from.Equal(fixture.windows[1].until.Add(time.Second)) ||
			!fixture.windows[2].until.Equal(fixture.windows[0].until) ||
			!fixture.windows[3].from.Equal(fixture.windows[0].from) ||
			!fixture.windows[3].until.Equal(fixture.windows[0].until) {
			t.Fatalf("incomplete search did not split and cover the fixed window: %v", fixture.calls)
		}
	} else if len(fixture.calls) != 2 || fixture.calls[0] != "KEYWORD:agent skills" ||
		fixture.calls[1] != "TOPIC:agent-skills" {
		t.Fatalf("discovery did not execute core before extension: %v", fixture.calls)
	}
}
