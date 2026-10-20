package repository

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func TestOfficialGitQuotaPolicy(t *testing.T) {
	for _, kind := range []string{"blocked-before-start", "fetch-report-failure", "checkout-report-failure", "local-init"} {
		t.Run(kind, func(t *testing.T) {
			dir := t.TempDir()
			bin := t.TempDir()
			if err := os.WriteFile(filepath.Join(bin, "git"), []byte("#!/bin/sh\nexit 0\n"), 0700); err != nil {
				t.Fatal(err)
			}
			t.Setenv("PATH", bin)
			before, after := 0, 0
			denied := errors.New("quota policy denied")
			ctx := WithGitRequestPolicy(context.Background(), func(context.Context) error {
				before++
				if kind == "blocked-before-start" {
					return denied
				}
				return nil
			}, func(context.Context, error) error { after++; return denied })
			command := "fetch"
			if kind == "checkout-report-failure" {
				command = "checkout"
			}
			if kind == "local-init" {
				command = "init"
			}
			err := runBoundedGit(ctx, dir, dir, 1<<20, 0, command)
			if before != 1 {
				t.Fatalf("before=%d", before)
			}
			if kind == "local-init" {
				if err != nil || after != 0 {
					t.Fatal("local init fabricated quota observation")
				}
				return
			}
			if !errors.Is(err, denied) {
				t.Fatalf("policy failure swallowed: %v", err)
			}
			wantAfter := 1
			if kind == "blocked-before-start" {
				wantAfter = 0
			}
			if after != wantAfter {
				t.Fatalf("after=%d want=%d", after, wantAfter)
			}
		})
	}
}
