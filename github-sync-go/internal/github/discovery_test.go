package github

import (
	"context"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"
)

func TestSearchRepositoriesReportsLimitAndIncompleteCoverage(t *testing.T) {
	client := NewClient("")
	client.http.Transport = roundTripFunc(func(request *http.Request) (*http.Response, error) {
		query := request.URL.Query()
		if request.URL.Path != "/search/repositories" || query.Get("page") != "1" ||
			query.Get("q") != `"agent skills" in:name,description,topics is:public pushed:2026-09-01T00:00:00+00:00..2026-09-02T00:00:00+00:00` {
			t.Fatalf("unexpected search request: %s", request.URL.String())
		}
		return &http.Response{StatusCode: http.StatusOK, Header: make(http.Header),
			Body:    io.NopCloser(strings.NewReader(`{"total_count":1001,"incomplete_results":true,"items":[{"id":1,"name":"skills","owner":{"login":"owner"},"private":false}]}`)),
			Request: request}, nil
	})
	from := time.Date(2026, 9, 1, 0, 0, 0, 0, time.UTC)
	until := from.Add(24 * time.Hour)
	page, _, err := client.SearchRepositories(context.Background(), "KEYWORD", "agent skills", &from, until, 1)
	if err != nil || !page.LimitReached || !page.IncompleteResults || !page.HasMore ||
		len(page.Repositories) != 1 || page.Repositories[0].ID != 1 {
		t.Fatalf("search coverage = %+v, error = %v", page, err)
	}
}

func TestCuratedUserAndOrganizationEnumerateOnlyPublicRepositories(t *testing.T) {
	for _, testcase := range []struct {
		name       string
		ownerType  OwnerType
		listPath   string
		filterType string
	}{
		{name: "user", ownerType: UserOwner, listPath: "/users/public-owner/repos", filterType: "owner"},
		{name: "organization", ownerType: OrganizationOwner,
			listPath: "/orgs/public-owner/repos", filterType: "public"},
	} {
		t.Run(testcase.name, func(t *testing.T) {
			client := NewClient("")
			calls := 0
			client.http.Transport = roundTripFunc(func(request *http.Request) (*http.Response, error) {
				calls++
				body := `{"type":"` + string(testcase.ownerType) + `"}`
				if calls == 1 {
					if request.URL.Path != "/users/public-owner" {
						t.Fatalf("unexpected owner lookup: %s", request.URL.Path)
					}
				} else {
					query := request.URL.Query()
					if request.URL.Path != testcase.listPath || query.Get("type") != testcase.filterType ||
						query.Get("page") != "1" || query.Get("per_page") != "100" {
						t.Fatalf("unexpected repository listing: %s", request.URL.String())
					}
					body = `[{"id":7,"name":"skills","private":false,"owner":{"login":"public-owner"}},` +
						`{"id":8,"name":"hidden","private":true,"owner":{"login":"public-owner"}}]`
				}
				return &http.Response{StatusCode: http.StatusOK, Header: make(http.Header),
					Body: io.NopCloser(strings.NewReader(body)), Request: request}, nil
			})
			ownerType, _, err := client.GetOwnerType(context.Background(), "public-owner")
			if err != nil || ownerType != testcase.ownerType {
				t.Fatalf("owner type = %s, error = %v", ownerType, err)
			}
			page, _, err := client.ListPublicOwnerRepositories(context.Background(),
				"public-owner", ownerType, 1)
			if err != nil || calls != 2 || page.HasMore || len(page.Repositories) != 1 ||
				page.Repositories[0].ID != 7 {
				t.Fatalf("public repository page = %+v, error = %v", page, err)
			}
		})
	}
}
