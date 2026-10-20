package repository

import (
	"archive/zip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/iflytek/skillhub/github-sync-go/internal/github"
)

var (
	ErrLocalDiskBudget = errors.New("local Git workspace disk budget exceeded")
	ErrCapacity        = errors.New("local disk cannot hold a compliant package")
	ErrArchiveTooLarge = errors.New("Skill archive exceeds upload limit")
)

const (
	evidenceManifestPath   = "__skillhub_sync_evidence__.json"
	evidenceEntryPrefix    = "__skillhub_sync_evidence__/"
	gitMetadataReserve     = 16 << 20
	archiveOverheadReserve = 1 << 20
)

type rootEvidenceManifest struct {
	FormatVersion int                `json:"formatVersion"`
	RootFiles     []RootEvidenceFile `json:"rootFiles"`
}

type AcquisitionLimits struct {
	MaxWorkspaceBytes int64
	MaxArchiveBytes   int64
	MinFreeBytes      int64
}

// Artifact owns its isolated temporary directory until Close is called.
type Artifact struct {
	Path     string
	Size     int64
	SHA256   string
	dir      string
	once     sync.Once
	closeErr error
}

func (artifact *Artifact) Close() error {
	artifact.once.Do(func() { artifact.closeErr = os.RemoveAll(artifact.dir) })
	return artifact.closeErr
}

// AcquirePackage fetches one fixed public commit without repository blobs,
// checks out only the preflighted files, then builds one bounded ZIP. The
// caller must obtain Java package admission before invoking this function.
func AcquirePackage(ctx context.Context, owner, name, commitSHA, tempRoot string,
	plan PackagePlan, limits AcquisitionLimits) (*Artifact, error) {
	if !validGitSHA(commitSHA) || (plan.Root != "." && !validPath(plan.Root)) ||
		len(plan.Files) == 0 ||
		plan.TotalBytes < 0 || limits.MaxWorkspaceBytes < 1 || limits.MaxArchiveBytes < 1 ||
		limits.MinFreeBytes < 0 || limits.MaxWorkspaceBytes > math.MaxInt64-limits.MinFreeBytes {
		return nil, errors.New("invalid fixed revision, package plan, or acquisition limits")
	}
	remote, err := github.PublicCloneURL(owner, name)
	if err != nil {
		return nil, err
	}
	var evidenceBytes int64
	for _, file := range plan.Evidence {
		if !validPath(file.RepositoryPath) || strings.Contains(file.RepositoryPath, "/") ||
			!rootEvidenceName.MatchString(file.RepositoryPath) || !validGitSHA(file.SHA) ||
			file.Size < 0 {
			return nil, errors.New("invalid root license or notice evidence")
		}
		if file.Bundled {
			if file.Size > maxEvidenceFileBytes ||
				file.Size > maxEvidenceTotalBytes-evidenceBytes {
				return nil, errors.New("root evidence exceeds acquisition limit")
			}
			evidenceBytes += file.Size
		}
	}
	if len(plan.Evidence) > maxRootEvidenceFiles ||
		!canReserveWorkspace(plan.TotalBytes, evidenceBytes, limits) {
		return nil, ErrCapacity
	}
	patterns, allowed, err := prepareSparseCheckout(plan)
	if err != nil {
		return nil, err
	}
	if err := ensureFreeSpace(tempRoot, limits.MaxWorkspaceBytes+limits.MinFreeBytes); err != nil {
		return nil, err
	}
	workDir, err := os.MkdirTemp(tempRoot, "skillhub-github-package-")
	if err != nil {
		return nil, localDiskWriteError(err)
	}
	keep := false
	defer func() {
		if !keep {
			_ = os.RemoveAll(workDir)
		}
	}()
	repoDir := filepath.Join(workDir, "repo")
	if err := os.Mkdir(repoDir, 0700); err != nil {
		return nil, localDiskWriteError(err)
	}
	for _, step := range []struct {
		name string
		args []string
	}{
		{"init", []string{"init", "--quiet"}},
		{"remote", []string{"remote", "add", "origin", remote}},
		{"fetch", []string{"fetch", "--quiet", "--no-tags", "--filter=blob:none", "--depth=1", "origin", commitSHA}},
	} {
		if err := runBoundedGit(ctx, repoDir, workDir, limits.MaxWorkspaceBytes,
			limits.MinFreeBytes, step.args...); err != nil {
			return nil, fmt.Errorf("Git %s: %w", step.name, err)
		}
	}
	if err := runBoundedGit(ctx, repoDir, workDir, limits.MaxWorkspaceBytes,
		limits.MinFreeBytes, patterns...); err != nil {
		return nil, fmt.Errorf("Git sparse checkout: %w", err)
	}
	if err := runBoundedGit(ctx, repoDir, workDir, limits.MaxWorkspaceBytes,
		limits.MinFreeBytes,
		"checkout", "--quiet", "--detach", "--force", "FETCH_HEAD"); err != nil {
		return nil, fmt.Errorf("Git fixed checkout: %w", err)
	}
	head, err := os.ReadFile(filepath.Join(repoDir, ".git", "HEAD"))
	if err != nil || strings.TrimSpace(string(head)) != commitSHA {
		return nil, errors.New("checked-out commit differs from fixed revision")
	}
	if err := verifyCheckout(repoDir, allowed); err != nil {
		return nil, err
	}
	archivePath := filepath.Join(workDir, "package.zip")
	usedBytes, err := directoryBytes(workDir)
	if err != nil {
		return nil, err
	}
	remainingBytes := limits.MaxWorkspaceBytes - usedBytes
	if remainingBytes < 1 {
		return nil, ErrLocalDiskBudget
	}
	archiveLimit := min(limits.MaxArchiveBytes, remainingBytes)
	if err := requireMinimumFree(workDir, archiveLimit+limits.MinFreeBytes); err != nil {
		return nil, err
	}
	size, digest, err := writePackageArchive(ctx, repoDir, archivePath, plan, archiveLimit)
	if err != nil {
		return nil, archiveWriteError(err, archiveLimit, limits.MaxArchiveBytes)
	}
	if size+usedBytes > limits.MaxWorkspaceBytes {
		return nil, ErrLocalDiskBudget
	}
	keep = true
	return &Artifact{Path: archivePath, Size: size, SHA256: digest, dir: workDir}, nil
}

