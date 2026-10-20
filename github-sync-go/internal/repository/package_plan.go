package repository

import (
	"errors"
	"regexp"
	"sort"
	"strings"

	"github.com/iflytek/skillhub/github-sync-go/internal/github"
)

type PackageLimits struct {
	MaxFiles           int
	MaxSingleFileBytes int64
	MaxPackageBytes    int64
}

type PackageFile struct {
	RepositoryPath string
	RelativePath   string
	SHA            string
	Size           int64
}

type PackagePlan struct {
	Root       string
	Files      []PackageFile
	Evidence   []RootEvidenceFile
	TotalBytes int64
}

// RootEvidenceFile is repository-level license or notice metadata at the fixed commit.
// Large files keep their Git blob digest in the manifest without being downloaded.
type RootEvidenceFile struct {
	RepositoryPath string `json:"path"`
	SHA            string `json:"gitSha"`
	Size           int64  `json:"size"`
	Bundled        bool   `json:"bundled"`
}

const (
	maxRootEvidenceFiles  = 16
	maxEvidenceFileBytes  = 64 << 10
	maxEvidenceTotalBytes = 256 << 10
)

var rootEvidenceName = regexp.MustCompile(`(?i)^(license|licence|copying|notice|copyright)(?:[-_.][a-z0-9][a-z0-9._-]*)?$`)

type PackageFailure struct {
	Root      string
	Code      string
	Retryable bool
}

type PackagePreflight struct {
	Plans    []PackagePlan
	Failures []PackageFailure
}

type packageAccumulator struct {
	plan       PackagePlan
	seen       map[string]struct{}
	hasSkillMD bool
	failure    *PackageFailure
}

// PreflightPackages assigns every blob to its nearest Skill root. Ignored
// candidate directories are still counted when they belong to a parent Skill.
// An incomplete range never produces a package plan for an affected root.
func PreflightPackages(snapshot TreeSnapshot, roots []string, limits PackageLimits) (PackagePreflight, error) {
	if limits.MaxFiles < 1 || limits.MaxSingleFileBytes < 1 || limits.MaxPackageBytes < 1 {
		return PackagePreflight{}, errors.New("package limits must be positive")
	}
	packages := make(map[string]*packageAccumulator, len(roots))
	for _, root := range roots {
		if root != "." && !validPath(root) {
			return PackagePreflight{}, errors.New("invalid Skill root")
		}
		if _, exists := packages[root]; exists {
			return PackagePreflight{}, errors.New("duplicate Skill root")
		}
		packages[root] = &packageAccumulator{
			plan: PackagePlan{Root: root}, seen: make(map[string]struct{}),
		}
	}
	for root, accumulator := range packages {
		for _, subtree := range snapshot.IncompleteSubtrees {
			if subtree != "." && !validPath(subtree) {
				return PackagePreflight{}, errors.New("invalid incomplete subtree")
			}
			if subtree == "." || root == "." || subtree == root ||
				strings.HasPrefix(subtree, root+"/") || strings.HasPrefix(root, subtree+"/") {
				accumulator.failure = &PackageFailure{Root: root, Code: "TREE_INCOMPLETE", Retryable: true}
				break
			}
		}
	}
	entries := append([]github.TreeEntry(nil), snapshot.Entries...)
	sort.Slice(entries, func(i, j int) bool { return entries[i].Path < entries[j].Path })
	evidence, evidenceFailure := planRootEvidence(entries)
	for _, entry := range entries {
		if !validPath(entry.Path) {
			return PackagePreflight{}, errors.New("invalid repository tree path")
		}
		root := nearestSkillRoot(entry.Path, packages)
		if root == "" {
			continue
		}
		accumulator := packages[root]
		if accumulator.failure != nil || entry.Type == "tree" {
			continue
		}
		relativePath := entry.Path
		if root != "." {
			relativePath = strings.TrimPrefix(entry.Path, root+"/")
		}
		if _, duplicate := accumulator.seen[relativePath]; duplicate {
			accumulator.failure = &PackageFailure{Root: root, Code: "DUPLICATE_PATH"}
			continue
		}
		accumulator.seen[relativePath] = struct{}{}
		if relativePath == evidenceManifestPath || strings.HasPrefix(relativePath, evidenceEntryPrefix) {
			accumulator.failure = &PackageFailure{Root: root, Code: "RESERVED_EVIDENCE_PATH"}
			continue
		}
		switch {
		case entry.Type == "commit" || entry.Mode == "160000":
			accumulator.failure = &PackageFailure{Root: root, Code: "SUBMODULE"}
		case entry.Type == "blob" && entry.Mode == "120000":
			accumulator.failure = &PackageFailure{Root: root, Code: "SYMLINK"}
		case entry.Type != "blob" || (entry.Mode != "100644" && entry.Mode != "100755"):
			accumulator.failure = &PackageFailure{Root: root, Code: "UNSUPPORTED_ENTRY"}
		case entry.Size < 0 || !validGitSHA(entry.SHA):
			accumulator.failure = &PackageFailure{Root: root, Code: "INVALID_BLOB_METADATA", Retryable: true}
		case entry.Size > limits.MaxSingleFileBytes ||
			entry.Size > limits.MaxPackageBytes-accumulator.plan.TotalBytes ||
			len(accumulator.plan.Files) >= limits.MaxFiles:
			accumulator.failure = &PackageFailure{Root: root, Code: "PACKAGE_TOO_LARGE"}
		default:
			accumulator.plan.Files = append(accumulator.plan.Files, PackageFile{
				RepositoryPath: entry.Path, RelativePath: relativePath,
				SHA: entry.SHA, Size: entry.Size,
			})
			accumulator.plan.TotalBytes += entry.Size
			if relativePath == "SKILL.md" {
				accumulator.hasSkillMD = true
			}
		}
	}
	result := PackagePreflight{}
	for _, root := range sortedAccumulatorRoots(packages) {
		accumulator := packages[root]
		if accumulator.failure != nil {
			result.Failures = append(result.Failures, *accumulator.failure)
		} else if evidenceFailure != "" {
			result.Failures = append(result.Failures,
				PackageFailure{Root: root, Code: evidenceFailure})
		} else if !accumulator.hasSkillMD {
			result.Failures = append(result.Failures,
				PackageFailure{Root: root, Code: "MISSING_SKILL_MD"})
		} else {
			accumulator.plan.Evidence = append([]RootEvidenceFile(nil), evidence...)
			result.Plans = append(result.Plans, accumulator.plan)
		}
	}
	return result, nil
}

