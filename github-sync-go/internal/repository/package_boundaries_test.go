package repository

import (
	"fmt"
	"reflect"
	"testing"

	"github.com/iflytek/skillhub/github-sync-go/internal/github"
)

func TestSourceIgnoreRemovalRestoresOnlySelectedSkill(t *testing.T) {
	snapshot := TreeSnapshot{Entries: []github.TreeEntry{}}
	for _, path := range []string{"skills/custom/SKILL.md", "skills/good/SKILL.md", "docs/demo/SKILL.md", "examples/demo/SKILL.md", "tests/demo/SKILL.md"} {
		snapshot.Entries = append(snapshot.Entries, github.TreeEntry{Path: path, Type: "blob", Mode: "100644", SHA: skillBlobSHA, Size: 1})
	}
	for _, testcase := range []struct {
		name    string
		ignores []string
		want    []string
	}{
		{"ignored", []string{"skills/custom"}, []string{"docs/demo", "examples/demo", "skills/good"}},
		{"restored", nil, []string{"docs/demo", "examples/demo", "skills/custom", "skills/good"}},
	} {
		t.Run(testcase.name, func(t *testing.T) {
			roots := RootsFromSnapshot(snapshot, testcase.ignores)
			if !reflect.DeepEqual(roots.Paths, testcase.want) {
				t.Fatalf("roots=%v want=%v", roots.Paths, testcase.want)
			}
		})
	}
}

func TestPackagePolicyExactBoundariesAndSubmoduleIsolation(t *testing.T) {
	limits := PackageLimits{MaxFiles: 100, MaxSingleFileBytes: 10 << 20, MaxPackageBytes: 100 << 20}
	for _, tc := range []struct {
		name        string
		files       int
		size        int64
		submodule   bool
		wantFailure string
	}{
		{"100-files", 100, 1, false, ""}, {"101-files", 101, 1, false, "PACKAGE_TOO_LARGE"},
		{"10MiB-file", 1, 10 << 20, false, ""}, {"10MiB-plus-one", 1, (10 << 20) + 1, false, "PACKAGE_TOO_LARGE"},
		{"100MiB-package", 10, 10 << 20, false, ""}, {"110MiB-package", 11, 10 << 20, false, "PACKAGE_TOO_LARGE"},
		{"submodule", 1, 1, true, "SUBMODULE"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			entries := []github.TreeEntry{{Path: "good/SKILL.md", Type: "blob", Mode: "100644", SHA: skillBlobSHA, Size: 1}}
			for i := 0; i < tc.files; i++ {
				name := fmt.Sprintf("file-%03d.txt", i)
				if i == 0 {
					name = "SKILL.md"
				}
				entries = append(entries, github.TreeEntry{Path: "selected/" + name, Type: "blob", Mode: "100644", SHA: skillBlobSHA, Size: tc.size})
			}
			if tc.submodule {
				entries = append(entries, github.TreeEntry{Path: "selected/vendor", Type: "commit", Mode: "160000", SHA: skillBlobSHA})
			}
			result, err := PreflightPackages(TreeSnapshot{Entries: entries}, []string{"good", "selected"}, limits)
			if err != nil {
				t.Fatal(err)
			}
			if tc.wantFailure == "" {
				if len(result.Plans) != 2 || len(result.Failures) != 0 {
					t.Fatalf("boundary rejected: %+v", result)
				}
			} else {
				if len(result.Plans) != 1 || result.Plans[0].Root != "good" || len(result.Failures) != 1 || result.Failures[0].Code != tc.wantFailure || result.Failures[0].Retryable {
					t.Fatalf("failure not isolated: %+v", result)
				}
			}
		})
	}
}
