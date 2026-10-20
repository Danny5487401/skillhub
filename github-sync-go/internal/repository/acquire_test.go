package repository

import (
	"archive/zip"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"math"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"
)

type cancelAfterFirstRead struct {
	cancel context.CancelFunc
	reads  int
}

func (reader *cancelAfterFirstRead) Read(data []byte) (int, error) {
	reader.reads++
	if reader.reads > 1 {
		return 0, io.EOF
	}
	data[0] = 'x'
	reader.cancel()
	return 1, nil
}

func TestArchiveReaderStopsAfterCancellation(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	source := &cancelAfterFirstRead{cancel: cancel}
	_, err := io.Copy(io.Discard, contextReader{ctx: ctx, reader: source})
	if !errors.Is(err, context.Canceled) || source.reads != 1 {
		t.Fatalf("archive reader continued after cancellation: reads=%d error=%v", source.reads, err)
	}
}

func TestWritePackageArchiveDoesNotStartAfterCancellation(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	path := filepath.Join(t.TempDir(), "package.zip")
	_, _, err := writePackageArchive(ctx, t.TempDir(), path, PackagePlan{}, 1024)
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("canceled archive returned %v", err)
	}
	if _, err := os.Stat(path); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("canceled archive created a file: %v", err)
	}
}

func TestCanReserveWorkspaceIncludesGitCheckoutAndArchive(t *testing.T) {
	const mib = int64(1 << 20)
	limits := AcquisitionLimits{MaxWorkspaceBytes: 64 * mib, MaxArchiveBytes: 24 * mib}
	if canReserveWorkspace(20*mib, 0, limits) {
		t.Fatal("a workspace smaller than the combined Git, checkout, archive and metadata reserve was accepted")
	}
	limits.MaxWorkspaceBytes = 80 * mib
	if !canReserveWorkspace(20*mib, 0, limits) {
		t.Fatal("a workspace with enough reserved capacity was rejected")
	}
	if canReserveWorkspace(math.MaxInt64, 1, limits) {
		t.Fatal("overflowing package and evidence sizes were accepted")
	}
}

func TestArchiveWriteErrorDistinguishesWorkspaceAndUploadLimits(t *testing.T) {
	if err := archiveWriteError(ErrArchiveTooLarge, 1024, 2048); !errors.Is(err, ErrLocalDiskBudget) {
		t.Fatalf("workspace-limited archive was not deferred as disk budget: %v", err)
	}
	if err := archiveWriteError(ErrArchiveTooLarge, 2048, 2048); !errors.Is(err, ErrArchiveTooLarge) {
		t.Fatalf("upload-limited archive was not rejected as too large: %v", err)
	}
	for _, diskError := range []error{syscall.ENOSPC, syscall.EDQUOT} {
		writeError := &os.PathError{Op: "write", Path: "package.zip", Err: diskError}
		if err := archiveWriteError(writeError, 2048, 2048); !errors.Is(err, ErrLocalDiskBudget) {
			t.Fatalf("filesystem write limit %v was not deferred: %v", diskError, err)
		}
	}
}

func TestAcquirePackageDefersWhenArchiveExceedsRemainingWorkspace(t *testing.T) {
	const workspaceMax = int64(20 << 20)
	const commitSHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	fakeBin := t.TempDir()
	git := filepath.Join(fakeBin, "git")
	script := `#!/bin/sh
case " $* " in
  *" checkout "*)
    mkdir -p .git
    printf '%s\n' "$SKILLHUB_TEST_COMMIT" > .git/HEAD
    head -c 10000 /dev/urandom > SKILL.md
    truncate -s "$((SKILLHUB_TEST_WORKSPACE_MAX - 20000))" .git/filler
    ;;
esac
`
	if err := os.WriteFile(git, []byte(script), 0700); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PATH", fakeBin+string(os.PathListSeparator)+os.Getenv("PATH"))
	t.Setenv("SKILLHUB_TEST_COMMIT", commitSHA)
	t.Setenv("SKILLHUB_TEST_WORKSPACE_MAX", strconv.FormatInt(workspaceMax, 10))
	root := t.TempDir()
	artifact, err := AcquirePackage(context.Background(), "openai", "skills", commitSHA, root,
		PackagePlan{Root: ".", TotalBytes: 10000, Files: []PackageFile{{
			RepositoryPath: "SKILL.md", RelativePath: "SKILL.md",
			SHA: strings.Repeat("b", 40), Size: 10000,
		}}}, AcquisitionLimits{
			MaxWorkspaceBytes: workspaceMax, MaxArchiveBytes: 1 << 20,
		})
	if artifact != nil || !errors.Is(err, ErrLocalDiskBudget) || errors.Is(err, ErrArchiveTooLarge) {
		t.Fatalf("workspace-limited archive returned artifact=%v, error=%v", artifact, err)
	}
	entries, err := os.ReadDir(root)
	if err != nil || len(entries) != 0 {
		t.Fatalf("capacity deferral left temporary files: %v, %v", entries, err)
	}
}

