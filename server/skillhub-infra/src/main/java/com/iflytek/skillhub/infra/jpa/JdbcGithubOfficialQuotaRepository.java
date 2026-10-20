package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.githubsync.GithubOfficialQuotaRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcGithubOfficialQuotaRepository implements GithubOfficialQuotaRepository {
    private final JdbcTemplate jdbc;

    public JdbcGithubOfficialQuotaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public boolean record(UUID taskId, long generation, String leaseTokenHash, UUID expectedCredentialId,
                          Observation observation, Instant now) {
        validate(observation, now);
        var leases = jdbc.query("""
                SELECT task.credential_id, task.credential_fingerprint, CASE WHEN task.kind = 'REPOSITORY_SYNC'
                    OR task.snapshot_json -> 'officialDiscovery' ->> 'channel' = 'OWNER_EXPANSION'
                    OR task.snapshot_json ->> 'entryType' IN ('CURATED_OWNER', 'CURATED_REPOSITORY')
                    THEN 'CORE' ELSE 'SEARCH' END AS resource
                FROM github_sync_task task
                LEFT JOIN github_system_credential credential ON credential.id = task.credential_id
                WHERE task.id = ? AND task.status = 'LEASED' AND task.lease_generation = ?
                    AND task.lease_token_hash = ? AND task.lease_expires_at > CASE WHEN task.official_query_id IS NOT NULL THEN clock_timestamp() ELSE ? END
                    AND (?::uuid IS NULL OR task.credential_id = ?)
                    AND (?::uuid IS NOT NULL OR task.required_capabilities @> ARRAY['official-pause/v1'])
                    AND (task.credential_id IS NULL OR task.credential_fingerprint = credential.fingerprint)
                FOR UPDATE OF task
                """, (result, row) -> new Lease(result.getObject("credential_id", UUID.class), result.getString("credential_fingerprint"), result.getString("resource")),
                taskId, generation, leaseTokenHash, Timestamp.from(now), expectedCredentialId, expectedCredentialId, expectedCredentialId);
        if (leases.isEmpty()) {
            return false;
        }
        Lease lease = leases.getFirst();
        if (lease.credentialId() != null) {
            // Lock task, then credential: replacement never locks tasks. Recheck after waiting
            // so a report accepted against the old fingerprint cannot poison a replacement.
            var fingerprints = jdbc.query("SELECT fingerprint FROM github_system_credential WHERE id=? FOR UPDATE",
                    (result, row) -> result.getString(1), lease.credentialId());
            if (fingerprints.isEmpty() || !java.util.Objects.equals(lease.fingerprint(), fingerprints.getFirst())) {
                return false;
            }
        }
        if (!lease.resource().equals(observation.resource())
                && !("UNKNOWN".equals(observation.state()) && "OTHER".equals(observation.resource()))) {
            throw new IllegalArgumentException("Quota resource differs from leased GitHub operation");
        }
        Instant validUntil = observation.observedAt().plusSeconds(300);
        if (validUntil.isAfter(now.plusSeconds(300))) {
            validUntil = now.plusSeconds(300);
        }
        if (observation.resetAt() != null && observation.resetAt().isBefore(validUntil)) {
            validUntil = observation.resetAt();
        }
        String key = lease.credentialId() == null ? "anonymous" : "credential:" + lease.credentialId();
        jdbc.update("""
                INSERT INTO github_official_quota_observation
                    (observation_key, resource, credential_id, state, remaining, reset_at, observed_at, received_at, valid_until)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (observation_key, resource) DO UPDATE SET state = EXCLUDED.state,
                    remaining = EXCLUDED.remaining, reset_at = EXCLUDED.reset_at, observed_at = EXCLUDED.observed_at,
                    received_at = EXCLUDED.received_at, valid_until = EXCLUDED.valid_until
                WHERE github_official_quota_observation.observed_at <= EXCLUDED.observed_at
                """, key, observation.resource(), lease.credentialId(), observation.state(), observation.remaining(),
                timestamp(observation.resetAt()), Timestamp.from(observation.observedAt()), Timestamp.from(now), Timestamp.from(validUntil));
        // Authenticated primary limits belong to the leased credential and API resource.
        // Distinct configured tokens may share an upstream identity; this permits trying an
        // unexhausted token, not summing or assuming their allowances are independent.
        if (lease.credentialId() != null && List.of("OBSERVED", "PRIMARY_EXHAUSTED").contains(observation.state())) {
            jdbc.update("""
                    INSERT INTO github_system_credential_quota
                        (credential_id, resource, remaining, reset_at, observed_at) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (credential_id, resource) DO UPDATE SET remaining=EXCLUDED.remaining,
                        reset_at=EXCLUDED.reset_at, observed_at=EXCLUDED.observed_at
                    WHERE github_system_credential_quota.observed_at <= EXCLUDED.observed_at
                    """, lease.credentialId(), observation.resource(), observation.remaining(),
                    timestamp(observation.resetAt()), Timestamp.from(observation.observedAt()));
        }
        if (lease.credentialId() == null && ("PRIMARY_EXHAUSTED".equals(observation.state())
                || "OBSERVED".equals(observation.state()) && Integer.valueOf(0).equals(observation.remaining())
                && observation.resetAt().isAfter(now))) {
            jdbc.update("""
                    INSERT INTO github_official_resource_cooldown (resource, primary_until) VALUES (?, ?)
                    ON CONFLICT (resource) DO UPDATE SET primary_until = GREATEST(
                        github_official_resource_cooldown.primary_until, EXCLUDED.primary_until)
                    """, observation.resource(), Timestamp.from(observation.resetAt()));
        }
        if ("SECONDARY_COOLDOWN".equals(observation.state())
                || "UNKNOWN".equals(observation.state()) && observation.retryAfterSeconds() != null
                || "AUTH_INVALID".equals(observation.state()) && lease.credentialId() == null) {
            Instant until = observation.observedAt().plusSeconds(observation.retryAfterSeconds() == null ? 60 : Math.max(1, observation.retryAfterSeconds()));
            jdbc.update("UPDATE github_official_secondary_cooldown SET cooldown_until = GREATEST(cooldown_until, ?) WHERE id = TRUE",
                    Timestamp.from(until));
        }
        if ("AUTH_INVALID".equals(observation.state()) && lease.credentialId() != null) {
            jdbc.update("UPDATE github_system_credential SET status = 'INVALID', rate_limited_until = NULL, updated_at = ? WHERE id = ?",
                    Timestamp.from(now), lease.credentialId());
        }
        return true;
    }

    @Override
    public Instant cooldown(String resource, Instant now) {
        return jdbc.query("""
                SELECT max(blocked_until) FROM (
                    SELECT cooldown_until AS blocked_until FROM github_official_secondary_cooldown WHERE id = TRUE
                    UNION ALL SELECT primary_until FROM github_official_resource_cooldown WHERE resource = ?
                        AND NOT EXISTS (SELECT 1 FROM github_system_credential)
                ) cooldown WHERE blocked_until > ?
                """, result -> result.next() && result.getTimestamp(1) != null ? result.getTimestamp(1).toInstant() : null,
                resource, Timestamp.from(now));
    }

    @Override
    public Instant cooldownForLease(UUID taskId, Instant now) {
        return jdbc.query("""
                WITH leased AS (
                    SELECT credential_id, CASE WHEN kind='REPOSITORY_SYNC'
                        OR snapshot_json -> 'officialDiscovery' ->> 'channel'='OWNER_EXPANSION'
                        OR snapshot_json ->> 'entryType' IN ('CURATED_OWNER','CURATED_REPOSITORY')
                        THEN 'CORE' ELSE 'SEARCH' END AS resource
                    FROM github_sync_task WHERE id=?
                ) SELECT max(blocked_until) FROM (
                    SELECT cooldown_until AS blocked_until FROM github_official_secondary_cooldown WHERE id
                    UNION ALL SELECT c.primary_until FROM github_official_resource_cooldown c
                        JOIN leased l ON l.credential_id IS NULL AND l.resource=c.resource
                    UNION ALL SELECT q.reset_at FROM github_system_credential_quota q
                        JOIN leased l ON l.credential_id=q.credential_id AND l.resource=q.resource
                        WHERE q.remaining=0
                ) cooldown WHERE blocked_until>?
                """, result -> result.next() && result.getTimestamp(1)!=null ? result.getTimestamp(1).toInstant() : null,
                taskId, Timestamp.from(now));
    }

    @Override
    public void deferClaim(UUID taskId, long generation, Instant due, Instant now, String reason) {
        if (!List.of("QUOTA_COOLDOWN", "CREDENTIAL_UNAVAILABLE").contains(reason)) {
            throw new IllegalArgumentException("Invalid credential deferral reason");
        }
        int count = jdbc.update("""
                UPDATE github_sync_task SET status = 'RETRY_WAIT', due_at = ?, retry_at = ?, official_pause_reason = ?,
                    lease_token_hash = NULL, lease_expires_at = NULL, leased_by = NULL, updated_at = ?
                WHERE id = ? AND status = 'LEASED' AND lease_generation = ?
                """, Timestamp.from(due), Timestamp.from(due), reason, Timestamp.from(now), taskId, generation);
        if (count != 1) {
            throw new IllegalStateException("Credential deferral lost its newly claimed task");
        }
    }

    @Override
    public void clearClaimWait(UUID taskId, long generation) {
        jdbc.update("""
                UPDATE github_sync_task SET official_pause_reason = NULL
                WHERE id = ? AND status = 'LEASED' AND lease_generation = ?
                    AND official_pause_reason IN ('QUOTA_COOLDOWN','CREDENTIAL_UNAVAILABLE')
                """, taskId, generation);
        jdbc.update("""
                UPDATE github_deep_discovery_query q SET wait_reason = NULL
                FROM github_sync_task t WHERE t.id = ? AND t.status = 'LEASED'
                    AND t.lease_generation = ? AND q.active_task_id = t.id
                    AND q.wait_reason IN ('QUOTA_COOLDOWN','CREDENTIAL_UNAVAILABLE')
                """, taskId, generation);
    }

    private void validate(Observation value, Instant now) {
        if (value == null || value.resource() == null || !List.of("CORE", "SEARCH", "GRAPHQL", "OTHER").contains(value.resource())
                || value.state() == null || !List.of("OBSERVED", "UNKNOWN", "PRIMARY_EXHAUSTED", "SECONDARY_COOLDOWN", "AUTH_INVALID").contains(value.state())
                || value.observedAt() == null || value.observedAt().isBefore(now.minusSeconds(300)) || value.observedAt().isAfter(now.plusSeconds(300))
                || value.remaining() != null && value.remaining() < 0
                || value.resetAt() != null && value.resetAt().isAfter(now.plusSeconds(86400))
                || value.retryAfterSeconds() != null && (value.retryAfterSeconds() < 0 || value.retryAfterSeconds() > 86400)) {
            throw new IllegalArgumentException("Invalid official GitHub quota observation");
        }
        if (("OBSERVED".equals(value.state()) || "PRIMARY_EXHAUSTED".equals(value.state()))
                && (value.remaining() == null || value.resetAt() == null)
                || "PRIMARY_EXHAUSTED".equals(value.state()) && (!Integer.valueOf(0).equals(value.remaining()) || !value.resetAt().isAfter(now))
                || "UNKNOWN".equals(value.state()) && (value.remaining() != null || value.resetAt() != null)) {
            throw new IllegalArgumentException("Quota state and values disagree");
        }
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private record Lease(UUID credentialId, String fingerprint, String resource) {}
}