// Validate the complete file plan before creating a workspace or fetching Git objects.
func prepareSparseCheckout(plan PackagePlan) ([]string, map[string]PackageFile, error) {
	patterns := make([]string, 0, len(plan.Files)+len(plan.Evidence)+4)
	patterns = append(patterns, "sparse-checkout", "set", "--no-cone", "--")
	allowed := make(map[string]PackageFile, len(plan.Files))
	hasSkillMD := false
	var expectedTotal int64
	for _, file := range plan.Files {
		if !validPath(file.RepositoryPath) || !validPath(file.RelativePath) ||
			!validGitSHA(file.SHA) || file.Size < 0 {
			return nil, nil, errors.New("invalid preflighted file")
		}
		if plan.Root != "." && !strings.HasPrefix(file.RepositoryPath, plan.Root+"/") {
			return nil, nil, errors.New("preflighted file is outside Skill root")
		}
		relativePath := file.RepositoryPath
		if plan.Root != "." {
			relativePath = strings.TrimPrefix(file.RepositoryPath, plan.Root+"/")
		}
		if file.RelativePath != relativePath || file.Size > plan.TotalBytes-expectedTotal {
			return nil, nil, errors.New("preflighted file does not match package plan")
		}
		expectedTotal += file.Size
		if file.RelativePath == "SKILL.md" {
			hasSkillMD = true
		}
		if _, duplicate := allowed[file.RepositoryPath]; duplicate {
			return nil, nil, errors.New("duplicate preflighted file")
		}
		allowed[file.RepositoryPath] = file
		patterns = append(patterns, sparseFilePattern(file.RepositoryPath))
	}
	for _, evidence := range plan.Evidence {
		if !evidence.Bundled {
			continue
		}
		if selected, exists := allowed[evidence.RepositoryPath]; exists {
			if selected.SHA != evidence.SHA || selected.Size != evidence.Size {
				return nil, nil, errors.New("root evidence conflicts with package file")
			}
			continue
		}
		allowed[evidence.RepositoryPath] = PackageFile{
			RepositoryPath: evidence.RepositoryPath,
			SHA:            evidence.SHA, Size: evidence.Size,
		}
		patterns = append(patterns, sparseFilePattern(evidence.RepositoryPath))
	}
	if !hasSkillMD || expectedTotal != plan.TotalBytes {
		return nil, nil, errors.New("package plan has no SKILL.md or mismatched total size")
	}
	return patterns, allowed, nil
}

