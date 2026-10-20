package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/iflytek/skillhub/github-sync-go/internal/controlplane"
	"github.com/iflytek/skillhub/github-sync-go/internal/worker"
)

type config struct {
	JavaURL    string
	Token      string
	HealthAddr string
	Pool       worker.PoolConfig
}

func main() {
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)))
	settings, err := loadConfig()
	if err != nil {
		slog.Error("GitHub sync configuration invalid", "code", "INVALID_CONFIGURATION")
		os.Exit(1)
	}
	workspaceGuard, err := prepareTempWorkspace(settings.Pool.Repository.TempRoot)
	if err != nil {
		slog.Error("GitHub sync temporary workspace unavailable", "code", "TEMP_WORKSPACE_UNAVAILABLE")
		os.Exit(1)
	}
	defer workspaceGuard.Close()
	control, err := controlplane.NewClient(settings.JavaURL, settings.Token)
	if err != nil {
		slog.Error("GitHub sync control plane unavailable", "code", "INVALID_CONTROL_PLANE")
		os.Exit(1)
	}
	listener, err := net.Listen("tcp", settings.HealthAddr)
	if err != nil {
		slog.Error("GitHub sync health listener unavailable", "code", "HEALTH_BIND_FAILED")
		os.Exit(1)
	}
	counters := &worker.Counters{}
	server := &http.Server{Handler: healthHandler(counters), ReadHeaderTimeout: 5 * time.Second}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	serveDone := make(chan error, 1)
	go func() { serveDone <- server.Serve(listener) }()
	poolDone := make(chan error, 1)
	go func() { poolDone <- worker.Run(ctx, control, settings.Pool, counters) }()
	poolStopped := false
	failed := false
	select {
	case <-ctx.Done():
	case err := <-serveDone:
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			slog.Error("GitHub sync health server stopped", "code", "HEALTH_SERVER_FAILED")
			failed = true
		}
		stop()
	case err := <-poolDone:
		poolStopped = true
		if err != nil && !errors.Is(err, context.Canceled) {
			slog.Error("GitHub sync worker stopped", "code", "WORKER_STOPPED")
			failed = true
		}
		stop()
	}
	counters.DiscoveryReady.Store(false)
	counters.RepositoryReady.Store(false)
	shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = server.Shutdown(shutdown)
	if !poolStopped {
		select {
		case <-poolDone:
		case <-time.After(20 * time.Second):
			slog.Warn("GitHub sync shutdown timed out", "code", "SHUTDOWN_TIMEOUT")
			failed = true
		}
	}
	if failed {
		os.Exit(1)
	}
}

func healthHandler(counters *worker.Counters) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health/live", func(response http.ResponseWriter, _ *http.Request) {
		response.WriteHeader(http.StatusNoContent)
	})
	mux.HandleFunc("GET /health/ready", func(response http.ResponseWriter, _ *http.Request) {
		if !counters.DiscoveryReady.Load() || !counters.RepositoryReady.Load() {
			response.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		response.WriteHeader(http.StatusNoContent)
	})
	mux.HandleFunc("GET /metrics", func(response http.ResponseWriter, _ *http.Request) {
		response.Header().Set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
		fmt.Fprintf(response, "skillhub_github_sync_claims_total{kind=\"discovery\"} %d\n", counters.DiscoveryClaims.Load())
		fmt.Fprintf(response, "skillhub_github_sync_claims_total{kind=\"repository\"} %d\n", counters.RepositoryClaims.Load())
		fmt.Fprintf(response, "skillhub_github_sync_errors_total{kind=\"discovery\"} %d\n", counters.DiscoveryErrors.Load())
		fmt.Fprintf(response, "skillhub_github_sync_errors_total{kind=\"repository\"} %d\n", counters.RepositoryErrors.Load())
		fmt.Fprintf(response, "skillhub_github_sync_active_tasks{kind=\"discovery\"} %d\n", counters.DiscoveryActive.Load())
		fmt.Fprintf(response, "skillhub_github_sync_active_tasks{kind=\"repository\"} %d\n", counters.RepositoryActive.Load())
	})
	return mux
}

