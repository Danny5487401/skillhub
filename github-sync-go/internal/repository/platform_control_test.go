package repository

import (
	"context"
	"errors"
	"testing"

	"github.com/iflytek/skillhub/github-sync-go/internal/github"
)

func TestPlatformControlFencesGitStepsWithoutStartingSubprocess(t *testing.T) {
	stopped := errors.New("platform paused")
	ctx := github.WithAcquisitionControl(context.Background(), func(context.Context) error { return stopped })
	ctx = WithGitRequestPolicy(ctx, func(context.Context) error { t.Fatal("quota policy ran after platform stopped"); return nil }, nil)
	for _, step := range []string{"fetch", "sparse-checkout", "checkout"} {
		if err := runBoundedGit(ctx, "does-not-exist", "does-not-exist", 1, 0, step); !errors.Is(err, stopped) {
			t.Fatalf("%s: %v", step, err)
		}
	}
}
