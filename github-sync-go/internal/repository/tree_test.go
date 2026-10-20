package repository

import (
	"context"
	"errors"
	"reflect"
	"testing"

	"github.com/iflytek/skillhub/github-sync-go/internal/github"
)

const (
	rootTreeSHA   = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	skillsTreeSHA = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
	goodTreeSHA   = "cccccccccccccccccccccccccccccccccccccccc"
	badTreeSHA    = "dddddddddddddddddddddddddddddddddddddddd"
	skillBlobSHA  = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
)

type fixtureTreeReader struct {
	trees  map[string]github.Tree
	errors map[string]error
	calls  []string
}

func (reader *fixtureTreeReader) GetTree(_ context.Context, _, _, sha string,
	recursive bool) (github.Tree, github.Quota, error) {
	key := sha
	if recursive {
		key += ":recursive"
	}
	reader.calls = append(reader.calls, key)
	if err := reader.errors[key]; err != nil {
		return github.Tree{}, github.Quota{}, err
	}
	return reader.trees[key], github.Quota{}, nil
}

func TestEnumerateTreeContinuesCompleteSiblingAfterTruncation(t *testing.T) {
	reader := &fixtureTreeReader{
		trees: map[string]github.Tree{
			rootTreeSHA + ":recursive": {SHA: rootTreeSHA, Truncated: true},
			rootTreeSHA: {SHA: rootTreeSHA, Entries: []github.TreeEntry{
				{Path: "skills", Type: "tree", SHA: skillsTreeSHA},
			}},
			skillsTreeSHA: {SHA: skillsTreeSHA, Entries: []github.TreeEntry{
				{Path: "good", Type: "tree", SHA: goodTreeSHA},
				{Path: "bad", Type: "tree", SHA: badTreeSHA},
			}},
			goodTreeSHA: {SHA: goodTreeSHA, Entries: []github.TreeEntry{
				{Path: "SKILL.md", Type: "blob", Mode: "100644", SHA: skillBlobSHA, Size: 12},
			}},
		},
		errors: map[string]error{badTreeSHA: errors.New("temporary tree failure")},
	}
	snapshot, err := EnumerateTree(context.Background(), reader, "owner", "repo", rootTreeSHA,
		TreeLimits{MaxTreeRequests: 8, MaxEntries: 20})
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(snapshot.IncompleteSubtrees, []string{"skills/bad"}) ||
		!reflect.DeepEqual(reader.calls, []string{
			rootTreeSHA + ":recursive", rootTreeSHA, skillsTreeSHA, goodTreeSHA, badTreeSHA,
		}) {
		t.Fatalf("incorrect partial tree coverage: %+v, calls=%v", snapshot, reader.calls)
	}
	roots := RootsFromSnapshot(snapshot, nil)
	if !reflect.DeepEqual(roots.Paths, []string{"skills/good"}) {
		t.Fatalf("complete sibling root was lost: %+v", roots)
	}
	preflight, err := PreflightPackages(snapshot, roots.Paths, PackageLimits{
		MaxFiles: 500, MaxSingleFileBytes: 10 << 20, MaxPackageBytes: 100 << 20,
	})
	if err != nil || len(preflight.Plans) != 1 || preflight.Plans[0].Root != "skills/good" ||
		len(preflight.Failures) != 0 {
		t.Fatalf("complete sibling was blocked: %+v, error=%v", preflight, err)
	}
}

func TestEnumerateTreeFallsBackWhenRecursiveResponseIsTooLarge(t *testing.T) {
	reader := &fixtureTreeReader{
		trees: map[string]github.Tree{rootTreeSHA: {
			SHA: rootTreeSHA, Entries: []github.TreeEntry{
				{Path: "SKILL.md", Type: "blob", Mode: "100644", SHA: skillBlobSHA, Size: 12},
			},
		}},
		errors: map[string]error{rootTreeSHA + ":recursive": github.ErrResponseTooLarge},
	}
	snapshot, err := EnumerateTree(context.Background(), reader, "owner", "repo", rootTreeSHA,
		TreeLimits{MaxTreeRequests: 2, MaxEntries: 2})
	if err != nil || len(snapshot.IncompleteSubtrees) != 0 ||
		!reflect.DeepEqual(RootsFromSnapshot(snapshot, nil).Paths, []string{"."}) {
		t.Fatalf("oversized recursive tree did not fall back: %+v, error=%v", snapshot, err)
	}
}
