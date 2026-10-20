package worker

import (
	"context"
	"errors"
	"slices"

	"github.com/iflytek/skillhub/github-sync-go/internal/controlplane"
	"github.com/iflytek/skillhub/github-sync-go/internal/github"
	"github.com/iflytek/skillhub/github-sync-go/internal/repository"
)

func repositoryNeedsOfficialControl(task *controlplane.ClaimedTask) bool {
	return slices.Contains(task.RequiredCapabilities, controlplane.OfficialPauseCapability)
}

// The claim's source ID is not authoritative: a candidate can become bound or
// gain an independent origin during acquisition. Java resolves current ownership.
func pauseOfficialRepositoryIfNeeded(ctx context.Context, control *controlplane.Client,
	task *controlplane.ClaimedTask, completion *controlplane.RepositoryCompletion) (bool, error) {
	if err := github.CheckAcquisitionControl(ctx); err != nil {
		return false, err
	}
	if !repositoryNeedsOfficialControl(task) {
		return false, nil
	}
	for attempts := 0; attempts < 3; attempts++ {
		state, err := control.GetOfficialTaskControl(ctx, task.TaskID, task.Lease)
		if err != nil || state.Action == "CONTINUE" {
			return false, err
		}
		progress := controlplane.OfficialRepositoryProgress{
			IncompleteSubtrees: completion.IncompleteSubtrees,
			PathResults:        completion.PathResults,
		}
		if completion.CommitSHA != "" {
			progress.ObservedCommitSHA = &completion.CommitSHA
		}
		if completion.PathCheckpoint != "" {
			progress.PathCheckpoint = &completion.PathCheckpoint
		}
		// Accepted packages remain in Java's ingestion records. We only send
		// actual local path outcomes, never manufacture ACCEPTED receipt IDs.
		err = control.PauseOfficialRepository(ctx, task.TaskID, task.Lease, progress)
		if err == nil {
			return true, nil
		}
		var apiError *controlplane.APIError
		if !errors.As(err, &apiError) || apiError.Code != "CONTROL_CHANGED" {
			return false, err
		}
	}
	return false, errors.New("official repository control changed repeatedly")
}

// A tree walk can issue multiple requests after a truncated response. Guard
// each request, and cancel the walk after control ends the task so enumeration
// cannot swallow a control error and proceed to acquire packages.
type officialRepositoryTreeReader struct {
	reader  repository.TreeReader
	before  func(context.Context) (bool, error)
	stop    context.CancelFunc
	stopped bool
	err     error
}

func (r *officialRepositoryTreeReader) GetTree(ctx context.Context, owner, name, sha string, recursive bool) (github.Tree, github.Quota, error) {
	if !r.stopped {
		paused, err := r.before(ctx)
		if paused || err != nil {
			r.stopped, r.err = true, err
			r.stop()
		}
	}
	if r.stopped {
		return github.Tree{}, github.Quota{}, context.Canceled
	}
	return r.reader.GetTree(ctx, owner, name, sha, recursive)
}
