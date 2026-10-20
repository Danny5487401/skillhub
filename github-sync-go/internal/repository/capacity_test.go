package repository

import (
	"context"
	"fmt"
	"runtime"
	"testing"
	"time"

	"github.com/iflytek/skillhub/github-sync-go/internal/github"
)

type capacityTreeReader struct {
	tree  github.Tree
	calls int
}

func (reader *capacityTreeReader) GetTree(_ context.Context, _, _, sha string,
	_ bool) (github.Tree, github.Quota, error) {
	reader.calls++
	if sha != reader.tree.SHA {
		return github.Tree{}, github.Quota{}, fmt.Errorf("unexpected tree SHA")
	}
	return reader.tree, github.Quota{}, nil
}

func TestSyntheticRepositoryMetadataCapacity(t *testing.T) {
	const repositories = 1000
	const skillsPerRepository = 10
	const expectedSkills = repositories * skillsPerRepository
	var before, after runtime.MemStats
	runtime.ReadMemStats(&before)
	started := time.Now()
	requests := 0
	plans := 0
	for repositoryIndex := range repositories {
		sha := fmt.Sprintf("%040x", repositoryIndex+1)
		entries := make([]github.TreeEntry, 0, skillsPerRepository)
		for skillIndex := range skillsPerRepository {
			entries = append(entries, github.TreeEntry{
				Path: fmt.Sprintf("skills/skill-%02d/SKILL.md", skillIndex),
				Type: "blob", Mode: "100644", SHA: skillBlobSHA, Size: 64,
			})
		}
		reader := &capacityTreeReader{tree: github.Tree{SHA: sha, Entries: entries}}
		snapshot, err := EnumerateTree(context.Background(), reader, "fixture", "repo", sha,
			TreeLimits{MaxTreeRequests: 8, MaxEntries: 100_000})
		if err != nil || len(snapshot.IncompleteSubtrees) != 0 {
			t.Fatalf("repository %d metadata failed: snapshot=%+v, error=%v", repositoryIndex, snapshot, err)
		}
		roots := RootsFromSnapshot(snapshot, nil)
		preflight, err := PreflightPackages(snapshot, roots.Paths, PackageLimits{
			MaxFiles: 500, MaxSingleFileBytes: 10 << 20, MaxPackageBytes: 100 << 20,
		})
		if err != nil || len(roots.Paths) != skillsPerRepository ||
			len(preflight.Plans) != skillsPerRepository || len(preflight.Failures) != 0 {
			t.Fatalf("repository %d preflight failed: roots=%d, plans=%d, failures=%d, error=%v",
				repositoryIndex, len(roots.Paths), len(preflight.Plans), len(preflight.Failures), err)
		}
		requests += reader.calls
		plans += len(preflight.Plans)
	}
	runtime.ReadMemStats(&after)
	if requests != repositories || plans != expectedSkills {
		t.Fatalf("unexpected synthetic coverage: requests=%d, plans=%d", requests, plans)
	}
	t.Logf("synthetic metadata baseline: repositories=%d, skill paths=%d, tree requests=%d, elapsed=%s, allocated=%d bytes",
		repositories, plans, requests, time.Since(started), after.TotalAlloc-before.TotalAlloc)
}
