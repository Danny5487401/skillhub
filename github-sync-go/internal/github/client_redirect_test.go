package github

import (
	"context"
	"io"
	"net/http"
	"strings"
	"testing"
)

type roundTripFunc func(*http.Request) (*http.Response, error)

func (fn roundTripFunc) RoundTrip(request *http.Request) (*http.Response, error) {
	return fn(request)
}

func TestGetRepositoryFollowsSameHostRenameRedirect(t *testing.T) {
	client := NewClient("fixture-token")
	requests := 0
	client.http.Transport = roundTripFunc(func(request *http.Request) (*http.Response, error) {
		requests++
		if request.Header.Get("Authorization") != "Bearer fixture-token" {
			t.Fatal("GitHub credential missing from same-host request")
		}
		switch request.URL.Path {
		case "/repos/old-owner/old-name":
			return &http.Response{StatusCode: http.StatusMovedPermanently,
				Header: http.Header{"Location": {"https://api.github.com/repositories/42"}},
				Body:   io.NopCloser(strings.NewReader("")), Request: request}, nil
		case "/repositories/42":
			return &http.Response{StatusCode: http.StatusOK, Header: make(http.Header),
				Body:    io.NopCloser(strings.NewReader(`{"id":42,"name":"new-name","owner":{"login":"new-owner"},"default_branch":"main","private":false}`)),
				Request: request}, nil
		default:
			t.Fatalf("unexpected GitHub path %s", request.URL.Path)
			return nil, nil
		}
	})
	repository, _, err := client.GetRepository(context.Background(), "old-owner", "old-name")
	if err != nil || repository.ID != 42 || repository.Owner.Login != "new-owner" ||
		repository.Name != "new-name" || requests != 2 {
		t.Fatalf("unexpected renamed repository result: %+v, requests=%d, error=%v", repository, requests, err)
	}
}

func TestGetRepositoryRejectsCrossHostRedirect(t *testing.T) {
	client := NewClient("fixture-token")
	requests := 0
	client.http.Transport = roundTripFunc(func(request *http.Request) (*http.Response, error) {
		requests++
		if request.URL.Host != "api.github.com" {
			t.Fatal("GitHub credential request escaped API host")
		}
		return &http.Response{StatusCode: http.StatusMovedPermanently,
			Header: http.Header{"Location": {"https://example.invalid/repos/new-owner/new-name"}},
			Body:   io.NopCloser(strings.NewReader("")), Request: request}, nil
	})
	_, _, err := client.GetRepository(context.Background(), "old-owner", "old-name")
	if err == nil || requests != 1 {
		t.Fatalf("cross-host redirect must fail before a second request: requests=%d, error=%v", requests, err)
	}
}