func TestAcquirePackageCancellationRemovesGitWorkspace(t *testing.T) {
	fakeBin := t.TempDir()
	started := filepath.Join(fakeBin, "git-started")
	git := filepath.Join(fakeBin, "git")
	if err := os.WriteFile(git, []byte("#!/bin/sh\n: > \"$SKILLHUB_TEST_GIT_STARTED\"\nexec sleep 60\n"), 0700); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PATH", fakeBin+string(os.PathListSeparator)+os.Getenv("PATH"))
	t.Setenv("SKILLHUB_TEST_GIT_STARTED", started)
	root := t.TempDir()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	finished := make(chan error, 1)
	go func() {
		artifact, err := AcquirePackage(ctx, "openai", "skills", strings.Repeat("a", 40), root,
			PackagePlan{Root: "skills/demo", TotalBytes: 1, Files: []PackageFile{{
				RepositoryPath: "skills/demo/SKILL.md", RelativePath: "SKILL.md",
				SHA: strings.Repeat("b", 40), Size: 1,
			}}}, AcquisitionLimits{MaxWorkspaceBytes: 64 << 20, MaxArchiveBytes: 1 << 20})
		if artifact != nil {
			_ = artifact.Close()
		}
		finished <- err
	}()
	deadline := time.Now().Add(5 * time.Second)
	for {
		if _, err := os.Stat(started); err == nil {
			break
		} else if !os.IsNotExist(err) {
			t.Fatal(err)
		}
		if time.Now().After(deadline) {
			cancel()
			t.Fatal("Git command did not start")
		}
		time.Sleep(20 * time.Millisecond)
	}
	cancel()
	select {
	case err := <-finished:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("canceled acquisition returned %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("canceled Git command did not stop")
	}
	entries, err := os.ReadDir(root)
	if err != nil || len(entries) != 0 {
		t.Fatalf("canceled acquisition left temporary files: %v, %v", entries, err)
	}
}

func TestAcquirePackageStopsOnSIGTERM(t *testing.T) {
	if os.Getenv("SKILLHUB_TEST_SIGNAL_CHILD") == "1" {
		ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM)
		defer stop()
		_, err := AcquirePackage(ctx, "openai", "skills", strings.Repeat("a", 40),
			os.Getenv("SKILLHUB_TEST_TEMP_ROOT"), PackagePlan{
				Root: "skills/demo", TotalBytes: 1, Files: []PackageFile{{
					RepositoryPath: "skills/demo/SKILL.md", RelativePath: "SKILL.md",
					SHA: strings.Repeat("b", 40), Size: 1,
				}},
			}, AcquisitionLimits{MaxWorkspaceBytes: 64 << 20, MaxArchiveBytes: 1 << 20})
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("SIGTERM did not cancel Git acquisition: %v", err)
		}
		return
	}
	fakeBin := t.TempDir()
	started := filepath.Join(fakeBin, "git-started")
	git := filepath.Join(fakeBin, "git")
	if err := os.WriteFile(git, []byte("#!/bin/sh\nprintf '%s' \"$$\" > \"$SKILLHUB_TEST_GIT_STARTED\"\nexec sleep 60\n"), 0700); err != nil {
		t.Fatal(err)
	}
	root := t.TempDir()
	binary, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	command := exec.Command(binary, "-test.run=^TestAcquirePackageStopsOnSIGTERM$", "-test.v")
	command.Env = append(os.Environ(),
		"PATH="+fakeBin+string(os.PathListSeparator)+os.Getenv("PATH"),
		"SKILLHUB_TEST_GIT_STARTED="+started,
		"SKILLHUB_TEST_SIGNAL_CHILD=1", "SKILLHUB_TEST_TEMP_ROOT="+root)
	var output bytes.Buffer
	command.Stdout, command.Stderr = &output, &output
	if err := command.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() {
		if command.ProcessState == nil {
			_ = command.Process.Kill()
			if data, err := os.ReadFile(started); err == nil {
				if pid, err := strconv.Atoi(string(data)); err == nil {
					_ = syscall.Kill(-pid, syscall.SIGKILL)
				}
			}
		}
	}()
	deadline := time.Now().Add(5 * time.Second)
	for {
		if _, err := os.Stat(started); err == nil {
			break
		} else if !os.IsNotExist(err) {
			t.Fatal(err)
		}
		if time.Now().After(deadline) {
			t.Fatalf("child Git command did not start: %s", output.String())
		}
		time.Sleep(20 * time.Millisecond)
	}
	if err := command.Process.Signal(syscall.SIGTERM); err != nil {
		t.Fatal(err)
	}
	finished := make(chan error, 1)
	go func() { finished <- command.Wait() }()
	select {
	case err := <-finished:
		if err != nil {
			t.Fatalf("SIGTERM child did not exit cleanly: %v, %s", err, output.String())
		}
	case <-time.After(10 * time.Second):
		t.Fatalf("SIGTERM child did not stop: %s", output.String())
	}
	entries, err := os.ReadDir(root)
	if err != nil || len(entries) != 0 {
		t.Fatalf("SIGTERM left temporary files: %v, %v", entries, err)
	}
}

