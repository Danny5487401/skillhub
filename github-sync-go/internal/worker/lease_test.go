package worker

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/iflytek/skillhub/github-sync-go/internal/controlplane"
)

func TestLeasedTaskStopsWorkWhenJavaRenewalFails(t *testing.T) {
	var renewCalls atomic.Int32
	var otherCalls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		if request.URL.Path == "/internal/github-sync/v1/tasks/fixture/renew" {
			renewCalls.Add(1)
			response.Header().Set("Content-Type", "application/json")
			response.WriteHeader(http.StatusServiceUnavailable)
			_, _ = response.Write([]byte(`{"code":"SERVICE_UNAVAILABLE"}`))
			return
		}
		otherCalls.Add(1)
		response.WriteHeader(http.StatusNotFound)
	}))
	defer server.Close()
	client, err := controlplane.NewClient(server.URL+"/internal/github-sync/v1",
		strings.Repeat("t", 32))
	if err != nil {
		t.Fatal(err)
	}
	task := &controlplane.ClaimedTask{
		TaskID: "fixture", Lease: controlplane.LeaseProof{Generation: 1, Token: "lease"},
		LeaseExpiresAt: time.Now().Add(3 * time.Second),
	}
	workReleased := false
	err = executeLeasedTask(context.Background(), client, task, func(ctx context.Context) error {
		defer func() { workReleased = true }()
		<-ctx.Done()
		return ctx.Err()
	})
	var apiError *controlplane.APIError
	if !errors.As(err, &apiError) || apiError.Code != "SERVICE_UNAVAILABLE" {
		t.Fatalf("renewal failure was not returned: %v", err)
	}
	if !workReleased || renewCalls.Load() != 1 || otherCalls.Load() != 0 {
		t.Fatalf("worker did not release work after failed renewal: released=%t renew=%d other=%d",
			workReleased, renewCalls.Load(), otherCalls.Load())
	}
}
