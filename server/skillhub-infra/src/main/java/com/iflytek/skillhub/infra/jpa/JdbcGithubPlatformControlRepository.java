package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.githubsync.GithubOfficialLifecycleRepository;
import com.iflytek.skillhub.githubsync.GithubOfficialProtocolException;
import com.iflytek.skillhub.githubsync.GithubPlatformControlRepository;
import com.iflytek.skillhub.githubsync.GithubRepositoryCompletionResult;
import com.iflytek.skillhub.githubsync.GithubSyncDeferralReason;
import com.iflytek.skillhub.githubsync.GithubSyncLeaseService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class JdbcGithubPlatformControlRepository implements GithubPlatformControlRepository {
    private final JdbcTemplate jdbc;
    private final GithubSyncLeaseService leases;

    public JdbcGithubPlatformControlRepository(JdbcTemplate jdbc, GithubSyncLeaseService leases) {
        this.jdbc = jdbc;
        this.leases = leases;
    }

    @Override
    public State get() {
        return jdbc.queryForObject("""
                SELECT c.*, count(t.id) AS pending,
                       count(t.id) FILTER (WHERE t.lease_expires_at IS NULL OR t.lease_expires_at <= clock_timestamp()) AS expired
                FROM github_platform_collection_control c
                LEFT JOIN github_sync_task t ON t.namespace_id=c.namespace_id
                  AND t.platform_pause_version=c.version AND t.platform_stop_ack_at IS NULL
                GROUP BY c.namespace_id
                """, (r, n) -> new State(r.getLong("namespace_id"), r.getLong("version"), r.getBoolean("paused"),
                !r.getBoolean("paused") ? "RUNNING" : r.getLong("pending") == 0 ? "PAUSED" : "PAUSING",
                r.getBoolean("paused") ? r.getLong("pending") : 0,
                r.getBoolean("paused") ? r.getLong("expired") : 0, r.getTimestamp("updated_at").toInstant()));
    }

    @Override
    public State update(long expectedVersion, boolean paused, String actor) {
        var current = lock(false);
        if (current.version() != expectedVersion) {
            // A retry of the transition just acknowledged by this client is harmless.
            require(current.version() == expectedVersion + 1 && current.desiredPaused() == paused, "CONTROL_CHANGED");
            return get();
        }
        if (current.desiredPaused() == paused) {
            return get();
        }
        jdbc.update("UPDATE github_platform_collection_control SET paused=?,version=version+1,updated_at=clock_timestamp(),updated_by=? WHERE namespace_id=?",
                paused, actor, current.namespaceId());
        if (paused) {
            jdbc.update("""
                    UPDATE github_sync_task SET platform_pause_version=?,platform_pause_generation=lease_generation,
                        platform_pause_token_hash=lease_token_hash,platform_stop_ack_at=NULL
                    WHERE namespace_id=? AND status='LEASED'
                    """, current.version() + 1, current.namespaceId());
        }
        return get();
    }

    @Override
    public Control control(UUID taskId, long generation, String token) {
        var state = lock(true);
        var task = task(taskId);
        require(task.namespace() == state.namespaceId(), "PLATFORM_SCOPE_MISMATCH");
        verifyProof(task, generation, hash(token));
        require("LEASED".equals(task.status()) && task.unexpired()
                || state.desiredPaused() && task.pauseVersion() != null && task.pauseVersion() == state.version()
                   && task.pauseGeneration() != null && task.pauseGeneration() == generation,
                "LEASE_STALE");
        return new Control(state.desiredPaused() ? "PAUSE" : "CONTINUE", state.version());
    }

    @Override
    public void pause(UUID taskId, long generation, String token, long observedVersion,
                      String kind, String checkpoint, GithubOfficialLifecycleRepository.RepositoryProgress progress) {
        var state = lock(true);
        require(state.desiredPaused() && state.version() == observedVersion, "CONTROL_CHANGED");
        // Match the admission -> task lock order when validating received repository paths.
        jdbc.queryForObject("SELECT id FROM github_sync_admission_policy WHERE id FOR UPDATE", Boolean.class);
        var task = task(taskId);
        String digest = hash(token);
        require(task.namespace() == state.namespaceId() && task.pauseVersion() != null
                && task.pauseVersion() == observedVersion && task.pauseGeneration() != null
                && task.pauseGeneration() == generation && Objects.equals(task.pauseToken(), digest)
                && task.generation() == generation, "LEASE_STALE");
        require(Objects.equals(kind, task.kind()), "RESULT_CONFLICT");
        if (task.acknowledged()) {
            return;
        }
        boolean active = "LEASED".equals(task.status()) && task.unexpired();
        if (active) {
            require(Objects.equals(task.token(), digest), "LEASE_STALE");
            if ("DISCOVERY".equals(kind)) {
                require(progress == null && Objects.equals(checkpoint, task.checkpoint()), "CHECKPOINT_CONFLICT");
            } else {
                require(checkpoint == null && progress != null && progress.incompleteSubtrees() != null
                        && progress.pathResults() != null, "RESULT_CONFLICT");
                if (progress.observedCommitSha() == null) {
                    require(task.commit() == null && progress.pathCheckpoint() == null
                            && progress.incompleteSubtrees().isEmpty() && progress.pathResults().isEmpty(), "COMMIT_MISMATCH");
                    require(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM github_sync_package_admission WHERE task_id=?)", Boolean.class, taskId)), "COMMIT_MISMATCH");
                } else if ("FULL".equals(task.mode())) {
                    var result = leases.completeRepository(taskId, generation, token, progress.observedCommitSha(), false,
                            progress.pathCheckpoint(), progress.incompleteSubtrees(), progress.pathResults(),
                            Instant.now().plusSeconds(60), GithubSyncDeferralReason.CAPACITY);
                    require(result == GithubRepositoryCompletionResult.RETRY_WAIT, "RESULT_CONFLICT");
                } else {
                    require(progress.pathCheckpoint() == null && progress.pathResults().isEmpty()
                            && progress.incompleteSubtrees().isEmpty(), "RESULT_CONFLICT");
                    require(task.commit() == null || Objects.equals(task.commit(), progress.observedCommitSha()), "COMMIT_MISMATCH");
                }
            }
        }
        jdbc.update("""
                UPDATE github_sync_package_admission SET status='RELEASED',updated_at=clock_timestamp()
                WHERE task_id=? AND lease_generation=? AND status='ACTIVE'
                """, taskId, generation);
        // Expired or already-settled work only acknowledges stopping. Never write its proposed progress.
        jdbc.update("UPDATE github_sync_task SET platform_stop_ack_at=clock_timestamp() WHERE id=?", taskId);
        if ("LEASED".equals(task.status())) {
            jdbc.update("""
                    UPDATE github_sync_task SET status='QUEUED',lease_token_hash=NULL,lease_expires_at=NULL,
                        leased_by=NULL,credential_id=NULL,credential_fingerprint=NULL,due_at=clock_timestamp(),
                        retry_at=NULL,updated_at=clock_timestamp() WHERE id=?
                    """, taskId);

        }
    }

    private State lock(boolean shared) {
        return jdbc.queryForObject(
                "SELECT * FROM github_platform_collection_control FOR " + (shared ? "SHARE" : "UPDATE"),
                (result, row) -> new State(result.getLong("namespace_id"), result.getLong("version"),
                        result.getBoolean("paused"), null, 0, 0, result.getTimestamp("updated_at").toInstant()));
    }

    private record Task(long namespace, String kind, String mode, String status, long generation, String token,
                        String checkpoint, String commit, boolean unexpired, Long pauseVersion, Long pauseGeneration,
                        String pauseToken, boolean acknowledged) {}

    private Task task(UUID id) {
        return jdbc.query("""
                SELECT *, lease_expires_at > clock_timestamp() AS unexpired
                FROM github_sync_task WHERE id = ? FOR UPDATE
                """, result -> {
            require(result.next(), "LEASE_STALE");
            return new Task(result.getLong("namespace_id"), result.getString("kind"), result.getString("mode"),
                    result.getString("status"), result.getLong("lease_generation"), result.getString("lease_token_hash"),
                    result.getString("discovery_checkpoint"), result.getString("fixed_commit_sha"),
                    result.getBoolean("unexpired"), result.getObject("platform_pause_version", Long.class),
                    result.getObject("platform_pause_generation", Long.class), result.getString("platform_pause_token_hash"),
                    result.getTimestamp("platform_stop_ack_at") != null);
        }, id);
    }

    private void verifyProof(Task task, long generation, String digest) {
        require(task.generation() == generation && (Objects.equals(task.token(), digest)
                || task.pauseGeneration() != null && task.pauseGeneration() == generation
                    && Objects.equals(task.pauseToken(), digest)), "LEASE_STALE");
    }

    private static String hash(String token) {
        require(token != null && token.matches("[0-9a-fA-F]{64}"), "LEASE_STALE");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void require(boolean valid, String code) {
        if (!valid) {
            throw new GithubOfficialProtocolException(409, code);
        }
    }
}