// This immutable public commit exercises the real HTTPS partial-clone path.
// Run manually with SKILLHUB_GITHUB_SYNC_LIVE=1; regular tests remain offline.
func TestLiveAcquireFixedPublicSkill(t *testing.T) {
	if os.Getenv("SKILLHUB_GITHUB_SYNC_LIVE") != "1" {
		t.Skip("requires an explicit public GitHub smoke test")
	}
	const commitSHA = "49f948faa9258a0c61caceaf225e179651397431"
	plan, want := liveSkillCreatorPlan()
	tempRoot := t.TempDir()
	limits := AcquisitionLimits{
		MaxWorkspaceBytes: 512 << 20, MaxArchiveBytes: 100 << 20, MinFreeBytes: 128 << 20,
	}
	if os.Getenv("SKILLHUB_GITHUB_SYNC_LIVE_SMALL_VOLUME") == "1" {
		limits.MaxWorkspaceBytes = 32 << 20
		limits.MinFreeBytes = 8 << 20
	}
	stopSampling := make(chan struct{})
	peakSample := make(chan int64, 1)
	go func() {
		ticker := time.NewTicker(50 * time.Millisecond)
		defer ticker.Stop()
		var peak int64
		for {
			select {
			case <-ticker.C:
				if size, err := directoryBytes(tempRoot); err == nil && size > peak {
					peak = size
				}
			case <-stopSampling:
				peakSample <- peak
				return
			}
		}
	}()
	artifact, err := AcquirePackage(context.Background(), "openai", "skills", commitSHA,
		tempRoot, plan, limits)
	finalSample, finalSampleErr := directoryBytes(tempRoot)
	close(stopSampling)
	sampledPeakBytes := <-peakSample
	if finalSampleErr == nil && finalSample > sampledPeakBytes {
		sampledPeakBytes = finalSample
	}
	if err != nil {
		t.Fatal(err)
	}
	archiveBytes, err := os.ReadFile(artifact.Path)
	if err != nil {
		t.Fatal(err)
	}
	digest := sha256.Sum256(archiveBytes)
	if artifact.Size != int64(len(archiveBytes)) || artifact.SHA256 != hex.EncodeToString(digest[:]) {
		t.Fatal("archive size or digest does not match the generated artifact")
	}
	if capture := os.Getenv("SKILLHUB_GITHUB_SYNC_CAPTURE_ARCHIVE"); capture != "" {
		output, err := os.OpenFile(capture, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
		if err != nil {
			t.Fatal(err)
		}
		_, writeErr := output.Write(archiveBytes)
		closeErr := output.Close()
		if writeErr != nil || closeErr != nil {
			t.Fatalf("write captured archive: %v, close: %v", writeErr, closeErr)
		}
	}
	workspaceBytes, err := directoryBytes(artifact.dir)
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("fixed commit %s: archive=%d bytes, workspace after acquisition=%d bytes, sampled peak=%d bytes",
		commitSHA, artifact.Size, workspaceBytes, sampledPeakBytes)
	archive, err := zip.OpenReader(artifact.Path)
	if err != nil {
		t.Fatal(err)
	}
	got := make([]string, 0, len(archive.File))
	for _, entry := range archive.File {
		got = append(got, entry.Name)
	}
	if err := archive.Close(); err != nil {
		t.Fatal(err)
	}
	sort.Strings(got)
	if len(got) != len(want) {
		t.Fatalf("archive has %d entries, want %d: %v", len(got), len(want), got)
	}
	for index := range want {
		if got[index] != want[index] {
			t.Fatalf("archive entries = %v, want %v", got, want)
		}
	}
	dir := artifact.dir
	if err := artifact.Close(); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(dir); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("temporary package directory remains after Close: %v", err)
	}
}

