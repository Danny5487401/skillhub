package worker

import (
	"context"
	"errors"
	"sync"
	"time"

	"github.com/iflytek/skillhub/github-sync-go/internal/controlplane"
	"github.com/iflytek/skillhub/github-sync-go/internal/github"
)

var errPlatformPaused = errors.New("platform collection paused")

type platformContextKey struct{}

// The monitor only cancels acquisition. The execution goroutine acknowledges
// stopping after all work and cleanup have returned.
type platformExecution struct {
	control    *controlplane.Client
	task       *controlplane.ClaimedTask
	cancel     context.CancelFunc
	mu         sync.Mutex
	paused     bool
	delivering bool
	observeErr error
	checkpoint string
	completion *controlplane.RepositoryCompletion
}

func (p *platformExecution) before(ctx context.Context) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.paused {
		return errPlatformPaused
	}
	if p.observeErr != nil {
		return p.observeErr
	}
	state, err := p.control.GetPlatformControl(ctx, p.task.TaskID, p.task.Lease)
	if err != nil {
		p.observeErr = err
		p.cancel()
		return err
	}
	if state.Action == "PAUSE" {
		p.paused = true
		if !p.delivering {
			p.cancel()
		}
		return errPlatformPaused
	}
	return nil
}

// A fully built, already-admitted artifact can settle under its live lease.
// Only platform pause cancellation is deferred; shutdown and lease fencing still
// cancel the parent context and therefore the upload.
func beginPlatformDelivery(ctx context.Context) error {
	state, ok := ctx.Value(platformContextKey{}).(*platformExecution)
	if !ok {
		return ctx.Err()
	}
	state.mu.Lock()
	defer state.mu.Unlock()
	if state.paused {
		return errPlatformPaused
	}
	if state.observeErr != nil {
		return state.observeErr
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	state.delivering = true
	return nil
}

func endPlatformDelivery(ctx context.Context) {
	state, ok := ctx.Value(platformContextKey{}).(*platformExecution)
	if !ok {
		return
	}
	state.mu.Lock()
	defer state.mu.Unlock()
	state.delivering = false
	if state.paused {
		state.cancel()
	}
}

func runPlatformTask(ctx context.Context, control *controlplane.Client, task *controlplane.ClaimedTask,
	process func(context.Context) error) error {
	if task.PlatformControlVersion == nil {
		return process(ctx)
	}
	work, cancel := context.WithCancel(ctx)
	defer cancel()
	state := &platformExecution{control: control, task: task, cancel: cancel}
	if task.Repository != nil {
		state.completion = &controlplane.RepositoryCompletion{
			CommitSHA:          task.Repository.FixedCommitSHA,
			PathCheckpoint:     task.Repository.PathCheckpoint,
			IncompleteSubtrees: append([]string(nil), task.Repository.IncompleteSubtrees...),
		}
	}
	if task.Discovery != nil {
		state.checkpoint = task.Discovery.Checkpoint
	}
	if task.OfficialDiscovery != nil {
		state.checkpoint = task.OfficialDiscovery.Checkpoint
	}
	work = context.WithValue(work, platformContextKey{}, state)
	work = github.WithAcquisitionControl(work, state.before)
	work = controlplane.WithCheckpointObserver(work, func(value string) { state.checkpoint = value })
	monitor, stop := context.WithCancel(ctx)
	done := make(chan struct{})
	go func() {
		defer close(done)
		ticker := time.NewTicker(5 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-monitor.Done():
				return
			case <-ticker.C:
				if state.before(monitor) != nil {
					return
				}
			}
		}
	}()
	err := state.before(work)
	if err == nil {
		err = process(work)
	}
	stop()
	<-done
	// Use the lease-renewal context, not the cancelled acquisition context.
	// A pause may have captured the proof while normal completion was settling.
	acknowledged, ackErr := state.acknowledge(ctx)
	if ackErr != nil {
		return ackErr
	}
	if acknowledged {
		return nil
	}
	if errors.Is(err, errPlatformPaused) || errors.Is(err, context.Canceled) && ctx.Err() == nil {
		// A rapid resume invalidated the pause epoch. Release via the normal retry
		// report, retaining Java's confirmed progress; never restart stale local work.
		return errors.New("platform control changed during acquisition")
	}
	return err
}

func (p *platformExecution) acknowledge(ctx context.Context) (bool, error) {
	for attempt := 0; attempt < 3; attempt++ {
		state, err := p.control.GetPlatformControl(ctx, p.task.TaskID, p.task.Lease)
		var api *controlplane.APIError
		if errors.As(err, &api) && api.Code == "LEASE_STALE" {
			return false, nil
		}
		if err != nil {
			return false, err
		}
		if state.Action != "PAUSE" {
			return false, nil
		}
		request := controlplane.PlatformPause{Lease: p.task.Lease, ObservedVersion: state.PlatformVersion, Kind: p.task.Kind}
		if p.task.Kind == "DISCOVERY" {
			if p.checkpoint != "" {
				request.DiscoveryCheckpoint = &p.checkpoint
			}
		} else {
			progress := &controlplane.OfficialRepositoryProgress{IncompleteSubtrees: []string{}, PathResults: []controlplane.RepositoryPathResult{}}
			if p.completion != nil {
				if p.completion.CommitSHA != "" {
					progress.ObservedCommitSHA = &p.completion.CommitSHA
				}
				if p.completion.PathCheckpoint != "" {
					progress.PathCheckpoint = &p.completion.PathCheckpoint
				}
				progress.IncompleteSubtrees = append(progress.IncompleteSubtrees, p.completion.IncompleteSubtrees...)
				progress.PathResults = append(progress.PathResults, p.completion.PathResults...)
			}
			request.RepositoryProgress = progress
		}
		err = p.control.PausePlatform(ctx, p.task.TaskID, request)
		if err == nil {
			return true, nil
		}
		if !errors.As(err, &api) {
			// The ACK may have committed before its response was lost. Read the
			// current epoch again and repeat the idempotent stop confirmation.
			if ctx.Err() != nil {
				return false, ctx.Err()
			}
			continue
		}
		if api.Code != "CONTROL_CHANGED" {
			return false, err
		}
	}
	return false, errors.New("platform control changed repeatedly")
}