func loadConfig() (config, error) {
	var settings config
	settings.JavaURL = os.Getenv("SKILLHUB_GITHUB_SYNC_JAVA_URL")
	settings.Token = os.Getenv("SKILLHUB_GITHUB_SYNC_SERVICE_TOKEN")
	settings.HealthAddr = valueOrDefault("SKILLHUB_GITHUB_SYNC_HEALTH_ADDR", ":8090")
	var err error
	if settings.Pool.DiscoveryWorkers, err = positiveInt("SKILLHUB_GITHUB_SYNC_DISCOVERY_WORKERS", 1); err != nil {
		return config{}, err
	}
	if settings.Pool.RepositoryWorkers, err = positiveInt("SKILLHUB_GITHUB_SYNC_REPOSITORY_WORKERS", 1); err != nil {
		return config{}, err
	}
	if settings.Pool.Discovery.MaxPagesPerLease, err = positiveInt("SKILLHUB_GITHUB_SYNC_MAX_PAGES_PER_LEASE", 20); err != nil {
		return config{}, err
	}
	if settings.Pool.Repository.MaxTreeRequests, err = positiveInt("SKILLHUB_GITHUB_SYNC_MAX_TREE_REQUESTS", 100); err != nil {
		return config{}, err
	}
	if settings.Pool.Repository.MaxTreeEntries, err = positiveInt("SKILLHUB_GITHUB_SYNC_MAX_TREE_ENTRIES", 100000); err != nil {
		return config{}, err
	}
	settings.Pool.PollInterval, err = time.ParseDuration(valueOrDefault("SKILLHUB_GITHUB_SYNC_POLL_INTERVAL", "5s"))
	if err != nil || settings.Pool.PollInterval < time.Second {
		return config{}, errors.New("invalid poll interval")
	}
	settings.Pool.Repository.TempRoot = os.Getenv("SKILLHUB_GITHUB_SYNC_TEMP_ROOT")
	settings.Pool.Repository.AllowedS3Hosts = splitHosts(os.Getenv("SKILLHUB_GITHUB_SYNC_ALLOWED_S3_HOSTS"))
	settings.Pool.Repository.MaxWorkspaceBytes, err = positiveBytes("SKILLHUB_GITHUB_SYNC_MAX_WORKSPACE_BYTES")
	if err != nil {
		return config{}, err
	}
	settings.Pool.Repository.MinFreeBytes, err = nonnegativeBytes("SKILLHUB_GITHUB_SYNC_MIN_FREE_BYTES")
	if err != nil {
		return config{}, err
	}
	totalWorkspaceBytes, err := positiveBytes("SKILLHUB_GITHUB_SYNC_TOTAL_WORKSPACE_BYTES")
	if err != nil || int64(settings.Pool.RepositoryWorkers) >
		totalWorkspaceBytes/settings.Pool.Repository.MaxWorkspaceBytes {
		return config{}, errors.New("repository workers exceed total workspace budget")
	}
	if !filepath.IsAbs(settings.Pool.Repository.TempRoot) || settings.JavaURL == "" || settings.Token == "" {
		return config{}, errors.New("missing GitHub sync service configuration")
	}
	info, err := os.Stat(settings.Pool.Repository.TempRoot)
	if err != nil || !info.IsDir() {
		return config{}, errors.New("GitHub sync temporary directory is unavailable")
	}
	var filesystem syscall.Statfs_t
	if err := syscall.Statfs(settings.Pool.Repository.TempRoot, &filesystem); err != nil ||
		uint64(totalWorkspaceBytes)+uint64(settings.Pool.Repository.MinFreeBytes) >
			uint64(filesystem.Blocks)*uint64(filesystem.Bsize) {
		return config{}, errors.New("temporary filesystem is smaller than configured workspace budget")
	}
	probe, err := os.MkdirTemp(settings.Pool.Repository.TempRoot, "write-check-")
	if err != nil {
		return config{}, errors.New("GitHub sync temporary directory is not writable")
	}
	if err := os.Remove(probe); err != nil {
		return config{}, errors.New("GitHub sync temporary directory cannot be cleaned")
	}
	return settings, nil
}

func valueOrDefault(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}

func positiveInt(name string, fallback int) (int, error) {
	value, err := strconv.Atoi(valueOrDefault(name, strconv.Itoa(fallback)))
	if err != nil || value < 1 {
		return 0, errors.New("invalid positive worker configuration")
	}
	return value, nil
}

func positiveBytes(name string) (int64, error) {
	value, err := strconv.ParseInt(os.Getenv(name), 10, 64)
	if err != nil || value < 1 {
		return 0, errors.New("invalid workspace byte budget")
	}
	return value, nil
}

func nonnegativeBytes(name string) (int64, error) {
	value, err := strconv.ParseInt(os.Getenv(name), 10, 64)
	if err != nil || value < 0 {
		return 0, errors.New("invalid minimum free byte budget")
	}
	return value, nil
}

func splitHosts(value string) []string {
	if strings.TrimSpace(value) == "" {
		return nil
	}
	var hosts []string
	for _, item := range strings.Split(value, ",") {
		if host := strings.TrimSpace(item); host != "" {
			hosts = append(hosts, host)
		}
	}
	return hosts
}