// This repository has thousands of tracked paths at the fixed commit. The
// opt-in smoke test checks that a single Skill stays within a small workspace
// even when the remote repository tree is substantially larger.
func TestLiveAcquireLargerPublicRepositorySkill(t *testing.T) {
	if os.Getenv("SKILLHUB_GITHUB_SYNC_LIVE_LARGE_REPO") != "1" {
		t.Skip("requires an explicit public GitHub smoke test")
	}
	const commitSHA = "e325b0ee302fdf66abb1990ad7957e1a1c7aba5a"
	plan := liveLargerPublicRepositoryPlan()
	tempRoot := t.TempDir()
	artifact, err := AcquirePackage(context.Background(), "SangHynh", "agent-skills",
		commitSHA, tempRoot, plan, AcquisitionLimits{
			MaxWorkspaceBytes: 64 << 20, MaxArchiveBytes: 8 << 20, MinFreeBytes: 16 << 20,
		})
	if err != nil {
		t.Fatal(err)
	}
	archive, err := zip.OpenReader(artifact.Path)
	if err != nil {
		_ = artifact.Close()
		t.Fatal(err)
	}
	if len(archive.File) != 2 || archive.File[0].Name != "SKILL.md" ||
		archive.File[1].Name != evidenceManifestPath {
		_ = archive.Close()
		_ = artifact.Close()
		t.Fatalf("unexpected Skill archive entries: %+v", archive.File)
	}
	if err := archive.Close(); err != nil {
		_ = artifact.Close()
		t.Fatal(err)
	}
	workspaceBytes, err := directoryBytes(tempRoot)
	if err != nil || workspaceBytes >= 64<<20 {
		_ = artifact.Close()
		t.Fatalf("workspace exceeded configured limit: %d bytes, error %v", workspaceBytes, err)
	}
	t.Logf("fixed commit %s: archive=%d bytes, workspace after acquisition=%d bytes",
		commitSHA, artifact.Size, workspaceBytes)
	if err := artifact.Close(); err != nil {
		t.Fatal(err)
	}
	remaining, err := os.ReadDir(tempRoot)
	if err != nil || len(remaining) != 0 {
		t.Fatalf("temporary files remain after package cleanup: %v, %v", remaining, err)
	}
}

func liveLargerPublicRepositoryPlan() PackagePlan {
	const root = "skills/00-andruia-consultant"
	return PackagePlan{Root: root, TotalBytes: 2894, Files: []PackageFile{{
		RepositoryPath: root + "/SKILL.md", RelativePath: "SKILL.md",
		SHA: "e173357610b8204ce3d676ca9172b3463b02ce97", Size: 2894,
	}}}
}