// Reserve room for Git objects, checked-out files, a ZIP, and baseline Git
// metadata before any network fetch. Actual usage is still checked at each Git
// step because repository tree metadata has no predictable size.
func canReserveWorkspace(packageBytes, evidenceBytes int64, limits AcquisitionLimits) bool {
	if packageBytes < 0 || evidenceBytes < 0 || packageBytes > math.MaxInt64-evidenceBytes ||
		limits.MaxWorkspaceBytes <= gitMetadataReserve || limits.MaxArchiveBytes < 1 {
		return false
	}
	rawBytes := packageBytes + evidenceBytes
	available := limits.MaxWorkspaceBytes - gitMetadataReserve
	if rawBytes > available/2 {
		return false
	}
	available -= 2 * rawBytes
	archiveBytes := limits.MaxArchiveBytes
	if rawBytes <= limits.MaxArchiveBytes-archiveOverheadReserve {
		archiveBytes = rawBytes + archiveOverheadReserve
	}
	return archiveBytes <= available
}

func archiveWriteError(err error, workspaceLimit, uploadLimit int64) error {
	if errors.Is(err, ErrArchiveTooLarge) && workspaceLimit < uploadLimit {
		return ErrLocalDiskBudget
	}
	return localDiskWriteError(err)
}

func localDiskWriteError(err error) error {
	if errors.Is(err, syscall.ENOSPC) || errors.Is(err, syscall.EDQUOT) {
		return ErrLocalDiskBudget
	}
	return err
}

func sparseFilePattern(path string) string {
	var pattern strings.Builder
	pattern.WriteByte('/')
	for _, char := range path {
		if char == '*' || char == '?' || char == '[' || char == ']' || char == '\\' {
			pattern.WriteByte('\\')
		}
		pattern.WriteRune(char)
	}
	return pattern.String()
}

func gitEnvironment() []string {
	environment := make([]string, 0, len(os.Environ())+5)
	for _, entry := range os.Environ() {
		if strings.HasPrefix(entry, "GIT_") || strings.HasPrefix(entry, "HOME=") {
			continue
		}
		environment = append(environment, entry)
	}
	return append(environment,
		"GIT_CONFIG_NOSYSTEM=1", "GIT_CONFIG_GLOBAL=/dev/null",
		"GIT_TERMINAL_PROMPT=0", "GIT_ALLOW_PROTOCOL=https", "GIT_LFS_SKIP_SMUDGE=1")
}

type gitRequestPolicyKey struct{}
type gitRequestPolicy struct {
	before func(context.Context) error
	after  func(context.Context, error) error
}

// WithGitRequestPolicy guards commands that may fetch lazy partial-clone blobs.
// Archive creation and artifact delivery do not perform GitHub requests.
func WithGitRequestPolicy(ctx context.Context, before func(context.Context) error, after func(context.Context, error) error) context.Context {
	return context.WithValue(ctx, gitRequestPolicyKey{}, gitRequestPolicy{before, after})
}

