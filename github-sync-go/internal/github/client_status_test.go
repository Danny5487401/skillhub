package github

import (
	"context"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestRepositoryStatusClassificationAndQuota(t *testing.T) {
	reset := time.Now().Add(5 * time.Minute).Truncate(time.Second)
	tests := []struct {
		name          string
		status        int
		message       string
		headers       http.Header
		code          string
		wantRetryTime time.Time
	}{
		{"invalid credential", http.StatusUnauthorized, `{"message":"Bad credentials"}`,
			nil, "AUTH_INVALID", time.Time{}},
		{"private or missing", http.StatusNotFound, `{"message":"Not Found"}`,
			nil, "SOURCE_UNREADABLE", time.Time{}},
		{"forbidden without rate limit", http.StatusForbidden, `{"message":"Resource not accessible"}`,
			nil, "SOURCE_UNREADABLE", time.Time{}},
		{"primary rate limit", http.StatusForbidden, `{"message":"API rate limit exceeded"}`,
			http.Header{"X-Ratelimit-Remaining": {"0"},
				"X-Ratelimit-Reset":    {strconv.FormatInt(reset.Unix(), 10)},
				"X-Ratelimit-Resource": {"core"}}, "GITHUB_RATE_LIMITED", reset},
		{"server failure", http.StatusInternalServerError, `{"message":"Server error"}`,
			nil, "RETRYABLE_FAILURE", time.Time{}},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			client := NewClient("fixture-token")
			client.http.Transport = roundTripFunc(func(request *http.Request) (*http.Response, error) {
				if request.Header.Get("Authorization") != "Bearer fixture-token" {
					t.Fatal("GitHub credential was not sent")
				}
				return &http.Response{StatusCode: test.status, Header: test.headers,
					Body: io.NopCloser(strings.NewReader(test.message)), Request: request}, nil
			})
			_, quota, err := client.GetRepository(context.Background(), "public-owner", "skills")
			var apiError *APIError
			if !errors.As(err, &apiError) || apiError.Code != test.code {
				t.Fatalf("status %d: expected %s, got %v", test.status, test.code, err)
			}
			if !test.wantRetryTime.IsZero() &&
				(!apiError.RetryAt.Equal(test.wantRetryTime) || quota.Remaining != 0 ||
					quota.Resource != "core") {
				t.Fatalf("rate limit headers were not preserved: error=%+v quota=%+v", apiError, quota)
			}
		})
	}
}

func TestSecondaryRateLimitRetryAfter(t *testing.T) {
	client := NewClient("fixture-token")
	client.http.Transport = roundTripFunc(func(request *http.Request) (*http.Response, error) {
		return &http.Response{StatusCode: http.StatusTooManyRequests,
			Header:  http.Header{"Retry-After": {"120"}},
			Body:    io.NopCloser(strings.NewReader(`{"message":"You have exceeded a secondary rate limit"}`)),
			Request: request}, nil
	})
	before := time.Now()
	_, _, err := client.GetRepository(context.Background(), "public-owner", "skills")
	var apiError *APIError
	if !errors.As(err, &apiError) || apiError.Code != "GITHUB_RATE_LIMITED" ||
		apiError.RetryAt.Before(before.Add(120*time.Second)) ||
		apiError.RetryAt.After(time.Now().Add(121*time.Second)) {
		t.Fatalf("secondary rate limit did not honor Retry-After: %v", err)
	}
}

func TestAnonymousRepositoryRequestHasNoAuthorizationHeader(t *testing.T) {
	client := NewClient("")
	client.http.Transport = roundTripFunc(func(request *http.Request) (*http.Response, error) {
		if request.Header.Get("Authorization") != "" {
			t.Fatal("anonymous GitHub request contained an authorization header")
		}
		return &http.Response{StatusCode: http.StatusOK, Header: make(http.Header),
			Body:    io.NopCloser(strings.NewReader(`{"id":42,"name":"skills","owner":{"login":"public-owner"},"default_branch":"main","private":false}`)),
			Request: request}, nil
	})
	repository, _, err := client.GetRepository(context.Background(), "public-owner", "skills")
	if err != nil || repository.ID != 42 {
		t.Fatalf("anonymous public repository lookup failed: repository=%+v error=%v", repository, err)
	}
}