func liveSkillCreatorPlan() (PackagePlan, []string) {
	const skillRoot = "skills/.system/skill-creator"
	files := []struct {
		path string
		sha  string
		size int64
	}{
		{"LICENSE.txt", "d645695673349e3947e8e5ae42332d0ac3164cd7", 11358},
		{"SKILL.md", "72bc0b97e7a6476254a9d5c424c9971748402ec3", 18664},
		{"agents/openai.yaml", "f5ce09ed118b72908b7308fcfc1619f59e89f6ea", 192},
		{"references/openai_yaml.md", "da5629f8de500ec6f81291d239a2d9c68d1d9044", 2130},
		{"scripts/generate_openai_yaml.py", "1a9d784f8704999daf55a2b9036094949d9af3f8", 6614},
		{"scripts/init_skill.py", "f90703eca8159a21a9684ed1df974c702d92f814", 14483},
		{"scripts/quick_validate.py", "0547b4041a5f58fa19892079a114a1df98286406", 3293},
	}
	plan := PackagePlan{Root: skillRoot}
	want := make([]string, 0, len(files)+1)
	for _, file := range files {
		plan.Files = append(plan.Files, PackageFile{
			RepositoryPath: skillRoot + "/" + file.path, RelativePath: file.path,
			SHA: file.sha, Size: file.size,
		})
		plan.TotalBytes += file.size
		want = append(want, file.path)
	}
	want = append(want, evidenceManifestPath)
	sort.Strings(want)
	return plan, want
}

// Two Skill roots in one fixed repository revision must use isolated package
// workspaces: closing the first package releases its Git objects before the
// second package starts.
func TestLiveAcquireTwoFixedPublicSkillsSequentially(t *testing.T) {
	if os.Getenv("SKILLHUB_GITHUB_SYNC_LIVE") != "1" {
		t.Skip("requires an explicit public GitHub smoke test")
	}
	const commitSHA = "49f948faa9258a0c61caceaf225e179651397431"
	creator, _ := liveSkillCreatorPlan()
	goal := liveDefineGoalPlan()
	tempRoot := t.TempDir()
	for _, plan := range []PackagePlan{creator, goal} {
		artifact, err := AcquirePackage(context.Background(), "openai", "skills", commitSHA,
			tempRoot, plan, AcquisitionLimits{
				MaxWorkspaceBytes: 512 << 20, MaxArchiveBytes: 100 << 20, MinFreeBytes: 128 << 20,
			})
		if err != nil {
			t.Fatalf("acquire %s: %v", plan.Root, err)
		}
		archive, err := zip.OpenReader(artifact.Path)
		if err != nil {
			t.Fatal(err)
		}
		if len(archive.File) != len(plan.Files)+1 {
			t.Fatalf("%s: archive entries = %d, want %d",
				plan.Root, len(archive.File), len(plan.Files)+1)
		}
		if err := archive.Close(); err != nil {
			t.Fatal(err)
		}
		workspaceBytes, err := directoryBytes(tempRoot)
		if err != nil || workspaceBytes >= 32<<20 {
			t.Fatalf("%s: unexpected workspace bytes %d: %v", plan.Root, workspaceBytes, err)
		}
		t.Logf("%s: archive=%d bytes, workspace after acquisition=%d bytes",
			plan.Root, artifact.Size, workspaceBytes)
		if err := artifact.Close(); err != nil {
			t.Fatal(err)
		}
		remaining, err := os.ReadDir(tempRoot)
		if err != nil || len(remaining) != 0 {
			t.Fatalf("%s: temporary files remain after package cleanup: %v, %v",
				plan.Root, remaining, err)
		}
	}
}

func liveDefineGoalPlan() PackagePlan {
	return PackagePlan{
		Root: "skills/.curated/define-goal",
		Files: []PackageFile{
			{RepositoryPath: "skills/.curated/define-goal/LICENSE.txt", RelativePath: "LICENSE.txt",
				SHA: "13e25df86ce06eb6488e6a6bc5c5847f5dedc352", Size: 10776},
			{RepositoryPath: "skills/.curated/define-goal/SKILL.md", RelativePath: "SKILL.md",
				SHA: "87f111bd700e0d993465f7ac741847b5daee57d6", Size: 5647},
			{RepositoryPath: "skills/.curated/define-goal/agents/openai.yaml", RelativePath: "agents/openai.yaml",
				SHA: "28c7e7a644d7ae3f5616c7ddd408c573139999f0", Size: 208},
		},
		TotalBytes: 16631,
	}
}

