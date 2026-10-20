package github

import (
	"context"
	"io"
	"net/http"
	"strings"
	"testing"
)

func TestFixedCommitTreeRejectsMismatchedResponses(t *testing.T) {
	const commitSHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	const treeSHA = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
	client := NewClient("")
	client.http.Transport = roundTripFunc(func(request *http.Request) (*http.Response, error) {
		var body string
		switch request.URL.Path {
		case "/repos/owner/repo/git/commits/" + commitSHA:
			body = `{"sha":"cccccccccccccccccccccccccccccccccccccccc","tree":{"sha":"` + treeSHA + `"}}`
		case "/repos/owner/repo/git/trees/" + treeSHA:
			body = `{"sha":"dddddddddddddddddddddddddddddddddddddddd","truncated":false,"tree":[]}`
		default:
			t.Fatalf("unexpected GitHub tree request: %s", request.URL.String())
		}
		return &http.Response{StatusCode: http.StatusOK, Header: make(http.Header),
			Body: io.NopCloser(strings.NewReader(body)), Request: request}, nil
	})
	if _, _, err := client.GetCommitTreeSHA(context.Background(), "owner", "repo", commitSHA); err == nil {
		t.Fatal("commit response with a different SHA must be rejected")
	}
	if _, _, err := client.GetTree(context.Background(), "owner", "repo", treeSHA, false); err == nil {
		t.Fatal("tree response with a different SHA must be rejected")
	}
}
