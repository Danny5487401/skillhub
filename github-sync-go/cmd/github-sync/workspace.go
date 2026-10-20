package main

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"syscall"
)

const packageWorkspacePrefix = "skillhub-github-package-"

// Keep the directory locked while the worker runs so another process cannot
// remove active packages from a shared local workspace during startup.
func prepareTempWorkspace(root string) (*os.File, error) {
	guard, err := os.Open(root)
	if err != nil {
		return nil, err
	}
	if err := syscall.Flock(int(guard.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		_ = guard.Close()
		return nil, errors.New("GitHub sync temporary workspace is already in use")
	}
	entries, err := os.ReadDir(root)
	if err != nil {
		_ = guard.Close()
		return nil, err
	}
	for _, entry := range entries {
		if !strings.HasPrefix(entry.Name(), packageWorkspacePrefix) ||
			len(entry.Name()) == len(packageWorkspacePrefix) {
			continue
		}
		if err := os.RemoveAll(filepath.Join(root, entry.Name())); err != nil {
			_ = guard.Close()
			return nil, err
		}
	}
	return guard, nil
}