// Run in the production image with a 72 MiB /tmp tmpfs. Both packages use
// independent Git workspaces while the volume provides a hard physical limit.
func TestLiveAcquireTwoFixedPublicSkillsConcurrentlyOnSmallVolume(t *testing.T) {
	if os.Getenv("SKILLHUB_GITHUB_SYNC_LIVE_CONCURRENT") != "1" {
		t.Skip("requires an explicit concurrent public GitHub smoke test")
	}
	const commitSHA = "49f948faa9258a0c61caceaf225e179651397431"
	tempRoot := t.TempDir()
	var filesystem syscall.Statfs_t
	if err := syscall.Statfs(tempRoot, &filesystem); err != nil {
		t.Fatal(err)
	}
	volumeBytes := int64(filesystem.Blocks) * int64(filesystem.Bsize)
	if volumeBytes > 80<<20 {
		t.Fatalf("concurrent smoke test requires a small tmpfs: volume=%d bytes", volumeBytes)
	}
	creator, creatorFiles := liveSkillCreatorPlan()
	goal := liveDefineGoalPlan()
	type request struct {
		owner, name, commit string
		plan                PackagePlan
	}
	requests := []request{
		{owner: "openai", name: "skills", commit: commitSHA, plan: creator},
		{owner: "openai", name: "skills", commit: commitSHA, plan: goal},
	}
	if os.Getenv("SKILLHUB_GITHUB_SYNC_LIVE_CROSS_REPOSITORY") == "1" {
		goal = liveLargerPublicRepositoryPlan()
		requests[1] = request{owner: "SangHynh", name: "agent-skills",
			commit: "e325b0ee302fdf66abb1990ad7957e1a1c7aba5a", plan: goal}
	}
	expectedEntries := map[string]int{
		creator.Root: len(creatorFiles),
		goal.Root:    len(goal.Files) + 1,
	}
	limits := AcquisitionLimits{
		MaxWorkspaceBytes: 32 << 20, MaxArchiveBytes: 8 << 20, MinFreeBytes: 8 << 20,
	}
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	type result struct {
		root     string
		artifact *Artifact
		err      error
	}
	results := make(chan result, len(requests))
	start := make(chan struct{})
	for _, requested := range requests {
		go func(requested request) {
			<-start
			artifact, err := AcquirePackage(ctx, requested.owner, requested.name,
				requested.commit, tempRoot, requested.plan, limits)
			results <- result{root: requested.plan.Root, artifact: artifact, err: err}
		}(requested)
	}
	close(start)
	ticker := time.NewTicker(10 * time.Millisecond)
	defer ticker.Stop()
	var sampledPeak int64
	var overlappingWorkspaces bool
	artifacts := make([]*Artifact, 0, len(requests))
	for completed := 0; completed < len(requests); {
		select {
		case outcome := <-results:
			completed++
			if outcome.err != nil {
				t.Errorf("acquire %s: %v", outcome.root, outcome.err)
				continue
			}
			artifacts = append(artifacts, outcome.artifact)
			archive, err := zip.OpenReader(outcome.artifact.Path)
			if err != nil {
				t.Errorf("open %s archive: %v", outcome.root, err)
				continue
			}
			if len(archive.File) != expectedEntries[outcome.root] {
				t.Errorf("%s archive entries=%d, want=%d", outcome.root,
					len(archive.File), expectedEntries[outcome.root])
			}
			if err := archive.Close(); err != nil {
				t.Errorf("close %s archive: %v", outcome.root, err)
			}
		case <-ticker.C:
			if completed == 0 && len(results) == 0 {
				if entries, err := os.ReadDir(tempRoot); err == nil && len(entries) == len(requests) {
					overlappingWorkspaces = true
				}
			}
			if used, err := directoryBytes(tempRoot); err == nil {
				sampledPeak = max(sampledPeak, used)
			} else {
				t.Errorf("measure concurrent workspaces: %v", err)
			}
		case <-ctx.Done():
			t.Fatal(ctx.Err())
		}
	}
	if len(artifacts) != len(requests) {
		t.Errorf("completed packages=%d, want=%d", len(artifacts), len(requests))
	}
	if !overlappingWorkspaces {
		t.Error("the two package workspaces did not overlap during acquisition")
	}
	if used, err := directoryBytes(tempRoot); err != nil || used > 64<<20 {
		t.Errorf("concurrent workspaces exceeded total budget: bytes=%d error=%v", used, err)
	} else {
		sampledPeak = max(sampledPeak, used)
	}
	for _, artifact := range artifacts {
		if err := artifact.Close(); err != nil {
			t.Errorf("remove %s: %v", artifact.Path, err)
		}
	}
	if remaining, err := os.ReadDir(tempRoot); err != nil || len(remaining) != 0 {
		t.Errorf("concurrent workspaces were not cleared: entries=%d error=%v", len(remaining), err)
	}
	t.Logf("concurrent fixed commit packages=%d cross_repository=%t volume_bytes=%d sampled_peak_bytes=%d overlapping_workspaces=%t",
		len(artifacts), os.Getenv("SKILLHUB_GITHUB_SYNC_LIVE_CROSS_REPOSITORY") == "1",
		volumeBytes, sampledPeak, overlappingWorkspaces)
}

