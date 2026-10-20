package worker

import (
	"context"
	"errors"
	"os"
	"testing"

	"github.com/iflytek/skillhub/github-sync-go/internal/controlplane"
	"github.com/iflytek/skillhub/github-sync-go/internal/github"
)

type curatedOwnerFixture struct {
	curatedRepositoryFixture
	owner     string
	ownerType github.OwnerType
}

func (fixture curatedOwnerFixture) GetOwnerType(_ context.Context, owner string) (github.OwnerType, github.Quota, error) {
	if owner != fixture.owner {
		return "", github.Quota{}, errors.New("unexpected curated owner")
	}
	return fixture.ownerType, github.Quota{}, nil
}

func (fixture curatedOwnerFixture) ListPublicOwnerRepositories(_ context.Context, owner string,
	ownerType github.OwnerType, page int) (github.OwnerPage, github.Quota, error) {
	if owner != fixture.owner || ownerType != fixture.ownerType || page != 1 {
		return github.OwnerPage{}, github.Quota{}, errors.New("unexpected public owner repository page")
	}
	repository := github.Repository{ID: 987654324, Name: "public-skills", DefaultBranch: "main"}
	if fixture.ownerType == github.OrganizationOwner {
		repository.ID++
	}
	repository.Owner.Login = owner
	return github.OwnerPage{Repositories: []github.Repository{repository}}, github.Quota{}, nil
}

func TestInteropCuratedOwnerDiscovery(t *testing.T) {
	ownerType := github.OwnerType(os.Getenv("SKILLHUB_INTEROP_OWNER_TYPE"))
	if ownerType == "" {
		t.Skip("requires a Java HTTP curated owner fixture")
	}
	if ownerType != github.UserOwner && ownerType != github.OrganizationOwner {
		t.Fatal("invalid curated owner fixture type")
	}
	owner := os.Getenv("SKILLHUB_INTEROP_OWNER_LOGIN")
	control, err := controlplane.NewClient(os.Getenv("SKILLHUB_INTEROP_JAVA_URL"),
		os.Getenv("SKILLHUB_INTEROP_SERVICE_TOKEN"))
	if err != nil {
		t.Fatal(err)
	}
	task, err := control.ClaimWithCapabilities(context.Background(), "interop-owner-worker", "DISCOVERY", []string{controlplane.PlatformPauseCapability})
	if err != nil {
		t.Fatal(err)
	}
	if task == nil || task.PlatformControlVersion == nil || task.TaskID != os.Getenv("SKILLHUB_INTEROP_TASK_ID") ||
		task.Discovery == nil || task.Discovery.EntryType != "CURATED_OWNER" ||
		task.Discovery.Owner != owner || task.Credential != nil {
		t.Fatal("Java did not lease the expected anonymous curated owner task")
	}
	if err := executeLeasedTask(context.Background(), control, task, func(ctx context.Context) error {
		return processDiscoveryWithGitHub(ctx, control, task,
			DiscoveryLimits{MaxPagesPerLease: 2}, curatedOwnerFixture{owner: owner, ownerType: ownerType})
	}); err != nil {
		t.Fatal(err)
	}
}
