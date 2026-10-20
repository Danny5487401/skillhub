package repository

import (
	"fmt"
	"testing"

	"github.com/iflytek/skillhub/github-sync-go/internal/github"
)

func TestRootEvidenceBoundariesPreserveDigestWithoutUnboundedBundling(t *testing.T) {
	for _, tc := range []struct {
		name        string
		sizes       []int64
		wantBundled int
		wantFailure string
	}{
		{"single-at-64KiB", []int64{64 << 10}, 1, ""},
		{"single-over-64KiB", []int64{(64 << 10) + 1}, 0, ""},
		{"total-at-256KiB", []int64{64 << 10, 64 << 10, 64 << 10, 64 << 10}, 4, ""},
		{"total-over-256KiB", []int64{64 << 10, 64 << 10, 64 << 10, 64 << 10, 1}, 4, ""},
		{"16-evidence-files", make([]int64, 16), 16, ""},
		{"17-evidence-files", make([]int64, 17), 0, "TOO_MANY_ROOT_EVIDENCE_FILES"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			entries := []github.TreeEntry{{Path: "skills/demo/SKILL.md", Type: "blob", Mode: "100644", SHA: skillBlobSHA, Size: 1}}
			for i, size := range tc.sizes {
				entries = append(entries, github.TreeEntry{Path: fmt.Sprintf("LICENSE-%02d", i), Type: "blob", Mode: "100644", SHA: skillBlobSHA, Size: size})
			}
			// Unrelated root files are not license evidence and do not consume its budget.
			entries = append(entries, github.TreeEntry{Path: "README.md", Type: "blob", Mode: "100644", SHA: skillBlobSHA, Size: 1})
			result, err := PreflightPackages(TreeSnapshot{Entries: entries}, []string{"skills/demo"}, PackageLimits{MaxFiles: 100, MaxSingleFileBytes: 10 << 20, MaxPackageBytes: 100 << 20})
			if err != nil {
				t.Fatal(err)
			}
			if tc.wantFailure != "" {
				if len(result.Plans) != 0 || len(result.Failures) != 1 || result.Failures[0].Code != tc.wantFailure {
					t.Fatalf("evidence limit must block plan: %+v", result)
				}
				return
			}
			if len(result.Plans) != 1 || len(result.Failures) != 0 {
				t.Fatalf("unexpected plan: %+v", result)
			}
			plan := result.Plans[0]
			if len(plan.Evidence) != len(tc.sizes) || len(plan.Files) != 1 || plan.TotalBytes != 1 {
				t.Fatalf("evidence metadata lost or conflated with Skill files: %+v", plan)
			}
			bundled := 0
			var bundledBytes int64
			for i, evidence := range plan.Evidence {
				if evidence.RepositoryPath != fmt.Sprintf("LICENSE-%02d", i) || evidence.SHA != skillBlobSHA || evidence.Size != tc.sizes[i] {
					t.Fatalf("unbundled evidence must retain path, digest and size: %+v", evidence)
				}
				if evidence.Bundled {
					bundled++
					bundledBytes += evidence.Size
				}
			}
			if bundled != tc.wantBundled || bundledBytes > 256<<10 {
				t.Fatalf("bundled=%d bytes=%d want=%d", bundled, bundledBytes, tc.wantBundled)
			}
		})
	}
}

func TestRootLicenseAndNoticeAreBothRetained(t *testing.T) {
	entries := []github.TreeEntry{
		{Path: "LICENSE", Type: "blob", Mode: "100644", SHA: skillBlobSHA, Size: 4},
		{Path: "NOTICE.txt", Type: "blob", Mode: "100644", SHA: skillBlobSHA, Size: 5},
	}
	evidence, failure := planRootEvidence(entries)
	if failure != "" || len(evidence) != 2 {
		t.Fatalf("license and notice not retained: %v %s", evidence, failure)
	}
	for i, file := range evidence {
		if file.RepositoryPath != entries[i].Path || file.SHA != skillBlobSHA || !file.Bundled || file.Size != entries[i].Size {
			t.Fatalf("invalid evidence: %+v", file)
		}
	}
}