func planRootEvidence(entries []github.TreeEntry) ([]RootEvidenceFile, string) {
	result := make([]RootEvidenceFile, 0)
	var bundledBytes int64
	for _, entry := range entries {
		if strings.Contains(entry.Path, "/") || !rootEvidenceName.MatchString(entry.Path) {
			continue
		}
		if len(result) >= maxRootEvidenceFiles {
			return nil, "TOO_MANY_ROOT_EVIDENCE_FILES"
		}
		if entry.Type != "blob" || (entry.Mode != "100644" && entry.Mode != "100755") ||
			entry.Size < 0 || !validGitSHA(entry.SHA) {
			return nil, "UNSAFE_ROOT_EVIDENCE"
		}
		bundled := entry.Size <= maxEvidenceFileBytes &&
			entry.Size <= maxEvidenceTotalBytes-bundledBytes
		if bundled {
			bundledBytes += entry.Size
		}
		result = append(result, RootEvidenceFile{
			RepositoryPath: entry.Path, SHA: entry.SHA,
			Size: entry.Size, Bundled: bundled,
		})
	}
	return result, ""
}

func nearestSkillRoot(path string, packages map[string]*packageAccumulator) string {
	for directory := parent(path); ; directory = parent(directory) {
		if _, exists := packages[directory]; exists {
			return directory
		}
		if directory == "." {
			return ""
		}
	}
}

func sortedAccumulatorRoots(packages map[string]*packageAccumulator) []string {
	roots := make([]string, 0, len(packages))
	for root := range packages {
		roots = append(roots, root)
	}
	sort.Strings(roots)
	return roots
}

func validGitSHA(value string) bool {
	if len(value) != 40 {
		return false
	}
	for _, char := range value {
		if (char < '0' || char > '9') && (char < 'a' || char > 'f') {
			return false
		}
	}
	return true
}
