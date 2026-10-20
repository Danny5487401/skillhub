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

type curatedRepositoryFixture struct{}

func (curatedRepositoryFixture) SetQuotaObserver(func(context.Context, github.Quota)) {}

func (curatedRepositoryFixture) GetRepository(_ context.Context, owner, name string) (github.Repository, github.Quota, error) {
	if owner != "openai" || name != "skills" {
		return github.Repository{}, github.Quota{}, errors.New("unexpected curated repository coordinate")
	}
	repository := github.Repository{ID: 987654321, Name: name, DefaultBranch: "main"}
	repository.Owner.Login = owner
	return repository, github.Quota{}, nil
}

func (curatedRepositoryFixture) GetOwnerType(context.Context, string) (github.OwnerType, github.Quota, error) {
	return "", github.Quota{}, errors.New("unexpected owner lookup")
}

func (curatedRepositoryFixture) ListPublicOwnerRepositories(context.Context, string, github.OwnerType, int) (github.OwnerPage, github.Quota, error) {
	return github.OwnerPage{}, github.Quota{}, errors.New("unexpected owner repository listing")
}

func (curatedRepositoryFixture) SearchRepositories(context.Context, string, string, *time.Time, time.Time, int) (github.SearchPage, github.Quota, error) {
	return github.SearchPage{}, github.Quota{}, errors.New("unexpected repository search")
}

// Opt-in cross-process check of Java's curated source binding contract.
// The optional live mode also probes GitHub but depends on its public quota.
func TestInteropCuratedPublicRepository(t *testing.T) {
	if os.Getenv("SKILLHUB_INTEROP_CURATED_REPOSITORY") != "1" {
		t.Skip("requires a Java HTTP curated repository fixture")
	}
	control, err := controlplane.NewClient(os.Getenv("SKILLHUB_INTEROP_JAVA_URL"),
		os.Getenv("SKILLHUB_INTEROP_SERVICE_TOKEN"))
	if err != nil {
		t.Fatal(err)
	}
	task, err := control.ClaimWithCapabilities(context.Background(), "interop-curated-worker", "DISCOVERY", []string{controlplane.PlatformPauseCapability})
	if err != nil {
		t.Fatal(err)
	}
	if task == nil || task.TaskID != os.Getenv("SKILLHUB_INTEROP_TASK_ID") {
		t.Fatal("Java did not lease the queued curated repository task")
	}
	if task.Discovery == nil || task.Discovery.EntryType != "CURATED_REPOSITORY" ||
		task.Discovery.Owner != "openai" || task.Discovery.RepositoryName != "skills" {
		t.Fatalf("Java returned unexpected curated repository coordinates: %+v", task.Discovery)
	}
	if task.Credential != nil {
		t.Fatal("Java should have leased an anonymous public repository task")
	}
	if err := executeLeasedTask(context.Background(), control, task, func(ctx context.Context) error {
		limits := DiscoveryLimits{MaxPagesPerLease: 2}
		if os.Getenv("SKILLHUB_INTEROP_CURATED_MODE") == "live" {
			return ProcessDiscovery(ctx, control, task, limits)
		}
		return processDiscoveryWithGitHub(ctx, control, task, limits, curatedRepositoryFixture{})
	}); err != nil {
		t.Fatal(err)
	}
}
