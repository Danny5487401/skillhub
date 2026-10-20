package repository

import (
	"archive/zip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"os"
	"os/exec"
	"slices"
	"sort"
	"strings"
	"testing"
	"time"

	"github.com/iflytek/skillhub/github-sync-go/internal/github"
)

type countedLiveTreeReader struct {
	client *github.Client
	calls  int
}

const fixedPublicCommitSHA = "49f948faa9258a0c61caceaf225e179651397431"

func loadFixedPublicTree(t *testing.T, ctx context.Context) (TreeSnapshot, int) {
	t.Helper()
	anonymous := os.Getenv("SKILLHUB_GITHUB_SYNC_LIVE_ANONYMOUS") == "1"
	token := ""
	if !anonymous {
		token = os.Getenv("SKILLHUB_GITHUB_SYNC_LIVE_TOKEN")
		if token == "" {
			output, err := exec.CommandContext(ctx, "gh", "auth", "token").Output()
			if err != nil {
				t.Fatal("no configured GitHub test token or usable local gh authentication")
			}
			token = strings.TrimSpace(string(output))
		}
		if token == "" {
			t.Fatal("GitHub test token is empty")
		}
	}
	client := github.NewClient(token)
	metadata, _, err := client.GetRepository(ctx, "openai", "skills")
	if err != nil {
		t.Fatal(err)
	}
	if metadata.ID != 1103643573 || metadata.Private || metadata.Owner.Login != "openai" {
		t.Fatal("public repository metadata differs from the fixed fixture")
	}
	treeSHA, _, err := client.GetCommitTreeSHA(ctx, metadata.Owner.Login,
		metadata.Name, fixedPublicCommitSHA)
	if err != nil {
		t.Fatal(err)
	}
	if treeSHA != "224b363928698e143876aef008232ed73d8e6f81" {
		t.Fatal("fixed commit resolved to an unexpected tree")
	}
	reader := &countedLiveTreeReader{client: client}
	snapshot, err := EnumerateTree(ctx, reader, metadata.Owner.Login, metadata.Name,
		treeSHA, TreeLimits{MaxTreeRequests: 10, MaxEntries: 5000})
	if err != nil {
		t.Fatal(err)
	}
	return snapshot, 2 + reader.calls
}

func (reader *countedLiveTreeReader) GetTree(ctx context.Context, owner, name, sha string,
	recursive bool) (github.Tree, github.Quota, error) {
	reader.calls++
	return reader.client.GetTree(ctx, owner, name, sha, recursive)
}

