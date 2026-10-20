package worker

import (
	"context"
	"testing"
	"time"

	"github.com/iflytek/skillhub/github-sync-go/internal/controlplane"
	"github.com/iflytek/skillhub/github-sync-go/internal/github"
)

func TestOfficialPrimaryCooldownIsCredentialAndResourceScoped(t *testing.T) {
	h := newOfficialHarness(t, "REPOSITORY_METADATA")
	guard := func(token, resource string) *officialQuotaGuard {
		task := officialTask(h.channel)
		if token != "" {
			task.Credential = &controlplane.GithubCredential{CredentialID: "same-slot", Token: token}
		}
		return &officialQuotaGuard{control: h.control, task: task, resource: resource}
	}
	a := guard("fixture-token-a", "SEARCH")
	if err := a.after(context.Background(), github.Quota{Resource: "SEARCH", Remaining: 0, ResetAt: time.Now().Add(time.Hour)}, nil); err == nil {
		t.Fatal("exhausted task should stop")
	}
	for _, tc := range []struct {
		name, token, resource string
		blocked               bool
	}{
		{"same credential", "fixture-token-a", "SEARCH", true},
		{"other resource", "fixture-token-a", "CORE", false},
		{"replacement credential", "fixture-token-b", "SEARCH", false},
		{"anonymous", "", "SEARCH", false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if blocked := guard(tc.token, tc.resource).before(context.Background()) != nil; blocked != tc.blocked {
				t.Fatalf("blocked=%t want %t", blocked, tc.blocked)
			}
		})
	}
	anonymous := guard("", "SEARCH")
	_ = anonymous.after(context.Background(), github.Quota{Resource: "SEARCH", Remaining: 0, ResetAt: time.Now().Add(time.Hour)}, nil)
	if guard("", "SEARCH").before(context.Background()) == nil {
		t.Fatal("anonymous exhaustion not shared")
	}
	if guard("fixture-token-b", "SEARCH").before(context.Background()) != nil {
		t.Fatal("anonymous exhaustion blocked authenticated credential")
	}
	secondary := guard("fixture-token-b", "SEARCH")
	_ = secondary.after(context.Background(), github.Quota{Remaining: -1}, &github.APIError{Code: "GITHUB_RATE_LIMITED", SecondaryRateLimit: true, RetryAt: time.Now().Add(time.Minute)})
	if guard("fixture-token-c", "CORE").before(context.Background()) == nil {
		t.Fatal("rotation bypassed shared secondary cooldown")
	}
}

func TestOfficialCooldownPrunesExpiredCredentialKeys(t *testing.T) {
	resetOfficialQuota(t)
	officialProcessQuota.resourceUntil["expired"] = time.Now().Add(-time.Minute)
	rememberOfficialCooldown("active", time.Now().Add(time.Minute), false)
	if _, present := officialProcessQuota.resourceUntil["expired"]; present {
		t.Fatal("expired credential retained")
	}
	if len(officialProcessQuota.resourceUntil) != 1 {
		t.Fatal("active cooldown lost")
	}
}

func TestOfficialInvalidCredentialDoesNotBlockHealthyToken(t *testing.T) {
	h := newOfficialHarness(t, "REPOSITORY_METADATA")
	guard := func(token, resource string) *officialQuotaGuard {
		task := officialTask(h.channel)
		task.Credential = &controlplane.GithubCredential{CredentialID: "same-slot", Token: token}
		return &officialQuotaGuard{control: h.control, task: task, resource: resource}
	}
	invalid := guard("invalid-fixture", "SEARCH")
	if err := invalid.after(context.Background(), github.Quota{Remaining: -1}, &github.APIError{Code: "AUTH_INVALID"}); err == nil {
		t.Fatal("invalid credential not stopped")
	}
	if guard("invalid-fixture", "CORE").before(context.Background()) == nil {
		t.Fatal("invalid token reused for another resource")
	}
	if guard("healthy-fixture", "SEARCH").before(context.Background()) != nil {
		t.Fatal("invalid token blocked a healthy replacement")
	}
}
