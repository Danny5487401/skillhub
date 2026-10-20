package github

import (
	"context"
	"os"
	"testing"
	"time"
)

// Opt-in check of the structured search contract against the public GitHub API.
func TestLiveStructuredRepositorySearch(t *testing.T) {
	if os.Getenv("SKILLHUB_GITHUB_SYNC_LIVE_SEARCH") != "1" {
		t.Skip("set SKILLHUB_GITHUB_SYNC_LIVE_SEARCH=1 to use the public GitHub API")
	}
	client := NewClient("")
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	until := time.Now().UTC()
	for _, selector := range []struct{ kind, value string }{
		{"KEYWORD", "agent skills"},
		{"TOPIC", "agent-skills"},
	} {
		page, _, err := client.SearchRepositories(ctx, selector.kind, selector.value,
			nil, until, 1)
		if err != nil {
			t.Fatalf("%s search failed: %v", selector.kind, err)
		}
		if page.TotalCount < 0 || len(page.Repositories) > repositoryPageSize {
			t.Fatalf("%s search returned invalid metadata: total=%d page=%d",
				selector.kind, page.TotalCount, len(page.Repositories))
		}
	}
}