// This opt-in test checks the real GitHub REST metadata path for a fixed public
// commit. SKILLHUB_GITHUB_SYNC_LIVE_ANONYMOUS=1 avoids local gh credentials;
// otherwise any credential stays in process memory and is never included in output.
func TestLiveEnumerateFixedPublicGitHubTree(t *testing.T) {
	if os.Getenv("SKILLHUB_GITHUB_SYNC_LIVE_TREE") != "1" {
		t.Skip("set SKILLHUB_GITHUB_SYNC_LIVE_TREE=1 to query the public GitHub Tree API")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	snapshot, apiOperations := loadFixedPublicTree(t, ctx)
	roots := RootsFromSnapshot(snapshot, nil)
	for _, expected := range []string{
		"skills/.system/skill-creator", "skills/.curated/define-goal",
	} {
		if !slices.Contains(roots.Paths, expected) {
			t.Fatalf("real GitHub tree omitted expected Skill root %q", expected)
		}
	}
	if len(snapshot.IncompleteSubtrees) != 0 || len(snapshot.Entries) < 1000 ||
		apiOperations != 3 {
		t.Fatalf("real tree was incomplete: entries=%d incomplete=%d treeCalls=%d",
			len(snapshot.Entries), len(snapshot.IncompleteSubtrees), apiOperations-2)
	}
	if len(roots.Paths) != 44 {
		t.Fatalf("fixed public tree changed Skill root count: got=%d want=44", len(roots.Paths))
	}
	// Match the effective publish policy in Java's application.yml. This reads only
	// Tree metadata; it never downloads the 44 candidate packages.
	policy := PackageLimits{
		MaxFiles: 100, MaxSingleFileBytes: 10 << 20, MaxPackageBytes: 100 << 20,
	}
	preflight, err := PreflightPackages(snapshot, roots.Paths, policy)
	if err != nil {
		t.Fatal(err)
	}
	if len(preflight.Plans)+len(preflight.Failures) != len(roots.Paths) {
		t.Fatalf("preflight lost Skill roots: roots=%d plans=%d failures=%d",
			len(roots.Paths), len(preflight.Plans), len(preflight.Failures))
	}
	const rejectedRoot = "skills/.curated/cloudflare-deploy"
	if len(preflight.Plans) != 43 || len(preflight.Failures) != 1 ||
		preflight.Failures[0].Root != rejectedRoot ||
		preflight.Failures[0].Code != "PACKAGE_TOO_LARGE" {
		t.Fatalf("unexpected fixed-tree package policy result: plans=%d failures=%+v",
			len(preflight.Plans), preflight.Failures)
	}
	rejectedFiles := 0
	for _, entry := range snapshot.Entries {
		if strings.HasPrefix(entry.Path, rejectedRoot+"/") && entry.Type == "blob" {
			rejectedFiles++
		}
	}
	if rejectedFiles <= policy.MaxFiles {
		t.Fatalf("rejected root does not exceed file-count policy: files=%d limit=%d",
			rejectedFiles, policy.MaxFiles)
	}
	failureCounts := make(map[string]int)
	for _, failure := range preflight.Failures {
		failureCounts[failure.Code]++
		t.Logf("package preflight rejected root: path=%s code=%s files=%d limit=%d",
			failure.Root, failure.Code, rejectedFiles, policy.MaxFiles)
	}
	failureCodes := make([]string, 0, len(failureCounts))
	for code := range failureCounts {
		failureCodes = append(failureCodes, code)
	}
	sort.Strings(failureCodes)
	var totalBytes, largestPackageBytes int64
	maxPackageFiles := 0
	for _, plan := range preflight.Plans {
		totalBytes += plan.TotalBytes
		largestPackageBytes = max(largestPackageBytes, plan.TotalBytes)
		maxPackageFiles = max(maxPackageFiles, len(plan.Files))
	}
	t.Logf("fixed public tree: api_operations=%d entries=%d skill_roots=%d incomplete=%d",
		apiOperations, len(snapshot.Entries), len(roots.Paths), len(snapshot.IncompleteSubtrees))
	t.Logf("package preflight: eligible=%d rejected=%d declared_bytes=%d largest_package_bytes=%d max_package_files=%d",
		len(preflight.Plans), len(preflight.Failures), totalBytes, largestPackageBytes, maxPackageFiles)
	for _, code := range failureCodes {
		t.Logf("package preflight rejection: code=%s count=%d", code, failureCounts[code])
	}
}

// Opt in separately from the Tree-only test: this makes one bounded Git
// acquisition per eligible Skill and closes each workspace before the next.
func TestLiveAcquireAllFixedPublicSkillsSequentially(t *testing.T) {
	if os.Getenv("SKILLHUB_GITHUB_SYNC_LIVE_ALL_PACKAGES") != "1" {
		t.Skip("set SKILLHUB_GITHUB_SYNC_LIVE_ALL_PACKAGES=1 for full public repository acquisition")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Minute)
	defer cancel()
	started := time.Now()
	snapshot, apiOperations := loadFixedPublicTree(t, ctx)
	if apiOperations != 3 || len(snapshot.IncompleteSubtrees) != 0 {
		t.Fatalf("fixed public Tree is incomplete: operations=%d incomplete=%d",
			apiOperations, len(snapshot.IncompleteSubtrees))
	}
	roots := RootsFromSnapshot(snapshot, nil)
	preflight, err := PreflightPackages(snapshot, roots.Paths, PackageLimits{
		MaxFiles: 100, MaxSingleFileBytes: 10 << 20, MaxPackageBytes: 100 << 20,
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(preflight.Plans) != 43 || len(preflight.Failures) != 1 ||
		preflight.Failures[0].Code != "PACKAGE_TOO_LARGE" {
		t.Fatalf("unexpected package preflight: plans=%d failures=%+v",
			len(preflight.Plans), preflight.Failures)
	}
	tempRoot := t.TempDir()
	limits := AcquisitionLimits{
		MaxWorkspaceBytes: 64 << 20, MaxArchiveBytes: 100 << 20, MinFreeBytes: 16 << 20,
	}
	var totalArchiveBytes, largestWorkspaceAfterAcquisition int64
	for index, plan := range preflight.Plans {
		func() {
			artifact, err := AcquirePackage(ctx, "openai", "skills", fixedPublicCommitSHA,
				tempRoot, plan, limits)
			if err != nil {
				t.Fatalf("acquire package %d/%d %s: %v",
					index+1, len(preflight.Plans), plan.Root, err)
			}
			defer func() {
				if err := artifact.Close(); err != nil {
					t.Fatalf("clean package %s: %v", plan.Root, err)
				}
			}()
			archive, err := zip.OpenReader(artifact.Path)
			if err != nil {
				t.Fatalf("open package %s: %v", plan.Root, err)
			}
			defer archive.Close()
			want := map[string]struct{}{evidenceManifestPath: {}}
			for _, file := range plan.Files {
				want[file.RelativePath] = struct{}{}
			}
			for _, evidence := range plan.Evidence {
				if evidence.Bundled {
					want[evidenceEntryPrefix+evidence.RepositoryPath] = struct{}{}
				}
			}
			if len(archive.File) != len(want) {
				t.Fatalf("package %s has %d ZIP entries, expected %d",
					plan.Root, len(archive.File), len(want))
			}
			for _, file := range archive.File {
				if _, expected := want[file.Name]; !expected {
					t.Fatalf("package %s contains unexpected ZIP path %s", plan.Root, file.Name)
				}
				delete(want, file.Name)
			}
			if len(want) != 0 {
				t.Fatalf("package %s omitted %d planned ZIP entries", plan.Root, len(want))
			}
			stored, err := os.Open(artifact.Path)
			if err != nil {
				t.Fatalf("read package %s: %v", plan.Root, err)
			}
			digest := sha256.New()
			storedBytes, copyErr := io.Copy(digest, stored)
			closeErr := stored.Close()
			if copyErr != nil || closeErr != nil || storedBytes != artifact.Size ||
				hex.EncodeToString(digest.Sum(nil)) != artifact.SHA256 {
				t.Fatalf("package %s archive size or digest mismatch: copy=%v close=%v",
					plan.Root, copyErr, closeErr)
			}
			workspaceBytes, err := directoryBytes(tempRoot)
			if err != nil || workspaceBytes > limits.MaxWorkspaceBytes {
				t.Fatalf("package %s exceeded workspace budget: bytes=%d error=%v",
					plan.Root, workspaceBytes, err)
			}
			largestWorkspaceAfterAcquisition = max(largestWorkspaceAfterAcquisition, workspaceBytes)
			totalArchiveBytes += artifact.Size
		}()
		remaining, err := os.ReadDir(tempRoot)
		if err != nil || len(remaining) != 0 {
			t.Fatalf("package %d/%d left a workspace: entries=%d error=%v",
				index+1, len(preflight.Plans), len(remaining), err)
		}
		if (index+1)%10 == 0 {
			t.Logf("sequential packages completed=%d/%d", index+1, len(preflight.Plans))
		}
	}
	t.Logf("fixed public repository acquisition: api_operations=%d eligible=%d rejected=%d git_acquisitions=%d total_archive_bytes=%d largest_workspace_after_acquisition=%d elapsed=%s",
		apiOperations, len(preflight.Plans), len(preflight.Failures), len(preflight.Plans),
		totalArchiveBytes, largestWorkspaceAfterAcquisition, time.Since(started))
}
