package repository

import (
	"reflect"
	"testing"

	"github.com/iflytek/skillhub/github-sync-go/internal/github"
)

func TestIgnoredCandidateStaysInParentPackageAndNestedSkillIsSeparate(t *testing.T) {
	snapshot := TreeSnapshot{Entries: []github.TreeEntry{
		{Path: "skills/parent/SKILL.md", Type: "blob", Mode: "100644", SHA: skillBlobSHA, Size: 12},
		{Path: "skills/parent/test/SKILL.md", Type: "blob", Mode: "100644", SHA: skillBlobSHA, Size: 12},
		{Path: "skills/parent/test/example.txt", Type: "blob", Mode: "100644", SHA: skillBlobSHA, Size: 4},
		{Path: "skills/parent/child/SKILL.md", Type: "blob", Mode: "100644", SHA: skillBlobSHA, Size: 12},
		{Path: "skills/parent/child/example.txt", Type: "blob", Mode: "100644", SHA: skillBlobSHA, Size: 4},
	}}
	roots := RootsFromSnapshot(snapshot, nil)
	if !reflect.DeepEqual(roots.Paths, []string{"skills/parent", "skills/parent/child"}) {
		t.Fatalf("unexpected Skill roots: %v", roots.Paths)
	}
	preflight, err := PreflightPackages(snapshot, roots.Paths, PackageLimits{
		MaxFiles: 500, MaxSingleFileBytes: 10 << 20, MaxPackageBytes: 100 << 20,
	})
	if err != nil || len(preflight.Failures) != 0 || len(preflight.Plans) != 2 {
		t.Fatalf("unexpected package preflight: %+v, error=%v", preflight, err)
	}
	if got := packagePaths(preflight.Plans[0]); !reflect.DeepEqual(got,
		[]string{"SKILL.md", "test/SKILL.md", "test/example.txt"}) {
		t.Fatalf("parent package paths = %v", got)
	}
	if got := packagePaths(preflight.Plans[1]); !reflect.DeepEqual(got,
		[]string{"SKILL.md", "example.txt"}) {
		t.Fatalf("child package paths = %v", got)
	}
}

func TestDefaultAndSourceIgnoredDirectoriesDoNotBecomeSkillRoots(t *testing.T) {
	ignored := []string{
		".git", "node_modules", ".venv", "venv", "dist", "build",
		"target", "__pycache__", "test", "tests",
	}
	if len(defaultIgnoredSegments) != len(ignored) {
		t.Fatalf("default ignored directory count = %d", len(defaultIgnoredSegments))
	}
	snapshot := TreeSnapshot{Entries: []github.TreeEntry{
		{Path: "skills/good/SKILL.md", Type: "blob", Mode: "100644", SHA: skillBlobSHA, Size: 12},
		{Path: "skills/custom/SKILL.md", Type: "blob", Mode: "100644", SHA: skillBlobSHA, Size: 12},
	}}
	for _, segment := range ignored {
		snapshot.Entries = append(snapshot.Entries, github.TreeEntry{
			Path: "skills/" + segment + "/SKILL.md", Type: "blob",
			Mode: "100644", SHA: skillBlobSHA, Size: 12,
		})
	}
	roots := RootsFromSnapshot(snapshot, []string{"skills/custom"})
	if !reflect.DeepEqual(roots.Paths, []string{"skills/good"}) {
		t.Fatalf("ignored directories became Skill roots: %v", roots.Paths)
	}
}

func TestUnsafeChildPackageDoesNotBlockParent(t *testing.T) {
	snapshot := TreeSnapshot{Entries: []github.TreeEntry{
		{Path: "skills/parent/SKILL.md", Type: "blob", Mode: "100644", SHA: skillBlobSHA, Size: 12},
		{Path: "skills/parent/child/SKILL.md", Type: "blob", Mode: "100644", SHA: skillBlobSHA, Size: 12},
		{Path: "skills/parent/child/link", Type: "blob", Mode: "120000", SHA: skillBlobSHA, Size: 5},
	}}
	roots := RootsFromSnapshot(snapshot, nil)
	preflight, err := PreflightPackages(snapshot, roots.Paths, PackageLimits{
		MaxFiles: 500, MaxSingleFileBytes: 10 << 20, MaxPackageBytes: 100 << 20,
	})
	if err != nil || len(preflight.Plans) != 1 || preflight.Plans[0].Root != "skills/parent" ||
		len(preflight.Failures) != 1 || preflight.Failures[0].Root != "skills/parent/child" ||
		preflight.Failures[0].Code != "SYMLINK" {
		t.Fatalf("unsafe child isolation failed: %+v, error=%v", preflight, err)
	}
}

func packagePaths(plan PackagePlan) []string {
	paths := make([]string, 0, len(plan.Files))
	for _, file := range plan.Files {
		paths = append(paths, file.RelativePath)
	}
	return paths
}
