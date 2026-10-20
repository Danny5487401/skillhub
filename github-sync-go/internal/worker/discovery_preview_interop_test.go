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

// Opt-in wire check: Java creates the preview task and persists the result;
// this process exercises the real worker completion/failure path with fixed search pages.
func TestInteropSearchPreview(t *testing.T) {
	phase := os.Getenv("SKILLHUB_INTEROP_PREVIEW_PHASE")
	if phase == "" {
		t.Skip("requires a Java HTTP preview fixture")
	}
	if phase != "success" && phase != "limit" && phase != "live" {
		t.Fatal("invalid preview fixture phase")
	}
	control, err := controlplane.NewClient(os.Getenv("SKILLHUB_INTEROP_JAVA_URL"),
		os.Getenv("SKILLHUB_INTEROP_SERVICE_TOKEN"))
	if err != nil {
		t.Fatal(err)
	}
	task, err := control.ClaimWithCapabilities(context.Background(), "interop-preview-worker", "DISCOVERY", []string{controlplane.PlatformPauseCapability})
	if err != nil {
		t.Fatal(err)
	}
	if task == nil || task.PlatformControlVersion == nil || task.TaskID != os.Getenv("SKILLHUB_INTEROP_TASK_ID") ||
		task.Discovery == nil || task.Discovery.EntryType != "SEARCH_PREVIEW" ||
		task.Discovery.Criteria == nil || len(task.Discovery.Criteria.Selectors) != 2 {
		t.Fatal("Java did not lease the expected multi-selector preview")
	}
	if expected := os.Getenv("SKILLHUB_INTEROP_EXPECT_CREDENTIAL_ID"); expected != "" {
		if task.Credential == nil || task.Credential.CredentialID != expected ||
			task.Credential.Token != os.Getenv("SKILLHUB_INTEROP_EXPECT_CREDENTIAL_TOKEN") {
			t.Fatal("Java did not lease the expected fixture credential")
		}
	} else if os.Getenv("SKILLHUB_INTEROP_EXPECT_ANONYMOUS") == "1" && task.Credential != nil {
		t.Fatal("Java should have leased an anonymous public request")
	}
	if phase == "live" {
		if err := executeLeasedTask(context.Background(), control, task, func(ctx context.Context) error {
			return processSearchPreview(ctx, control, task)
		}); err != nil {
			t.Fatal(err)
		}
		return
	}
	var calls []string
	search := func(_ context.Context, kind, value string, since *time.Time,
		until time.Time, page int) (github.SearchPage, github.Quota, error) {
		if since != nil || until.IsZero() || page != 1 {
			t.Fatal("preview did not use the first page of its fixed window")
		}
		calls = append(calls, kind+":"+value)
		if kind == "TOPIC" && phase == "limit" {
			return github.SearchPage{}, github.Quota{}, &github.APIError{
				Status: 429, Code: "GITHUB_RATE_LIMITED", RetryAt: time.Now().Add(10 * time.Minute),
			}
		}
		if kind == "KEYWORD" {
			repositories := make([]github.Repository, 6)
			for index := range repositories {
				repositories[index].ID = int64(900001 + index)
				repositories[index].Name = "public-skill"
				repositories[index].Owner.Login = "fixture-owner"
			}
			return github.SearchPage{Repositories: repositories, TotalCount: 1001,
				IncompleteResults: true, LimitReached: true}, github.Quota{}, nil
		}
		repository := github.Repository{ID: 900101, Name: "topic-skill"}
		repository.Owner.Login = "fixture-owner"
		return github.SearchPage{Repositories: []github.Repository{repository},
			TotalCount: 4}, github.Quota{}, nil
	}
	// The worker's lease wrapper reports upstream errors to Java, including retryAt.
	err = executeLeasedTask(context.Background(), control, task, func(ctx context.Context) error {
		return processSearchPreviewWithSearch(ctx, control, task, search)
	})
	if phase == "success" && err != nil || phase == "limit" && err == nil {
		t.Fatalf("unexpected worker outcome: phase=%s err=%v", phase, err)
	}
	if phase == "limit" {
		var upstream *github.APIError
		if !errors.As(err, &upstream) || upstream.Code != "GITHUB_RATE_LIMITED" {
			t.Fatalf("rate limit was not preserved: %v", err)
		}
	}
	if len(calls) != 2 || calls[0] != "KEYWORD:agent skills" ||
		calls[1] != "TOPIC:agent-skills" {
		t.Fatalf("unexpected preview search order: %v", calls)
	}
}
