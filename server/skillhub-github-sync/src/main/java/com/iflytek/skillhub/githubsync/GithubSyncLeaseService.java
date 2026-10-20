package com.iflytek.skillhub.githubsync;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class GithubSyncLeaseService {
    private static final Pattern SAFE_FAILURE_DETAIL = Pattern.compile(
            "WORKER_(?:TIMEOUT|ERROR)|GITHUB_HTTP_[1-5][0-9]{2}:"
                    + "(?:RETRYABLE_FAILURE|AUTH_INVALID|SOURCE_UNREADABLE|GITHUB_RATE_LIMITED)");
    private final GithubSyncTaskRepository tasks;
    private final GithubSystemCredentialRepository credentials;
    private final GithubSyncSchedulePolicyRepository schedulePolicies;
    private final Duration leaseDuration;
    private final Duration repositoryCheckInterval;
    private final SecureRandom random = new SecureRandom();

    public GithubSyncLeaseService(GithubSyncTaskRepository tasks,
                                  GithubSystemCredentialRepository credentials,
                                  GithubSyncSchedulePolicyRepository schedulePolicies,
                                  @Value("${skillhub.github-sync.lease-duration:PT5M}") Duration leaseDuration,
                                  @Value("${skillhub.github-sync.repository-check-interval:PT24H}")
                                  Duration repositoryCheckInterval) {
        if (leaseDuration.isNegative() || leaseDuration.isZero()
                || repositoryCheckInterval.isNegative() || repositoryCheckInterval.isZero()) {
            throw new IllegalArgumentException("GitHub sync intervals must be positive");
        }
        this.tasks = tasks;
        this.credentials = credentials;
        this.schedulePolicies = schedulePolicies;
        this.leaseDuration = leaseDuration;
        this.repositoryCheckInterval = repositoryCheckInterval;
    }

    @Transactional
    public Optional<LeasedTask> claim(GithubSyncTaskKind kind, String workerId) {
        return claim(kind, workerId, List.of());
    }

    @Transactional
    public Optional<LeasedTask> claim(GithubSyncTaskKind kind, String workerId, List<String> capabilities) {
        if (capabilities == null || capabilities.size() > 32 || capabilities.stream().anyMatch(
                value -> value == null || value.isBlank() || value.length() > 128
                        || value.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("Invalid GitHub worker capabilities");
        }
        if (kind == null) {
            throw new IllegalArgumentException("task kind is required");
        }
        if (workerId == null || workerId.isBlank() || workerId.length() > 128
                || workerId.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("workerId must contain 1 to 128 printable characters");
        }
        byte[] tokenBytes = new byte[32];
        random.nextBytes(tokenBytes);
        String token = HexFormat.of().formatHex(tokenBytes);
        Instant now = Instant.now();
        Optional<GithubSyncTaskLease> claimed = capabilities.isEmpty()
                ? tasks.claim(kind, workerId, hash(token), now, now.plus(leaseDuration))
                : tasks.claim(kind, workerId, hash(token), now, now.plus(leaseDuration), capabilities);
        return claimed
                .map(task -> new LeasedTask(task, token));
    }

    @Transactional
    public Optional<Instant> renew(UUID taskId, long generation, String token) {
        if (!validLease(taskId, generation, token)) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        return tasks.renew(taskId, generation, hash(token), now, now.plus(leaseDuration));
    }

    @Transactional
    public GithubRepositoryMetadataResult updateRepositoryMetadata(UUID taskId, long generation,
            String token, long repositoryId, String owner, String name, String defaultBranch) {
        return updateRepositoryMetadata(taskId, generation, token, repositoryId, owner, name, defaultBranch, null);
    }

    @Transactional
    public GithubRepositoryMetadataResult updateRepositoryMetadata(UUID taskId, long generation,
            String token, long repositoryId, String owner, String name, String defaultBranch, Long ownerId) {
        if (!validLease(taskId, generation, token)) {
            return GithubRepositoryMetadataResult.LEASE_STALE;
        }
        if (repositoryId <= 0 || ownerId != null && ownerId <= 0 || owner == null
                || !owner.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")
                || name == null || !name.matches("[A-Za-z0-9_.-]{1,100}")
                || name.equals(".") || name.equals("..")
                || defaultBranch == null || defaultBranch.isBlank() || defaultBranch.length() > 256
                || defaultBranch.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid public GitHub repository metadata");
        }
        return tasks.updateRepositoryMetadata(taskId, generation, hash(token), repositoryId,
                owner, name, defaultBranch, ownerId, Instant.now());
    }

    @Transactional
    public GithubPackageAdmissionResult admitPackage(UUID taskId, long generation, String token,
                                                       String commitSha, String skillPath) {
        if (!validLease(taskId, generation, token)) {
            return GithubPackageAdmissionResult.rejected(GithubPackageAdmissionResult.Status.LEASE_STALE);
        }
        if (commitSha == null || !commitSha.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("commitSha must be a lowercase Git commit SHA");
        }
        // Full coordinate validation is repeated after Java reads the repository ID from its task snapshot.
        if (skillPath == null || skillPath.isEmpty()) {
            throw new IllegalArgumentException("skillPath is required");
        }
        return tasks.admitPackage(taskId, generation, hash(token), commitSha, skillPath, Instant.now());
    }

    @Transactional
    public boolean completeHeadOnly(UUID taskId, long generation, String token, String observedCommitSha) {
        if (!validLease(taskId, generation, token)) {
            return false;
        }
        if (observedCommitSha == null || !observedCommitSha.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("observedCommitSha must be a lowercase Git commit SHA");
        }
        Instant now = Instant.now();
        return tasks.completeHeadOnly(taskId, generation, hash(token), observedCommitSha,
                now, now.plus(repositoryInterval()));
    }

    @Transactional
    public Optional<Instant> fail(UUID taskId, long generation, String token,
                                  GithubSyncFailureReason reason, String detail,
                                  Quota quota, Instant retryAt) {
        if (!validLease(taskId, generation, token)) {
            return Optional.empty();
        }
        if (reason == null) {
            throw new IllegalArgumentException("failure reason is required");
        }
        Instant now = Instant.now();
        if (reason == GithubSyncFailureReason.GITHUB_RATE_LIMITED
                && (retryAt == null || !retryAt.isAfter(now))) {
            throw new IllegalArgumentException("GitHub rate limit requires a future retryAt");
        }
        if (reason == GithubSyncFailureReason.SOURCE_UNREADABLE && retryAt != null) {
            throw new IllegalArgumentException("SOURCE_UNREADABLE retry schedule is owned by Java");
        }
        if (retryAt != null && !retryAt.isAfter(now)) {
            throw new IllegalArgumentException("retryAt must be in the future");
        }
        if (detail != null && !SAFE_FAILURE_DETAIL.matcher(detail).matches()) {
            throw new IllegalArgumentException("detail must be a supported diagnostic code");
        }
        if (quota != null) {
            if (quota.resource() == null
                    || !java.util.Set.of("CORE", "SEARCH", "GRAPHQL", "OTHER").contains(quota.resource())
                    || quota.remaining() < 0 || quota.resetAt() == null) {
                throw new IllegalArgumentException("invalid GitHub quota observation");
            }
            credentials.recordQuotaForTaskLease(taskId, generation, hash(token), quota.resource(),
                    quota.remaining(), quota.resetAt(), now);
        }
        return tasks.fail(taskId, generation, hash(token), reason, detail, retryAt, now);
    }

    @Transactional
    public GithubDiscoveryBatchResult reportDiscoveryBatch(UUID taskId, long generation,
                                                             String token, GithubDiscoveryBatch batch) {
        if (!validLease(taskId, generation, token)) {
            return GithubDiscoveryBatchResult.LEASE_STALE;
        }
        if (batch == null) {
            throw new IllegalArgumentException("discovery batch is required");
        }
        return tasks.recordDiscoveryBatch(taskId, generation, hash(token), batch,
                hashDiscoveryBatch(batch), Instant.now());
    }

    @Transactional
    public GithubDiscoveryCompletionResult completeDiscovery(UUID taskId, long generation, String token,
            String checkpoint, boolean complete, Instant retryAt, GithubSyncDeferralReason deferralReason) {
        if (!validLease(taskId, generation, token)) {
            return GithubDiscoveryCompletionResult.LEASE_STALE;
        }
        if (checkpoint == null || checkpoint.isBlank() || checkpoint.length() > 4096
                || checkpoint.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("a persisted discovery checkpoint is required");
        }
        Instant now = Instant.now();
        if ((complete && (retryAt != null || deferralReason != null))
                || (!complete && (retryAt == null || !retryAt.isAfter(now)))) {
            throw new IllegalArgumentException("discovery coverage and retry schedule disagree");
        }
        return tasks.completeDiscovery(taskId, generation, hash(token), checkpoint,
                complete, retryAt, deferralReason, now);
    }

    @Transactional
    public boolean completePreview(UUID taskId, long generation, String token, String resultJson) {
        if (!validLease(taskId, generation, token) || resultJson == null || resultJson.length() > 32768) {
            return false;
        }
        return tasks.completePreview(taskId, generation, hash(token), resultJson, Instant.now());
    }

    @Transactional
    public GithubRepositoryCompletionResult completeRepository(UUID taskId, long generation, String token,
            String commitSha, boolean complete, String pathCheckpoint, List<String> incompleteSubtrees,
            List<GithubRepositoryPathResult> pathResults, Instant retryAt,
            GithubSyncDeferralReason deferralReason) {
        if (!validLease(taskId, generation, token)) {
            return GithubRepositoryCompletionResult.LEASE_STALE;
        }
        if (commitSha == null || !commitSha.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("observedCommitSha must be a lowercase Git commit SHA");
        }
        if (pathCheckpoint != null) {
            new RepositorySkillCoordinate(1, 1, pathCheckpoint);
        }
        List<String> subtrees = incompleteSubtrees == null ? List.of() : incompleteSubtrees;
        List<GithubRepositoryPathResult> results = pathResults == null ? List.of() : pathResults;
        if (subtrees.size() > 1000 || results.size() > 10000) {
            throw new IllegalArgumentException("repository completion exceeds path budget");
        }
        var seen = new HashSet<String>();
        for (String subtree : subtrees) {
            new RepositorySkillCoordinate(1, 1, subtree);
            if (!seen.add(subtree)) {
                throw new IllegalArgumentException("duplicate incomplete subtree");
            }
        }
        seen.clear();
        boolean retryable = false;
        for (GithubRepositoryPathResult result : results) {
            if (result == null || result.outcome() == null) {
                throw new IllegalArgumentException("path outcome is required");
            }
            new RepositorySkillCoordinate(1, 1, result.skillPath());
            if (!seen.add(result.skillPath()) || result.reason() != null && result.reason().length() > 500
                    || result.outcome() != GithubRepositoryPathResult.Outcome.ACCEPTED
                    && result.ingestionId() != null
                    || result.outcome() == GithubRepositoryPathResult.Outcome.ACCEPTED
                    && result.ingestionId() == null) {
                throw new IllegalArgumentException("invalid repository path result");
            }
            retryable |= result.outcome() == GithubRepositoryPathResult.Outcome.RETRYABLE_FAILURE;
        }
        Instant now = Instant.now();
        if (complete && (retryAt != null || deferralReason != null || retryable || !subtrees.isEmpty())
                || !complete && (retryAt == null || !retryAt.isAfter(now))) {
            throw new IllegalArgumentException("repository coverage and retry schedule disagree");
        }
        return tasks.completeRepository(taskId, generation, hash(token), commitSha, complete,
                pathCheckpoint, subtrees, results, retryAt, deferralReason, now,
                now.plus(repositoryInterval()));
    }

    private Duration repositoryInterval() {
        Integer hours = schedulePolicies.get().repositoryHours();
        return hours == null ? repositoryCheckInterval : Duration.ofHours(hours);
    }

    private String hashDiscoveryBatch(GithubDiscoveryBatch batch) {
        MessageDigest digest = newDigest();
        updateDigest(digest, batch.previousCheckpoint());
        updateDigest(digest, batch.checkpoint());
        updateDigest(digest, Integer.toString(batch.deferredCount()));
        updateDigest(digest, batch.deferralReason() == null ? null : batch.deferralReason().name());
        batch.repositories().stream().sorted(Comparator.comparingLong(GithubDiscoveryCandidate::repositoryId))
                .forEach(repository -> {
                    updateDigest(digest, Long.toString(repository.repositoryId()));
                    updateDigest(digest, repository.owner());
                    updateDigest(digest, repository.name());
                    updateDigest(digest, repository.defaultBranch());
                });
        return HexFormat.of().formatHex(digest.digest());
    }

    private void updateDigest(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(4).putInt(-1).array());
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private boolean validLease(UUID taskId, long generation, String token) {
        return taskId != null && generation >= 1 && token != null && token.length() == 64
                && token.chars().allMatch(value -> (value >= '0' && value <= '9')
                || (value >= 'a' && value <= 'f'));
    }

    private String hash(String token) {
        return HexFormat.of().formatHex(newDigest().digest(token.getBytes(StandardCharsets.UTF_8)));
    }

    private MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record LeasedTask(GithubSyncTaskLease task, String token) {
    }

    public record Quota(String resource, int remaining, Instant resetAt) {
    }
}
