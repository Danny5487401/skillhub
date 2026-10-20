package worker

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/iflytek/skillhub/github-sync-go/internal/controlplane"
)

// Run is the same entry point used by cmd/github-sync after loading its normal
// configuration. No test-only capability option is supplied to the pool.
func TestDefaultWorkerPoolAdvertisesOfficialLifecycle(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	observed := make(chan string, 16)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			WorkerID      string   `json:"workerId"`
			Kind          string   `json:"kind"`
			ProtocolMajor int      `json:"protocolMajor"`
			Capabilities  []string `json:"capabilities"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			t.Error(err)
			w.WriteHeader(400)
			return
		}
		want := []string{controlplane.OfficialDiscoveryCapability, controlplane.OfficialCheckpointCapability, controlplane.OfficialPauseCapability, controlplane.OfficialOwnerCapability, controlplane.PlatformPauseCapability}
		if len(body.Capabilities) != len(want) {
			t.Errorf("capabilities=%v", body.Capabilities)
		}
		for _, capability := range want {
			if !slices.Contains(body.Capabilities, capability) {
				t.Errorf("missing %s", capability)
			}
		}
		if !strings.HasPrefix(body.WorkerID, "skillhub-") {
			t.Errorf("missing instance identity: %q", body.WorkerID)
		}
		if strings.HasSuffix(r.URL.Path, "/heartbeat") {
			if body.ProtocolMajor != 1 {
				t.Error("heartbeat omitted protocol major")
			}
			w.WriteHeader(204)
			observed <- "heartbeat"
			return
		}
		if !strings.HasSuffix(r.URL.Path, "/tasks/claim") {
			t.Errorf("unexpected path %s", r.URL.Path)
		}
		w.WriteHeader(204)
		observed <- body.Kind
	}))
	defer server.Close()
	control, err := controlplane.NewClient(server.URL, strings.Repeat("t", 32))
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() {
		done <- Run(ctx, control, PoolConfig{DiscoveryWorkers: 1, RepositoryWorkers: 1, PollInterval: time.Second, Discovery: DiscoveryLimits{MaxPagesPerLease: 10}, Repository: RepositoryLimits{MaxWorkspaceBytes: 1}}, &Counters{})
	}()
	seen := map[string]bool{}
	for len(seen) < 3 {
		select {
		case kind := <-observed:
			seen[kind] = true
		case <-ctx.Done():
			t.Fatal("default pool did not send heartbeat and both claims")
		}
	}
	if !seen["heartbeat"] || !seen["DISCOVERY"] || !seen["REPOSITORY_SYNC"] {
		t.Fatalf("unexpected pool requests %v", seen)
	}
	cancel()
	if err := <-done; !errors.Is(err, context.Canceled) {
		t.Fatalf("pool shutdown: %v", err)
	}
}
