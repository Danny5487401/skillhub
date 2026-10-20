package repository

import (
	"context"
	"errors"
	"sort"
	"strings"
	"unicode"

	"github.com/iflytek/skillhub/github-sync-go/internal/github"
)

var defaultIgnoredSegments = map[string]struct{}{
	".git": {}, "node_modules": {}, ".venv": {}, "venv": {}, "dist": {},
	"build": {}, "target": {}, "__pycache__": {}, "test": {}, "tests": {},
}

type TreeReader interface {
	GetTree(context.Context, string, string, string, bool) (github.Tree, github.Quota, error)
}

type TreeLimits struct {
	MaxTreeRequests int
	MaxEntries      int
}

type SkillRoots struct {
	Paths              []string
	IncompleteSubtrees []string
	EntriesExamined    int
}

type TreeSnapshot struct {
	Entries            []github.TreeEntry
	IncompleteSubtrees []string
	EntriesExamined    int
}

type pendingTree struct {
	SHA    string
	Prefix string
}

// EnumerateTree retains file metadata for package preflight. Truncated recursive
// trees are rewalked one subtree at a time; failed siblings are reported explicitly.
func EnumerateTree(ctx context.Context, reader TreeReader, owner, name, rootTreeSHA string,
	limits TreeLimits) (TreeSnapshot, error) {
	if limits.MaxTreeRequests < 1 || limits.MaxEntries < 1 {
		return TreeSnapshot{}, errors.New("tree request and entry limits must be positive")
	}
	root, _, err := reader.GetTree(ctx, owner, name, rootTreeSHA, true)
	if err != nil && !errors.Is(err, github.ErrResponseTooLarge) {
		return TreeSnapshot{}, err
	}
	if err == nil && !root.Truncated && len(root.Entries) <= limits.MaxEntries {
		result := TreeSnapshot{EntriesExamined: len(root.Entries)}
		for _, entry := range root.Entries {
			if !validPath(entry.Path) {
				result.IncompleteSubtrees = []string{"."}
				continue
			}
			result.Entries = append(result.Entries, entry)
		}
		return result, nil
	}
	result := TreeSnapshot{}
	incomplete := make(map[string]struct{})
	queue := []pendingTree{{SHA: rootTreeSHA, Prefix: "."}}
	requests := 1
	for len(queue) > 0 {
		if err := ctx.Err(); err != nil {
			for _, left := range queue {
				incomplete[left.Prefix] = struct{}{}
			}
			result.IncompleteSubtrees = sortedKeys(incomplete)
			return result, err
		}
		current := queue[0]
		queue = queue[1:]
		if requests >= limits.MaxTreeRequests || result.EntriesExamined >= limits.MaxEntries {
			incomplete[current.Prefix] = struct{}{}
			for _, left := range queue {
				incomplete[left.Prefix] = struct{}{}
			}
			break
		}
		tree, _, treeErr := reader.GetTree(ctx, owner, name, current.SHA, false)
		requests++
		if apiError, ok := treeErr.(*github.APIError); ok &&
			(apiError.Code == "GITHUB_RATE_LIMITED" || apiError.Code == "AUTH_INVALID") {
			incomplete[current.Prefix] = struct{}{}
			for _, left := range queue {
				incomplete[left.Prefix] = struct{}{}
			}
			result.IncompleteSubtrees = sortedKeys(incomplete)
			return result, treeErr
		}
		if treeErr != nil || tree.Truncated {
			incomplete[current.Prefix] = struct{}{}
			continue
		}
		if len(tree.Entries) > limits.MaxEntries-result.EntriesExamined {
			incomplete[current.Prefix] = struct{}{}
			continue
		}
		for _, entry := range tree.Entries {
			path := entry.Path
			if current.Prefix != "." {
				path = current.Prefix + "/" + path
			}
			result.EntriesExamined++
			if !validPath(path) {
				incomplete[current.Prefix] = struct{}{}
				continue
			}
			entry.Path = path
			result.Entries = append(result.Entries, entry)
			if entry.Type == "tree" {
				queue = append(queue, pendingTree{SHA: entry.SHA, Prefix: path})
			}
		}
	}
	result.IncompleteSubtrees = sortedKeys(incomplete)
	sort.Slice(result.Entries, func(i, j int) bool {
		return result.Entries[i].Path < result.Entries[j].Path
	})
	return result, nil
}

// DiscoverSkillRoots treats incomplete ranges as unknown. A candidate inside an
// ignored directory is skipped, but that directory's files remain available to
// a parent Skill package.
func DiscoverSkillRoots(ctx context.Context, reader TreeReader, owner, name, rootTreeSHA string,
	limits TreeLimits, addedIgnoredPrefixes []string) (SkillRoots, error) {
	for _, prefix := range addedIgnoredPrefixes {
		if !validPath(prefix) || prefix == "." {
			return SkillRoots{}, errors.New("invalid additional ignored path prefix")
		}
	}
	snapshot, err := EnumerateTree(ctx, reader, owner, name, rootTreeSHA, limits)
	return RootsFromSnapshot(snapshot, addedIgnoredPrefixes), err
}

func RootsFromSnapshot(snapshot TreeSnapshot, addedIgnoredPrefixes []string) SkillRoots {
	result := SkillRoots{
		IncompleteSubtrees: snapshot.IncompleteSubtrees,
		EntriesExamined:    snapshot.EntriesExamined,
	}
	roots := make(map[string]struct{})
	for _, entry := range snapshot.Entries {
		if candidateRoot(entry.Path, entry, addedIgnoredPrefixes) {
			roots[parent(entry.Path)] = struct{}{}
		}
	}
	result.Paths = sortedKeys(roots)
	return result
}

func candidateRoot(path string, entry github.TreeEntry, addedIgnoredPrefixes []string) bool {
	return entry.Type == "blob" && (path == "SKILL.md" || strings.HasSuffix(path, "/SKILL.md")) &&
		!ignoredDirectory(parent(path), addedIgnoredPrefixes)
}

func ignoredDirectory(path string, addedIgnoredPrefixes []string) bool {
	if path == "." {
		return false
	}
	for _, segment := range strings.Split(path, "/") {
		if _, ignored := defaultIgnoredSegments[segment]; ignored {
			return true
		}
	}
	for _, prefix := range addedIgnoredPrefixes {
		if path == prefix || strings.HasPrefix(path, prefix+"/") {
			return true
		}
	}
	return false
}

func validPath(path string) bool {
	if path == "" || path == "." || strings.HasPrefix(path, "/") || strings.HasSuffix(path, "/") ||
		strings.Contains(path, "\\") || strings.IndexFunc(path, unicode.IsControl) >= 0 {
		return false
	}
	for _, segment := range strings.Split(path, "/") {
		if segment == "" || segment == "." || segment == ".." {
			return false
		}
	}
	return true
}

func parent(path string) string {
	if cut := strings.LastIndexByte(path, '/'); cut >= 0 {
		return path[:cut]
	}
	return "."
}

func sortedKeys(values map[string]struct{}) []string {
	result := make([]string, 0, len(values))
	for value := range values {
		result = append(result, value)
	}
	sort.Strings(result)
	return result
}