func runBoundedGit(ctx context.Context, repoDir, workDir string,
	maxBytes, minFreeBytes int64, args ...string) (resultErr error) {
	if err := github.CheckAcquisitionControl(ctx); err != nil {
		return err
	}
	var afterRequest func(context.Context, error) error
	if policy, ok := ctx.Value(gitRequestPolicyKey{}).(gitRequestPolicy); ok {
		if err := policy.before(ctx); err != nil {
			return err
		}
		if len(args) > 0 && (args[0] == "fetch" || args[0] == "checkout" || args[0] == "sparse-checkout") {
			afterRequest = policy.after
		}
	}

	if err := checkWorkspace(workDir, maxBytes, minFreeBytes); err != nil {
		return err
	}
	command := exec.CommandContext(ctx, "git", append([]string{
		"-c", "credential.helper=", "-c", "core.hooksPath=/dev/null",
		"-c", "http.followRedirects=false",
	}, args...)...)
	command.Dir = repoDir
	command.Env = gitEnvironment()
	command.Stdout = io.Discard
	command.Stderr = io.Discard
	command.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	if err := command.Start(); err != nil {
		return localDiskWriteError(err)
	}
	if afterRequest != nil {
		defer func() {
			if err := afterRequest(ctx, resultErr); err != nil {
				resultErr = err
			}
		}()
	}
	done := make(chan error, 1)
	go func() { done <- command.Wait() }()
	ticker := time.NewTicker(500 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case err := <-done:
			if checkErr := checkWorkspace(workDir, maxBytes, minFreeBytes); checkErr != nil {
				return checkErr
			}
			if err != nil {
				if ctx.Err() != nil {
					return ctx.Err()
				}
				if errors.Is(requireMinimumFree(workDir, 1), ErrLocalDiskBudget) {
					return ErrLocalDiskBudget
				}
				return errors.New("Git command failed")
			}
			return nil
		case <-ticker.C:
			err := checkWorkspace(workDir, maxBytes, minFreeBytes)
			if err != nil {
				_ = syscall.Kill(-command.Process.Pid, syscall.SIGKILL)
				<-done
				return err
			}
		case <-ctx.Done():
			_ = syscall.Kill(-command.Process.Pid, syscall.SIGKILL)
			<-done
			return ctx.Err()
		}
	}
}

func checkWorkspace(workDir string, maxBytes, minFreeBytes int64) error {
	size, err := directoryBytes(workDir)
	if err != nil {
		return err
	}
	if size > maxBytes {
		return ErrLocalDiskBudget
	}
	return requireMinimumFree(workDir, minFreeBytes)
}

func requireMinimumFree(path string, bytes int64) error {
	err := ensureFreeSpace(path, bytes)
	if errors.Is(err, ErrCapacity) {
		return ErrLocalDiskBudget
	}
	return err
}

func directoryBytes(root string) (int64, error) {
	var total int64
	err := filepath.WalkDir(root, func(path string, entry os.DirEntry, walkErr error) error {
		if walkErr != nil {
			if path != root && errors.Is(walkErr, os.ErrNotExist) {
				return nil
			}
			return walkErr
		}
		if entry.IsDir() {
			return nil
		}
		info, err := entry.Info()
		if err != nil {
			if errors.Is(err, os.ErrNotExist) {
				return nil
			}
			return err
		}
		total += info.Size()
		return nil
	})
	return total, err
}

func ensureFreeSpace(path string, required int64) error {
	var stats syscall.Statfs_t
	if err := syscall.Statfs(path, &stats); err != nil {
		return err
	}
	blockSize := uint64(stats.Bsize)
	if blockSize == 0 {
		return ErrCapacity
	}
	requiredBlocks := uint64(required) / blockSize
	if uint64(required)%blockSize != 0 {
		requiredBlocks++
	}
	if stats.Bavail < requiredBlocks {
		return ErrCapacity
	}
	return nil
}

