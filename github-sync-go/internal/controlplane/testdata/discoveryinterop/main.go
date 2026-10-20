package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"time"

	"github.com/iflytek/skillhub/github-sync-go/internal/controlplane"
)

const checkpoint = "interop-discovery-checkpoint"

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func run() error {
	phase := os.Getenv("SKILLHUB_INTEROP_DISCOVERY_PHASE")
	if phase != "report" && phase != "resume" {
		return errors.New("invalid discovery interoperability phase")
	}
	client, err := controlplane.NewClient(os.Getenv("SKILLHUB_INTEROP_JAVA_URL"),
		os.Getenv("SKILLHUB_INTEROP_SERVICE_TOKEN"))
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	task, err := client.ClaimWithCapabilities(ctx, "interop-discovery-worker", "DISCOVERY", []string{controlplane.PlatformPauseCapability})
	if err != nil {
		return err
	}
	if task == nil || task.TaskID != os.Getenv("SKILLHUB_INTEROP_TASK_ID") ||
		task.Discovery == nil || task.Discovery.EntryType != "CURATED_OWNER" ||
		task.Discovery.Owner != "interop-owner" {
		return errors.New("Java did not lease the expected discovery task")
	}
	if expected := os.Getenv("SKILLHUB_INTEROP_EXPECT_CREDENTIAL_ID"); expected != "" &&
		(task.Credential == nil || task.Credential.CredentialID != expected ||
			task.Credential.Token != os.Getenv("SKILLHUB_INTEROP_EXPECT_CREDENTIAL_TOKEN")) {
		return errors.New("Java did not lease the expected fixture credential")
	}
	if phase == "report" && (task.Lease.Generation != 1 || task.Discovery.Checkpoint != "") ||
		phase == "resume" && (task.Lease.Generation != 2 || task.Discovery.Checkpoint != checkpoint) {
		return errors.New("Java did not preserve the expected discovery lease and checkpoint")
	}
	batch := controlplane.DiscoveryBatch{
		Checkpoint: checkpoint,
		Repositories: []controlplane.RepositoryCandidate{{
			RepositoryID: 424242, Owner: "interop-owner", Name: "public-skills",
			DefaultBranch: "main",
		}},
	}
	if err := client.ReportDiscoveryBatch(ctx, task.TaskID, task.Lease, batch); err != nil {
		return err
	}
	if phase == "report" {
		fmt.Println("REPORTED")
		return nil
	}
	status, err := client.CompleteDiscovery(ctx, task.TaskID, task.Lease,
		checkpoint, time.Time{}, "")
	if err != nil || status != "ACQUISITION_COMPLETE" {
		return errors.New("reclaimed discovery lease did not complete the saved checkpoint")
	}
	fmt.Println("COMPLETED")
	return nil
}
