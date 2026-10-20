package worker

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/iflytek/skillhub/github-sync-go/internal/controlplane"
	"github.com/iflytek/skillhub/github-sync-go/internal/github"
)

type revisionFixture struct {
	repositoryTreeFixture
	headCalls  int
	treeCommit string
}

func (f *revisionFixture) GetHead(context.Context, github.Repository) (string, github.Quota, error) {
	f.headCalls++
	return strings.Repeat("e", 40), github.Quota{}, nil
}
func (f *revisionFixture) GetCommitTreeSHA(_ context.Context, _, _, commit string) (string, github.Quota, error) {
	f.treeCommit = commit
	return strings.Repeat("b", 40), github.Quota{}, nil
}

func TestFullRepositoryResolvesCurrentHeadUnlessResumingPinnedCommit(t *testing.T) {
	for _, pinned := range []bool{false, true} {
		name := "new-task-current-head"
		if pinned {
			name = "resume-fixed-commit"
		}
		t.Run(name, func(t *testing.T) {
			var reported string
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if strings.HasSuffix(r.URL.Path, "/repository-metadata") {
					w.WriteHeader(http.StatusOK)
					return
				}
				if strings.HasSuffix(r.URL.Path, "/complete") {
					var body map[string]any
					if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
						t.Error(err)
					}
					reported, _ = body["observedCommitSha"].(string)
					w.Header().Set("Content-Type", "application/json")
					_, _ = w.Write([]byte(`{"status":"ACQUISITION_COMPLETE"}`))
					return
				}
				t.Errorf("unexpected request %s", r.URL.Path)
				w.WriteHeader(500)
			}))
			defer server.Close()
			control, err := controlplane.NewClient(server.URL, strings.Repeat("t", 32))
			if err != nil {
				t.Fatal(err)
			}
			task := &controlplane.ClaimedTask{TaskID: "fixture", Kind: "REPOSITORY_SYNC", Mode: "FULL", Lease: controlplane.LeaseProof{Generation: 1, Token: strings.Repeat("a", 64)}, Repository: &controlplane.RepositorySnapshot{RepositoryID: 42, Owner: "fixture", Name: "repo"}, MaxFilesPerPackage: 100, MaxPackageBytes: 100 << 20, MaxSingleFileBytes: 10 << 20, MaxUploadBytes: 100 << 20}
			want := strings.Repeat("e", 40)
			wantHead := 1
			if pinned {
				want = strings.Repeat("c", 40)
				task.Repository.FixedCommitSHA = want
				wantHead = 0
			}
			fixture := &revisionFixture{}
			err = processRepository(context.Background(), control, task, RepositoryLimits{TempRoot: t.TempDir(), MaxTreeRequests: 5, MaxTreeEntries: 20, MaxWorkspaceBytes: 32 << 20}, fixture)
			if err != nil {
				t.Fatal(err)
			}
			if fixture.headCalls != wantHead || fixture.treeCommit != want || reported != want {
				t.Fatalf("head calls=%d tree commit=%s reported=%s want=%s", fixture.headCalls, fixture.treeCommit, reported, want)
			}
		})
	}
}
