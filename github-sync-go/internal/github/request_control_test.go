package github

import (
	"context"
	"errors"
	"testing"
)

func TestPlatformControlFencesEveryGithubReadBeforeQuotaAndNetwork(t *testing.T) {
	stop := errors.New("platform paused")
	ctx := WithAcquisitionControl(context.Background(), func(context.Context) error { return stop })
	client := NewClient("")
	client.SetRequestPolicy(func(context.Context) error { t.Fatal("quota hook ran after platform stop"); return nil }, nil)
	// All acquisition channels (search, owner listing, metadata, HEAD and Tree)
	// pass through get; no external request may occur after the control denial.
	for _, path := range []string{"/search/repositories", "/search/code", "/users/fixture/repos", "/repos/fixture/skills", "/repos/fixture/skills/git/ref/heads/main", "/repos/fixture/skills/git/trees/fixture"} {
		var payload any
		if _, err := client.get(ctx, path, &payload); !errors.Is(err, stop) {
			t.Fatalf("%s: %v", path, err)
		}
	}
}
