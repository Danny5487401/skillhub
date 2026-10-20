package worker

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/iflytek/skillhub/github-sync-go/internal/controlplane"
	"github.com/iflytek/skillhub/github-sync-go/internal/github"
)

func TestPlatformPauseAcknowledgesOnlyAfterWorkCleanup(t *testing.T) {
	for _, kind := range []string{"DISCOVERY", "REPOSITORY_SYNC"} {
		t.Run(kind, func(t *testing.T) {
			var paused, cleaned, acked atomic.Bool
			var received controlplane.PlatformPause
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Content-Type", "application/json")
				if strings.HasSuffix(r.URL.Path, "/platform-control") {
					action := "CONTINUE"
					if paused.Load() {
						action = "PAUSE"
					}
					_ = json.NewEncoder(w).Encode(controlplane.PlatformControl{Action: action, PlatformVersion: 2})
					return
				}
				if strings.HasSuffix(r.URL.Path, "/platform-pause") {
					if !cleaned.Load() {
						t.Error("ack before acquisition cleanup")
					}
					_ = json.NewDecoder(r.Body).Decode(&received)
					acked.Store(true)
					_, _ = w.Write([]byte(`{"status":"PAUSED"}`))
					return
				}
				t.Errorf("unexpected request %s", r.URL.Path)
				w.WriteHeader(500)
			}))
			defer server.Close()
			client, _ := controlplane.NewClient(server.URL, strings.Repeat("t", 32))
			version := int64(1)
			task := &controlplane.ClaimedTask{TaskID: "fixture", Kind: kind, PlatformControlVersion: &version, Discovery: &controlplane.DiscoverySnapshot{Checkpoint: "confirmed"}}
			err := runPlatformTask(context.Background(), client, task, func(ctx context.Context) error {
				defer cleaned.Store(true)
				if kind == "REPOSITORY_SYNC" {
					ctx.Value(platformContextKey{}).(*platformExecution).completion = &controlplane.RepositoryCompletion{CommitSHA: strings.Repeat("a", 40), PathCheckpoint: "received"}
				}
				paused.Store(true)
				return github.CheckAcquisitionControl(ctx)
			})
			if err != nil || !acked.Load() {
				t.Fatalf("pause=%v ack=%t", err, acked.Load())
			}
			if received.ObservedVersion != 2 || received.Kind != kind {
				t.Fatalf("wrong request %+v", received)
			}
			if kind == "DISCOVERY" && (received.DiscoveryCheckpoint == nil || *received.DiscoveryCheckpoint != "confirmed") {
				t.Fatal("lost checkpoint")
			}
			if kind == "REPOSITORY_SYNC" && (received.RepositoryProgress == nil || *received.RepositoryProgress.PathCheckpoint != "received") {
				t.Fatal("lost repository progress")
			}
		})
	}
}

func TestPlatformControlDoesNotPollTeam(t *testing.T) {
	called := false
	err := runPlatformTask(context.Background(), nil, &controlplane.ClaimedTask{}, func(context.Context) error { called = true; return nil })
	if err != nil || !called {
		t.Fatal(err)
	}
}

func TestPlatformFinalControlAcknowledgesNormalAndOfficialSettlement(t *testing.T) {
	for _, result := range []error{nil, errors.New("acquisition failed")} {
		var calls atomic.Int32
		var acknowledgements atomic.Int32
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "application/json")
			if strings.HasSuffix(r.URL.Path, "/platform-pause") {
				acknowledgements.Add(1)
				_, _ = w.Write([]byte(`{"status":"PAUSED"}`))
				return
			}
			action := "CONTINUE"
			if calls.Add(1) > 1 {
				action = "PAUSE"
			}
			_ = json.NewEncoder(w).Encode(controlplane.PlatformControl{Action: action, PlatformVersion: 2})
		}))
		client, _ := controlplane.NewClient(server.URL, strings.Repeat("t", 32))
		version := int64(1)
		err := runPlatformTask(context.Background(), client, &controlplane.ClaimedTask{TaskID: "fixture", Kind: "DISCOVERY", PlatformControlVersion: &version}, func(context.Context) error { return result })
		server.Close()
		if err != nil || acknowledgements.Load() != 1 {
			t.Fatalf("terminal ack: %v", err)
		}
	}
}

func TestPlatformChangedEpochNeverAcknowledgesNewRunningState(t *testing.T) {
	var controls, acks atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if strings.HasSuffix(r.URL.Path, "/platform-pause") {
			acks.Add(1)
			w.WriteHeader(409)
			_, _ = w.Write([]byte(`{"code":"CONTROL_CHANGED"}`))
			return
		}
		action := "PAUSE"
		if controls.Add(1) > 2 {
			action = "CONTINUE"
		}
		_ = json.NewEncoder(w).Encode(controlplane.PlatformControl{Action: action, PlatformVersion: 3})
	}))
	defer server.Close()
	client, _ := controlplane.NewClient(server.URL, strings.Repeat("t", 32))
	version := int64(1)
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	err := runPlatformTask(ctx, client, &controlplane.ClaimedTask{TaskID: "fixture", Kind: "DISCOVERY", PlatformControlVersion: &version}, func(context.Context) error { t.Fatal("paused work started"); return nil })
	if err == nil || acks.Load() != 1 {
		t.Fatalf("epoch handling err=%v acks=%d", err, acks.Load())
	}
}

