package github

import (
	"context"
	"errors"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"
)

func TestOfficialQueryHalfOpenUTCAndScopes(t *testing.T) {
	start := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	for _, scope := range []OfficialQueryScope{OfficialQueryScope("NAME_DESCRIPTION_TOPICS"), OfficialQueryScope("README")} {
		t.Run(string(scope), func(t *testing.T) {
			q, err := CompileOfficialRepositoryQuery(OfficialRepositoryQuery{Kind: "KEYWORD", Value: "agent skills", Scope: scope}, OfficialRepositoryRange{Since: start, Until: start.Add(2 * time.Second)}, start.Add(time.Hour))
			if err != nil || !strings.Contains(q, "00:00:01+00:00") || strings.Contains(q, "00:00:02+00:00") {
				t.Fatalf("half-open query %q, %v", q, err)
			}
			if scope == "README" && !strings.Contains(q, "in:readme") {
				t.Fatalf("missing README qualifier %q", q)
			}
		})
	}
	for _, kind := range []string{"fractional", "partition", "cutoff", "injection"} {
		t.Run(kind, func(t *testing.T) {
			window := OfficialRepositoryRange{Since: start, Until: start.Add(time.Second)}
			cutoff := start.Add(time.Hour)
			value := "skills"
			switch kind {
			case "fractional":
				window.Since = start.Add(time.Nanosecond)
			case "partition":
				window.Partition = "stars:>1"
			case "cutoff":
				cutoff = start
			case "injection":
				value = "skills user:someone"
			}
			if _, err := CompileOfficialRepositoryQuery(OfficialRepositoryQuery{Kind: "KEYWORD", Value: value, Scope: "NAME_DESCRIPTION_TOPICS"}, window, cutoff); err == nil {
				t.Fatal("invalid official query accepted")
			}
		})
	}
}
func TestOfficialOwnerStableIDResponseValidation(t *testing.T) {
	for _, body := range []string{`{"id":42,"login":"renamed","type":"User"}`, `{"id":42,"login":"renamed","type":"Organization"}`, `{"id":43,"login":"renamed","type":"User"}`, `{"id":42,"login":"bad/name","type":"User"}`, `{"id":42,"login":"renamed","type":"Bot"}`} {
		t.Run(body, func(t *testing.T) {
			c := NewClient("")
			c.http.Transport = roundTripFunc(func(r *http.Request) (*http.Response, error) {
				if r.URL.Path != "/user/42" {
					t.Errorf("lookup not by stable ID: %s", r.URL.Path)
				}
				return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(body)), Request: r}, nil
			})
			owner, _, err := c.GetOwnerByID(context.Background(), 42)
			valid := strings.Contains(body, `"id":42`) && !strings.Contains(body, "bad/name") && !strings.Contains(body, "Bot")
			if (err == nil) != valid {
				t.Fatalf("owner %+v err %v", owner, err)
			}
		})
	}
}
func TestOfficialRESTPolicyStopsRequestsAfterObservationFailure(t *testing.T) {
	c := NewClient("")
	calls, observations := 0, 0
	stopped := false
	c.http.Transport = roundTripFunc(func(r *http.Request) (*http.Response, error) {
		calls++
		return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(`{"id":42,"login":"owner","type":"User"}`)), Request: r}, nil
	})
	c.SetRequestPolicy(func(context.Context) error {
		if stopped {
			return errors.New("cooldown")
		}
		return nil
	}, func(_ context.Context, q Quota, _ error) error {
		observations++
		if q.Remaining != -1 {
			t.Error("missing headers produced known remaining")
		}
		stopped = true
		return errors.New("persistence failed")
	})
	for i := 0; i < 2; i++ {
		if _, _, err := c.GetOwnerByID(context.Background(), 42); err == nil {
			t.Fatal("policy error was ignored")
		}
	}
	if calls != 1 || observations != 1 {
		t.Fatalf("calls=%d observations=%d", calls, observations)
	}
}
