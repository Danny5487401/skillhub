package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.githubsync.GithubSystemCredential;
import com.iflytek.skillhub.githubsync.GithubCredentialQuota;
import com.iflytek.skillhub.githubsync.GithubSystemCredentialRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcGithubSystemCredentialRepository implements GithubSystemCredentialRepository {
    private static final String FIELDS = "id, label, fingerprint, key_version, nonce, ciphertext, "
            + "status, rate_limited_until, last_used_at, created_at, updated_at";
    private final JdbcTemplate jdbc;

    public JdbcGithubSystemCredentialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<GithubSystemCredential> list() {
        return jdbc.query("SELECT " + FIELDS + " FROM github_system_credential ORDER BY created_at DESC, id DESC",
                JdbcGithubSystemCredentialRepository::map);
    }

    @Override
    public List<GithubCredentialQuota> quotas(UUID credentialId) {
        return jdbc.query("SELECT resource, remaining, reset_at, observed_at "
                        + "FROM github_system_credential_quota WHERE credential_id = ? ORDER BY resource",
                (result, row) -> new GithubCredentialQuota(result.getString("resource"),
                        result.getInt("remaining"), instant(result, "reset_at"),
                        instant(result, "observed_at")), credentialId);
    }

    @Override
    public Optional<GithubSystemCredential> find(UUID id) {
        return jdbc.query("SELECT " + FIELDS + " FROM github_system_credential WHERE id = ?",
                JdbcGithubSystemCredentialRepository::map, id).stream().findFirst();
    }

    @Override
    public boolean hasConfiguredCredentials() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM github_system_credential)", Boolean.class));
    }

    @Override
    public Optional<GithubSystemCredential> availableForClaim(Instant now, String resource) {
        if (resource == null || !List.of("CORE", "SEARCH", "GRAPHQL", "OTHER").contains(resource)) {
            throw new IllegalArgumentException("Invalid GitHub quota resource");
        }
        return jdbc.query("SELECT " + FIELDS + " FROM github_system_credential credential "
                        + "WHERE status = 'ACTIVE' AND (rate_limited_until IS NULL OR rate_limited_until <= ?) "
                        + "AND NOT EXISTS (SELECT 1 FROM github_system_credential_quota quota "
                        + "WHERE quota.credential_id = credential.id AND quota.resource = ? "
                        + "AND quota.remaining = 0 AND quota.reset_at > ?) "
                        + "ORDER BY last_used_at ASC NULLS FIRST, id LIMIT 1 FOR UPDATE SKIP LOCKED",
                JdbcGithubSystemCredentialRepository::map, Timestamp.from(now), resource,
                Timestamp.from(now)).stream().findFirst();
    }

    @Override
    public void create(GithubSystemCredential credential) {
        jdbc.update("INSERT INTO github_system_credential "
                        + "(id, label, fingerprint, key_version, nonce, ciphertext, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')",
                credential.id(), credential.label(), credential.fingerprint(), credential.keyVersion(),
                credential.nonce(), credential.ciphertext());
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public boolean replace(UUID id, String label, String fingerprint, String keyVersion, byte[] nonce, byte[] ciphertext) {
        var previous = jdbc.query("SELECT fingerprint FROM github_system_credential WHERE id=? FOR UPDATE",
                (result, row) -> result.getString(1), id);
        if (previous.isEmpty()) {
            return false;
        }
        jdbc.update("UPDATE github_system_credential SET label = ?, fingerprint = ?, key_version = ?, "
                        + "nonce = ?, ciphertext = ?, status = 'ACTIVE', rate_limited_until = NULL, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                label, fingerprint, keyVersion, nonce, ciphertext, id);
        if (!previous.getFirst().equals(fingerprint)) {
            jdbc.update("DELETE FROM github_system_credential_quota WHERE credential_id=?", id);
            jdbc.update("DELETE FROM github_official_quota_observation WHERE credential_id=?", id);
        }
        return true;
    }

    @Override
    public boolean setEnabled(UUID id, boolean enabled) {
        return jdbc.update("UPDATE github_system_credential SET status = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND status IN ('ACTIVE', 'DISABLED')",
                enabled ? "ACTIVE" : "DISABLED", id) == 1;
    }

    @Override
    public boolean assignToLease(UUID taskId, long generation, UUID credentialId,
                                 String fingerprint, Instant now) {
        int updated = jdbc.update("UPDATE github_sync_task SET credential_id = ?, credential_fingerprint = ? "
                        + "WHERE id = ? AND lease_generation = ? AND status = 'LEASED'",
                credentialId, fingerprint, taskId, generation);
        if (updated == 1) {
            jdbc.update("UPDATE github_system_credential SET last_used_at = ? WHERE id = ?",
                    Timestamp.from(now), credentialId);
        }
        return updated == 1;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public boolean markInvalid(UUID taskId, long generation, String leaseTokenHash,
                               UUID credentialId, Instant now) {
        if (lockCredentialForLease(taskId, generation, leaseTokenHash, credentialId, now) == null) {
            return false;
        }
        return jdbc.update("UPDATE github_system_credential AS credential "
                        + "SET status = 'INVALID', rate_limited_until = NULL, updated_at = ? "
                        + "FROM github_sync_task AS task "
                        + "WHERE credential.id = ? AND credential.status = 'ACTIVE' "
                        + "AND task.id = ? AND task.status = 'LEASED' "
                        + "AND task.credential_id = credential.id "
                        + "AND task.credential_fingerprint = credential.fingerprint "
                        + "AND task.lease_generation = ? "
                        + "AND task.lease_token_hash = ? AND task.lease_expires_at > CASE WHEN task.official_query_id IS NOT NULL THEN clock_timestamp() ELSE ? END",
                Timestamp.from(now), credentialId, taskId, generation, leaseTokenHash,
                Timestamp.from(now)) == 1;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public boolean markRateLimited(UUID taskId, long generation, String leaseTokenHash,
                                   UUID credentialId, Instant retryAt, Instant now) {
        if (lockCredentialForLease(taskId, generation, leaseTokenHash, credentialId, now) == null) {
            return false;
        }
        return jdbc.update("UPDATE github_system_credential AS credential "
                        + "SET rate_limited_until = GREATEST(COALESCE(credential.rate_limited_until, ?), ?), "
                        + "updated_at = ? FROM github_sync_task AS task "
                        + "WHERE credential.id = ? AND credential.status = 'ACTIVE' "
                        + "AND task.id = ? AND task.status = 'LEASED' "
                        + "AND task.credential_id = credential.id "
                        + "AND task.credential_fingerprint = credential.fingerprint "
                        + "AND task.lease_generation = ? "
                        + "AND task.lease_token_hash = ? AND task.lease_expires_at > CASE WHEN task.official_query_id IS NOT NULL THEN clock_timestamp() ELSE ? END",
                Timestamp.from(retryAt), Timestamp.from(retryAt), Timestamp.from(now),
                credentialId, taskId, generation, leaseTokenHash, Timestamp.from(now)) == 1;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public boolean recordQuotaForLease(UUID taskId, long generation, String leaseTokenHash,
                                       UUID credentialId, String resource, int remaining,
                                       Instant resetAt, Instant now) {
        UUID id = lockCredentialForLease(taskId, generation, leaseTokenHash, credentialId, now);
        if (id == null) return false;
        return writeQuota(id, resource, remaining, resetAt, now);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public boolean recordQuotaForTaskLease(UUID taskId, long generation, String leaseTokenHash,
                                            String resource, int remaining, Instant resetAt, Instant now) {
        UUID id = lockCredentialForLease(taskId, generation, leaseTokenHash, null, now);
        if (id == null) return false;
        return writeQuota(id, resource, remaining, resetAt, now);
    }

    private UUID lockCredentialForLease(UUID taskId, long generation, String leaseTokenHash,
                                       UUID expectedId, Instant now) {
        var tasks = jdbc.query("""
                SELECT credential_id, credential_fingerprint FROM github_sync_task
                WHERE id=? AND status='LEASED' AND lease_generation=? AND lease_token_hash=?
                    AND credential_id IS NOT NULL AND (?::uuid IS NULL OR credential_id=?)
                    AND lease_expires_at > CASE WHEN official_query_id IS NOT NULL THEN clock_timestamp() ELSE ? END
                FOR UPDATE
                """, (result, row) -> new LeaseCredential(result.getObject("credential_id", UUID.class),
                        result.getString("credential_fingerprint")),
                taskId, generation, leaseTokenHash, expectedId, expectedId, Timestamp.from(now));
        if (tasks.isEmpty()) return null;
        var lease = tasks.getFirst();
        var current = jdbc.query("SELECT fingerprint,status FROM github_system_credential WHERE id=? FOR UPDATE",
                (result, row) -> new String[]{result.getString(1), result.getString(2)}, lease.id());
        if (current.isEmpty() || !"ACTIVE".equals(current.getFirst()[1])
                || !java.util.Objects.equals(lease.fingerprint(), current.getFirst()[0])) return null;
        return lease.id();
    }

    private record LeaseCredential(UUID id, String fingerprint) {}

    @Override
    public void recordQuota(UUID credentialId, String resource, int remaining,
                            Instant resetAt, Instant now) {
        writeQuota(credentialId, resource, remaining, resetAt, now);
    }

    private boolean writeQuota(UUID credentialId, String resource, int remaining, Instant resetAt, Instant now) {
        return jdbc.update("INSERT INTO github_system_credential_quota "
                        + "(credential_id, resource, remaining, reset_at, observed_at) VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT (credential_id, resource) DO UPDATE SET remaining = EXCLUDED.remaining, "
                        + "reset_at = EXCLUDED.reset_at, observed_at = EXCLUDED.observed_at "
                        + "WHERE github_system_credential_quota.observed_at <= EXCLUDED.observed_at",
                credentialId, resource, remaining, Timestamp.from(resetAt), Timestamp.from(now)) == 1;
    }

    private static GithubSystemCredential map(ResultSet result, int row) throws SQLException {
        return new GithubSystemCredential(result.getObject("id", UUID.class), result.getString("label"),
                result.getString("fingerprint"), result.getString("key_version"),
                result.getBytes("nonce"), result.getBytes("ciphertext"), result.getString("status"),
                instant(result, "rate_limited_until"), instant(result, "last_used_at"),
                instant(result, "created_at"), instant(result, "updated_at"));
    }

    private static Instant instant(ResultSet result, String name) throws SQLException {
        Timestamp value = result.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }
}