// Fill both Git metadata directories near the configured aggregate budget.
// The fake Git executable avoids network variance while retaining the real
// workspace accounting and ZIP creation path.
func TestConcurrentAcquisitionNearSmallVolumeBudget(t *testing.T) {
	if os.Getenv("SKILLHUB_GITHUB_SYNC_CONCURRENT_CAPACITY_TEST") != "1" {
		t.Skip("requires an explicit small-volume capacity test")
	}
	const commitSHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	const mib = int64(1 << 20)
	root := t.TempDir()
	var filesystem syscall.Statfs_t
	if err := syscall.Statfs(root, &filesystem); err != nil {
		t.Fatal(err)
	}
	if volume := int64(filesystem.Blocks) * int64(filesystem.Bsize); volume > 80*mib {
		t.Fatalf("capacity test requires a small tmpfs: volume=%d bytes", volume)
	}
	fakeBin := t.TempDir()
	script := `#!/bin/sh
case " $* " in
  *" checkout "*)
    mkdir -p .git
    printf '%s\n' "$SKILLHUB_TEST_COMMIT" > .git/HEAD
    head -c 10000 /dev/zero > SKILL.md
    dd if=/dev/zero of=.git/filler bs=1048576 count=28 2>/dev/null
    sleep 0.2
    ;;
esac
`
	if err := os.WriteFile(filepath.Join(fakeBin, "git"), []byte(script), 0700); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PATH", fakeBin+string(os.PathListSeparator)+os.Getenv("PATH"))
	t.Setenv("SKILLHUB_TEST_COMMIT", commitSHA)
	if resolved, err := exec.LookPath("git"); err != nil || resolved != filepath.Join(fakeBin, "git") {
		t.Fatalf("fake Git executable was not selected: %q, %v", resolved, err)
	}
	plan := PackagePlan{Root: ".", TotalBytes: 10000, Files: []PackageFile{{
		RepositoryPath: "SKILL.md", RelativePath: "SKILL.md", SHA: strings.Repeat("b", 40), Size: 10000,
	}}}
	limits := AcquisitionLimits{MaxWorkspaceBytes: 32 * mib,
		MaxArchiveBytes: mib, MinFreeBytes: 8 * mib}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	results := make(chan struct {
		artifact *Artifact
		err      error
	}, 2)
	start := make(chan struct{})
	for range 2 {
		go func() {
			<-start
			artifact, err := AcquirePackage(ctx, "openai", "skills", commitSHA, root, plan, limits)
			results <- struct {
				artifact *Artifact
				err      error
			}{artifact, err}
		}()
	}
	close(start)
	artifacts := make([]*Artifact, 0, 2)
	for range 2 {
		select {
		case outcome := <-results:
			if outcome.err != nil {
				t.Errorf("near-budget acquisition failed: %v", outcome.err)
			} else {
				artifacts = append(artifacts, outcome.artifact)
			}
		case <-ctx.Done():
			t.Fatal(ctx.Err())
		}
	}
	used, err := directoryBytes(root)
	if err != nil || used < 56*mib || used > 64*mib {
		t.Errorf("unexpected aggregate workspace occupancy: bytes=%d error=%v", used, err)
	}
	if err := syscall.Statfs(root, &filesystem); err != nil ||
		int64(filesystem.Bavail)*int64(filesystem.Bsize) < 8*mib {
		t.Errorf("temporary filesystem lost its 8 MiB reserve: %v", err)
	}
	for _, artifact := range artifacts {
		if err := artifact.Close(); err != nil {
			t.Errorf("clean near-budget package: %v", err)
		}
	}
	if remaining, err := os.ReadDir(root); err != nil || len(remaining) != 0 {
		t.Errorf("near-budget workspaces remain: entries=%d error=%v", len(remaining), err)
	}
	t.Logf("near-budget concurrent packages=%d aggregate_workspace_bytes=%d",
		len(artifacts), used)
}

