package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestPrepareTempWorkspaceCleansOnlyAbandonedPackages(t *testing.T) {
	root := t.TempDir()
	abandoned := filepath.Join(root, packageWorkspacePrefix+"abandoned")
	if err := os.Mkdir(abandoned, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(abandoned, "object"), []byte("old"), 0600); err != nil {
		t.Fatal(err)
	}
	unrelated := filepath.Join(root, "operator-note")
	if err := os.WriteFile(unrelated, []byte("keep"), 0600); err != nil {
		t.Fatal(err)
	}
	guard, err := prepareTempWorkspace(root)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = guard.Close() })
	if _, err := os.Stat(abandoned); !os.IsNotExist(err) {
		t.Fatalf("abandoned package remains: %v", err)
	}
	if content, err := os.ReadFile(unrelated); err != nil || string(content) != "keep" {
		t.Fatalf("unrelated file changed: %q, %v", content, err)
	}
	if _, err := prepareTempWorkspace(root); err == nil {
		t.Fatal("a second worker must not clean an active workspace")
	}
}

func TestPrepareTempWorkspaceCanRecoverAfterPreviousOwnerExits(t *testing.T) {
	root := t.TempDir()
	guard, err := prepareTempWorkspace(root)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(filepath.Join(root, packageWorkspacePrefix+"interrupted"), 0700); err != nil {
		t.Fatal(err)
	}
	if err := guard.Close(); err != nil {
		t.Fatal(err)
	}
	guard, err = prepareTempWorkspace(root)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = guard.Close() })
	if entries, err := os.ReadDir(root); err != nil || len(entries) != 0 {
		t.Fatalf("restarted worker did not clear its old package: %v, %v", entries, err)
	}
}
