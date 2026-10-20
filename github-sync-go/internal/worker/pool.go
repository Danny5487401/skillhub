package worker

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"sync"
	"sync/atomic"
	"time"

	"github.com/iflytek/skillhub/github-sync-go/internal/controlplane"
)

type PoolConfig struct {
	DiscoveryWorkers  int
	RepositoryWorkers int
	PollInterval      time.Duration
	Discovery         DiscoveryLimits
	Repository        RepositoryLimits
}

type Counters struct {
	DiscoveryClaims  atomic.Uint64
	RepositoryClaims atomic.Uint64
	DiscoveryErrors  atomic.Uint64
	RepositoryErrors atomic.Uint64
	DiscoveryActive  atomic.Int64
	RepositoryActive atomic.Int64
	DiscoveryReady   atomic.Bool
	RepositoryReady  atomic.Bool
}

// supportedCapabilities describes the lifecycle implemented by the executable.
// Java configuration still determines whether official work is scheduled.
func supportedCapabilities() []string {
	return []string{controlplane.OfficialDiscoveryCapability, controlplane.OfficialCheckpointCapability,
		controlplane.OfficialPauseCapability, controlplane.OfficialOwnerCapability, controlplane.PlatformPauseCapability}
}

// Run starts separate bounded claim loops. Each loop claims only its task kind,
// so no lease waits in a queue behind work from the other pool.
func Run(ctx context.Context, control *controlplane.Client,
	config PoolConfig, counters *Counters) error {
	if control == nil || counters == nil || config.DiscoveryWorkers < 1 ||
		config.RepositoryWorkers < 1 || config.DiscoveryWorkers > 32 ||
		config.RepositoryWorkers > 32 || config.PollInterval < time.Second ||
		config.Discovery.MaxPagesPerLease < 1 || config.Repository.MaxWorkspaceBytes < 1 {
		return errors.New("invalid GitHub sync worker pool configuration")
	}
	instance := make([]byte, 6)
	if _, err := rand.Read(instance); err != nil {
		return err
	}
	instanceID := hex.EncodeToString(instance)
	var running sync.WaitGroup
	running.Add(1)
	go func() {
		defer running.Done()
		for ctx.Err() == nil {
			if err := control.HeartbeatWithCapabilities(ctx, "skillhub-"+instanceID, supportedCapabilities()); err != nil && ctx.Err() == nil {
				slog.Warn("GitHub sync heartbeat failed", "code", safeErrorCode(err))
			}
			pause(ctx, 30*time.Second)
		}
	}()
	start := func(kind string, count int) {
		for index := range count {
			running.Add(1)
			workerID := fmt.Sprintf("skillhub-%s-%s-%d", instanceID, kind, index)
			go func() {
				defer running.Done()
				claimLoop(ctx, control, config, counters, kind, workerID)
			}()
		}
	}
	start("DISCOVERY", config.DiscoveryWorkers)
	start("REPOSITORY_SYNC", config.RepositoryWorkers)
	running.Wait()
	return ctx.Err()
}

func claimLoop(ctx context.Context, control *controlplane.Client, config PoolConfig,
	counters *Counters, kind, workerID string) {
	for ctx.Err() == nil {
		task, err := control.ClaimWithCapabilities(ctx, workerID, kind, supportedCapabilities())
		if err != nil {
			setReady(counters, kind, false)
			countError(counters, kind)
			slog.Warn("GitHub sync claim failed", "kind", kind, "code", safeErrorCode(err))
			pause(ctx, config.PollInterval)
			continue
		}
		setReady(counters, kind, true)
		if task == nil {
			pause(ctx, config.PollInterval)
			continue
		}
		countClaim(counters, kind)
		countActive(counters, kind, 1)
		if kind == "DISCOVERY" {
			err = ExecuteDiscovery(ctx, control, task, config.Discovery)
		} else {
			err = ExecuteRepository(ctx, control, task, config.Repository)
		}
		countActive(counters, kind, -1)
		if err != nil && ctx.Err() == nil {
			countError(counters, kind)
			slog.Warn("GitHub sync task did not finish in this lease",
				"kind", kind, "task_id", task.TaskID, "code", safeErrorCode(err))
		}
	}
}

func pause(ctx context.Context, interval time.Duration) {
	timer := time.NewTimer(interval)
	defer timer.Stop()
	select {
	case <-ctx.Done():
	case <-timer.C:
	}
}

func countClaim(counters *Counters, kind string) {
	if kind == "DISCOVERY" {
		counters.DiscoveryClaims.Add(1)
	} else {
		counters.RepositoryClaims.Add(1)
	}
}

func countError(counters *Counters, kind string) {
	if kind == "DISCOVERY" {
		counters.DiscoveryErrors.Add(1)
	} else {
		counters.RepositoryErrors.Add(1)
	}
}

func countActive(counters *Counters, kind string, delta int64) {
	if kind == "DISCOVERY" {
		counters.DiscoveryActive.Add(delta)
	} else {
		counters.RepositoryActive.Add(delta)
	}
}

func setReady(counters *Counters, kind string, ready bool) {
	if kind == "DISCOVERY" {
		counters.DiscoveryReady.Store(ready)
	} else {
		counters.RepositoryReady.Store(ready)
	}
}

func safeErrorCode(err error) string {
	var apiError *controlplane.APIError
	if errors.As(err, &apiError) {
		return apiError.SafeCode()
	}
	return "WORKER_ERROR"
}
