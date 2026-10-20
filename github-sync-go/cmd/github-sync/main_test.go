package main

import (
	"strings"
	"testing"
)

func TestLoadConfigBoundsRepositoryWorkersByTotalWorkspace(t *testing.T) {
	t.Setenv("SKILLHUB_GITHUB_SYNC_JAVA_URL", "http://127.0.0.1:8080/internal/github-sync/v1")
	t.Setenv("SKILLHUB_GITHUB_SYNC_SERVICE_TOKEN", strings.Repeat("t", 32))
	t.Setenv("SKILLHUB_GITHUB_SYNC_TEMP_ROOT", t.TempDir())
	t.Setenv("SKILLHUB_GITHUB_SYNC_MAX_WORKSPACE_BYTES", "33554432")
	t.Setenv("SKILLHUB_GITHUB_SYNC_TOTAL_WORKSPACE_BYTES", "67108864")
	t.Setenv("SKILLHUB_GITHUB_SYNC_MIN_FREE_BYTES", "8388608")
	t.Setenv("SKILLHUB_GITHUB_SYNC_REPOSITORY_WORKERS", "2")

	settings, err := loadConfig()
	if err != nil || settings.Pool.RepositoryWorkers != 2 {
		t.Fatalf("two workers within the total budget should start: workers=%d, err=%v",
			settings.Pool.RepositoryWorkers, err)
	}

	t.Setenv("SKILLHUB_GITHUB_SYNC_REPOSITORY_WORKERS", "3")
	if _, err := loadConfig(); err == nil {
		t.Fatal("workers exceeding the total workspace budget must be rejected")
	}
}
