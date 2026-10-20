package controlplane

import "testing"

func TestPlatformCapabilityIsValidForLegacyDiscoveryAndRequiresControlVersion(t *testing.T) {
	version := int64(1)
	for _, entry := range []string{"CURATED_REPOSITORY", "SEARCH_RULE", "SEARCH_PREVIEW", "CURATED_OWNER"} {
		task := ClaimedTask{Kind: "DISCOVERY", Discovery: &DiscoverySnapshot{EntryType: entry}, RequiredCapabilities: []string{PlatformPauseCapability}, PlatformControlVersion: &version}
		if err := validateOfficialTask(task, []string{PlatformPauseCapability}); err != nil {
			t.Fatalf("%s: %v", entry, err)
		}
		task.PlatformControlVersion = nil
		if err := validateOfficialTask(task, []string{PlatformPauseCapability}); err == nil {
			t.Fatal("accepted missing platform version")
		}
	}
	task := ClaimedTask{Kind: "REPOSITORY_SYNC", PlatformControlVersion: &version}
	if err := validateOfficialTask(task, []string{PlatformPauseCapability}); err == nil {
		t.Fatal("accepted undeclared platform capability")
	}
}