func TestWorkspaceChecksDirectorySizeAndFreeSpace(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "object"), make([]byte, 32), 0600); err != nil {
		t.Fatal(err)
	}
	if err := checkWorkspace(root, 31, 0); !errors.Is(err, ErrLocalDiskBudget) {
		t.Fatalf("workspace over its byte limit: %v", err)
	}
	if err := checkWorkspace(root, 32, 0); err != nil {
		t.Fatalf("workspace at its byte limit: %v", err)
	}
	var stats syscall.Statfs_t
	if err := syscall.Statfs(root, &stats); err != nil {
		t.Fatal(err)
	}
	available := uint64(stats.Bavail) * uint64(stats.Bsize)
	if available > math.MaxInt64-1 {
		t.Skip("filesystem is too large for an int64 free-space boundary")
	}
	if err := requireMinimumFree(root, int64(available)+1); !errors.Is(err, ErrLocalDiskBudget) {
		t.Fatalf("minimum free space was not protected: %v", err)
	}
}

func TestDirectoryBytesDoesNotIgnoreMissingWorkspace(t *testing.T) {
	_, err := directoryBytes(filepath.Join(t.TempDir(), "missing-workspace"))
	if !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("missing workspace must fail accounting: %v", err)
	}
}

func TestAcquirePackageRejectsCapacityBeforeNetworkFetch(t *testing.T) {
	const mib = int64(1 << 20)
	plan := PackagePlan{
		Root: ".", TotalBytes: 20 * mib,
		Files: []PackageFile{{RepositoryPath: "SKILL.md", RelativePath: "SKILL.md",
			SHA: skillBlobSHA, Size: 20 * mib}},
	}
	artifact, err := AcquirePackage(context.Background(), "owner", "repo", rootTreeSHA,
		t.TempDir(), plan, AcquisitionLimits{MaxWorkspaceBytes: 64 * mib,
			MaxArchiveBytes: 24 * mib, MinFreeBytes: 0})
	if artifact != nil || !errors.Is(err, ErrCapacity) {
		t.Fatalf("expected pre-fetch capacity block, got artifact=%v, error=%v", artifact, err)
	}
}

func TestSparseCheckoutKeepsOnlySelectedSkillFile(t *testing.T) {
	root := t.TempDir()
	repoDir := filepath.Join(root, "repo")
	for _, directory := range []string{filepath.Join(repoDir, "selected"), filepath.Join(repoDir, "sibling")} {
		if err := os.MkdirAll(directory, 0700); err != nil {
			t.Fatal(err)
		}
	}
	for _, path := range []string{"selected/SKILL.md", "sibling/SKILL.md"} {
		if err := os.WriteFile(filepath.Join(repoDir, path), []byte("name: fixture\n"), 0600); err != nil {
			t.Fatal(err)
		}
	}
	for _, arguments := range [][]string{
		{"init", "--quiet"}, {"add", "."},
		{"-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "--quiet", "-m", "fixture"},
	} {
		command := exec.Command("git", arguments...)
		command.Dir = repoDir
		if output, err := command.CombinedOutput(); err != nil {
			t.Fatalf("git fixture setup failed: %v: %s", err, output)
		}
	}
	if err := runBoundedGit(context.Background(), repoDir, root, 64<<20, 0,
		"sparse-checkout", "set", "--no-cone", "--", sparseFilePattern("selected/SKILL.md")); err != nil {
		t.Fatal(err)
	}
	if err := runBoundedGit(context.Background(), repoDir, root, 64<<20, 0,
		"checkout", "--quiet", "--detach", "--force", "HEAD"); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(repoDir, "selected/SKILL.md")); err != nil {
		t.Fatalf("planned Skill file missing: %v", err)
	}
	if _, err := os.Stat(filepath.Join(repoDir, "sibling/SKILL.md")); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("unplanned sibling file was checked out: %v", err)
	}
}
