package worker

import (
	"context"
	"encoding/json"
	"os"
	"testing"

	"github.com/iflytek/skillhub/github-sync-go/internal/controlplane"
	"github.com/iflytek/skillhub/github-sync-go/internal/github"
)

// This test is compiled and launched as a separate OS process by the Java
// PostgreSQL interop test. Only GitHub is a fixture; control-plane HTTP is real.
func TestInteropOfficialDiscoveryProcess(t *testing.T) {
	phase := os.Getenv("SKILLHUB_INTEROP_OFFICIAL_PROCESS")
	if phase == "" {
		t.Skip("requires Java official discovery process fixture")
	}
	if phase != "before-kill" && phase != "resume" {
		t.Fatal("invalid interop phase")
	}
	control, err := controlplane.NewClient(os.Getenv("SKILLHUB_INTEROP_JAVA_URL"), os.Getenv("SKILLHUB_INTEROP_SERVICE_TOKEN"))
	if err != nil {
		t.Fatal(err)
	}
	if err := control.HeartbeatWithCapabilities(context.Background(), "interop-official-"+phase, supportedCapabilities()); err != nil {
		t.Fatal(err)
	}
	task, err := control.ClaimWithCapabilities(context.Background(), "interop-official-"+phase, "DISCOVERY", supportedCapabilities())
	if err != nil {
		t.Fatal(err)
	}
	if task == nil || task.TaskID != os.Getenv("SKILLHUB_INTEROP_TASK_ID") || task.OfficialDiscovery == nil || task.Credential != nil {
		t.Fatal("Java did not return expected anonymous official task")
	}
	if phase == "resume" {
		var cp controlplane.OfficialCheckpoint
		if json.Unmarshal([]byte(task.OfficialDiscovery.Checkpoint), &cp) != nil || cp.NextPage != 2 || cp.Coverage != "IN_PROGRESS" || task.Lease.Generation < 2 {
			t.Fatal("claim lost persisted checkpoint or generation")
		}
	} else if task.OfficialDiscovery.Checkpoint != "" {
		t.Fatal("first process received unexpected prior progress")
	}
	fixture := &officialFixture{search: func(page int) (github.SearchPage, error) {
		if phase == "before-kill" && page == 2 {
			if err := os.WriteFile(os.Getenv("SKILLHUB_INTEROP_BARRIER"), []byte("PAGE_TWO_AFTER_ACK"), 0600); err != nil {
				t.Fatal(err)
			}
			select {} // Java kills this exact executable, including its renewal goroutine.
		}
		if (phase == "before-kill" && page != 1) || (phase == "resume" && page != 2) {
			t.Fatalf("unexpected %s search page %d", phase, page)
		}
		repository := officialRepo(987654900+int64(page), 42)
		repository.DefaultBranch = "main"
		return github.SearchPage{Repositories: []github.Repository{repository}, HasMore: page == 1}, nil
	}}
	err = executeLeasedTask(context.Background(), control, task, func(ctx context.Context) error {
		return processOfficialDiscovery(ctx, control, task, DiscoveryLimits{MaxPagesPerLease: 10}, fixture)
	})
	if err != nil {
		t.Fatal(err)
	}
	if phase != "resume" || fixture.searches != 1 {
		t.Fatal("process did not resume exclusively from second page")
	}
}