func verifyCheckout(repoDir string, allowed map[string]PackageFile) error {
	seen := make(map[string]struct{}, len(allowed))
	err := filepath.WalkDir(repoDir, func(path string, entry os.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		relative, err := filepath.Rel(repoDir, path)
		if err != nil {
			return err
		}
		if relative == ".git" {
			return filepath.SkipDir
		}
		if entry.IsDir() {
			return nil
		}
		file, exists := allowed[filepath.ToSlash(relative)]
		if !exists || entry.Type()&os.ModeSymlink != 0 {
			return errors.New("checkout contains an unexpected file")
		}
		info, err := entry.Info()
		if err != nil || !info.Mode().IsRegular() || info.Size() != file.Size {
			return errors.New("checked-out file differs from tree metadata")
		}
		seen[file.RepositoryPath] = struct{}{}
		return nil
	})
	if err != nil {
		return err
	}
	if len(seen) != len(allowed) {
		return errors.New("checkout omitted preflighted files")
	}
	return nil
}

type limitedArchiveWriter struct {
	writer  io.Writer
	limit   int64
	written int64
}

func (writer *limitedArchiveWriter) Write(data []byte) (int, error) {
	if int64(len(data)) > writer.limit-writer.written {
		return 0, ErrArchiveTooLarge
	}
	n, err := writer.writer.Write(data)
	writer.written += int64(n)
	return n, err
}

func writePackageArchive(ctx context.Context, repoDir, archivePath string,
	plan PackagePlan, maxBytes int64) (int64, string, error) {
	if err := ctx.Err(); err != nil {
		return 0, "", err
	}
	archive, err := os.OpenFile(archivePath, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		return 0, "", err
	}
	defer archive.Close()
	digest := sha256.New()
	limited := &limitedArchiveWriter{writer: io.MultiWriter(archive, digest), limit: maxBytes}
	zipWriter := zip.NewWriter(limited)
	for _, file := range plan.Files {
		if err := appendArchiveFile(ctx, zipWriter, repoDir, file.RepositoryPath,
			file.RelativePath, file.Size); err != nil {
			return 0, "", err
		}
	}
	for _, evidence := range plan.Evidence {
		if evidence.Bundled {
			if err := appendArchiveFile(ctx, zipWriter, repoDir, evidence.RepositoryPath,
				evidenceEntryPrefix+evidence.RepositoryPath, evidence.Size); err != nil {
				return 0, "", err
			}
		}
	}
	manifest, err := json.Marshal(rootEvidenceManifest{
		FormatVersion: 1, RootFiles: append([]RootEvidenceFile{}, plan.Evidence...),
	})
	if err != nil {
		return 0, "", err
	}
	entry, err := zipWriter.Create(evidenceManifestPath)
	if err != nil {
		return 0, "", err
	}
	if _, err := entry.Write(manifest); err != nil {
		return 0, "", err
	}
	if err := ctx.Err(); err != nil {
		return 0, "", err
	}
	if err := zipWriter.Close(); err != nil {
		return 0, "", err
	}
	if err := archive.Sync(); err != nil {
		return 0, "", err
	}
	if err := ctx.Err(); err != nil {
		return 0, "", err
	}
	return limited.written, hex.EncodeToString(digest.Sum(nil)), nil
}

type contextReader struct {
	ctx    context.Context
	reader io.Reader
}

func (reader contextReader) Read(data []byte) (int, error) {
	if err := reader.ctx.Err(); err != nil {
		return 0, err
	}
	return reader.reader.Read(data)
}

func appendArchiveFile(ctx context.Context, zipWriter *zip.Writer, repoDir, repositoryPath,
	archivePath string, expectedSize int64) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	input, err := os.Open(filepath.Join(repoDir, filepath.FromSlash(repositoryPath)))
	if err != nil {
		return err
	}
	entry, err := zipWriter.Create(archivePath)
	if err == nil {
		var copied int64
		if expectedSize == math.MaxInt64 {
			err = errors.New("preflighted file is too large")
		} else {
			copied, err = io.Copy(entry, io.LimitReader(contextReader{ctx, input}, expectedSize+1))
		}
		if err == nil && copied != expectedSize {
			err = errors.New("file changed while creating package")
		}
	}
	closeErr := input.Close()
	if err != nil {
		return err
	}
	return closeErr
}