// Runs against the real Java HTTP server launched by the platform-control PG test.
// No GitHub network or credentials are used; the acquisition waits until the
// production control monitor cancels it, then the production executor sends ACK.
func TestPlatformJavaInterop(t *testing.T) {
	base := os.Getenv("SKILLHUB_PLATFORM_INTEROP_URL")
	if base == "" {
		t.Skip("Java interoperability fixture not supplied")
	}
	client, err := controlplane.NewClient(base, os.Getenv("SKILLHUB_PLATFORM_INTEROP_TOKEN"))
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	task, err := client.ClaimWithCapabilities(ctx, "platform-interop", "DISCOVERY", []string{controlplane.PlatformPauseCapability})
	if err != nil || task == nil {
		t.Fatalf("real Java claim: %v", err)
	}
	if task.Discovery.Checkpoint != "confirmed-page" {
		t.Fatal("Java claim lost persisted checkpoint")
	}
	if task.PlatformControlVersion == nil {
		t.Fatal("platform claim missing control version")
	}
	err = executeLeasedTask(ctx, client, task, func(work context.Context) error {
		if err := os.WriteFile(os.Getenv("SKILLHUB_PLATFORM_INTEROP_READY"), []byte("ready"), 0600); err != nil {
			return err
		}
		<-work.Done()
		return work.Err()
	})
	if err != nil {
		t.Fatal(err)
	}
}

func TestPlatformPauseBeforeRepositoryStartsUsesPersistedProgress(t *testing.T) {
	var received controlplane.PlatformPause
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if strings.HasSuffix(r.URL.Path, "/platform-control") {
			_, _ = w.Write([]byte(`{"action":"PAUSE","platformVersion":2}`))
			return
		}
		_ = json.NewDecoder(r.Body).Decode(&received)
		_, _ = w.Write([]byte(`{"status":"PAUSED"}`))
	}))
	defer server.Close()
	client, _ := controlplane.NewClient(server.URL, strings.Repeat("t", 32))
	version := int64(1)
	task := &controlplane.ClaimedTask{TaskID: "fixture", Kind: "REPOSITORY_SYNC", PlatformControlVersion: &version,
		Repository: &controlplane.RepositorySnapshot{FixedCommitSHA: strings.Repeat("a", 40), PathCheckpoint: "confirmed", IncompleteSubtrees: []string{"unknown"}}}
	err := runPlatformTask(context.Background(), client, task, func(context.Context) error { t.Fatal("paused worker started"); return nil })
	if err != nil {
		t.Fatal(err)
	}
	if received.RepositoryProgress == nil || received.RepositoryProgress.ObservedCommitSHA == nil || *received.RepositoryProgress.ObservedCommitSHA != strings.Repeat("a", 40) || *received.RepositoryProgress.PathCheckpoint != "confirmed" || len(received.RepositoryProgress.IncompleteSubtrees) != 1 {
		t.Fatalf("lost persisted progress: %+v", received.RepositoryProgress)
	}
}

func TestPlatformPauseAllowsCompletedArtifactSettlementBeforeAck(t *testing.T) {
	var paused, settled atomic.Bool
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if strings.HasSuffix(r.URL.Path, "/platform-control") {
			action := "CONTINUE"
			if paused.Load() {
				action = "PAUSE"
			}
			_ = json.NewEncoder(w).Encode(controlplane.PlatformControl{Action: action, PlatformVersion: 2})
			return
		}
		if !settled.Load() {
			t.Error("ACK before completed artifact settlement")
		}
		_, _ = w.Write([]byte(`{"status":"PAUSED"}`))
	}))
	defer server.Close()
	client, _ := controlplane.NewClient(server.URL, strings.Repeat("t", 32))
	version := int64(1)
	err := runPlatformTask(context.Background(), client, &controlplane.ClaimedTask{TaskID: "fixture", Kind: "REPOSITORY_SYNC", PlatformControlVersion: &version}, func(ctx context.Context) error {
		if err := beginPlatformDelivery(ctx); err != nil {
			return err
		}
		paused.Store(true)
		state := ctx.Value(platformContextKey{}).(*platformExecution)
		if err := state.before(ctx); !errors.Is(err, errPlatformPaused) {
			t.Fatalf("expected pause: %v", err)
		}
		if ctx.Err() != nil {
			t.Fatal("platform pause cancelled completed artifact settlement")
		}
		settled.Store(true)
		endPlatformDelivery(ctx)
		if ctx.Err() == nil {
			t.Fatal("acquisition was not cancelled after settlement")
		}
		return ctx.Err()
	})
	if err != nil {
		t.Fatal(err)
	}
}

func TestPlatformAckResponseLossRetriesIdempotently(t *testing.T) {
	var acknowledgements atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if strings.HasSuffix(r.URL.Path, "/platform-control") {
			_, _ = w.Write([]byte(`{"action":"PAUSE","platformVersion":2}`))
			return
		}
		if acknowledgements.Add(1) == 1 {
			connection, _, err := w.(http.Hijacker).Hijack()
			if err != nil {
				t.Error(err)
				return
			}
			_ = connection.Close()
			return
		}
		_, _ = w.Write([]byte(`{"status":"PAUSED"}`))
	}))
	defer server.Close()
	client, _ := controlplane.NewClient(server.URL, strings.Repeat("t", 32))
	version := int64(1)
	err := runPlatformTask(context.Background(), client, &controlplane.ClaimedTask{TaskID: "fixture", Kind: "DISCOVERY", PlatformControlVersion: &version}, func(context.Context) error { t.Fatal("paused work started"); return nil })
	if err != nil || acknowledgements.Load() != 2 {
		t.Fatalf("ack recovery=%v calls=%d", err, acknowledgements.Load())
	}
}
