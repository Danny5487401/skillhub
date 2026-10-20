package worker

import (
	"context"
	"os"
	"testing"

	"github.com/iflytek/skillhub/github-sync-go/internal/controlplane"
)

func TestInteropWorkerHeartbeat(t *testing.T) {
	if os.Getenv("SKILLHUB_INTEROP_HEARTBEAT") != "1" {
		t.Skip("requires a Java HTTP worker heartbeat fixture")
	}
	control, err := controlplane.NewClient(os.Getenv("SKILLHUB_INTEROP_JAVA_URL"),
		os.Getenv("SKILLHUB_INTEROP_SERVICE_TOKEN"))
	if err != nil {
		t.Fatal(err)
	}
	if err := control.Heartbeat(context.Background()); err != nil {
		t.Fatal(err)
	}
}
