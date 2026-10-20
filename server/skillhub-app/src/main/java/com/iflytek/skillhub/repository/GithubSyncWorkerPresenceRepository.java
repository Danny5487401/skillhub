package com.iflytek.skillhub.repository;

import com.iflytek.skillhub.dto.GithubSyncWorkerStatusResponse;
import com.iflytek.skillhub.dto.GithubOfficialWorkerAvailabilityResponse;
import com.iflytek.skillhub.githubsync.GithubOfficialDiscoveryCatalog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Operational status shared by the worker heartbeat and the admin read model. */
@Repository
public class GithubSyncWorkerPresenceRepository {
    private final JdbcTemplate jdbc;

    public GithubSyncWorkerPresenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void heartbeat() {
        jdbc.update("""
                INSERT INTO github_sync_worker_presence (id, last_seen_at)
                VALUES (1, CURRENT_TIMESTAMP)
                ON CONFLICT (id) DO UPDATE SET last_seen_at =
                    GREATEST(github_sync_worker_presence.last_seen_at, CURRENT_TIMESTAMP)
                """);
    }

    @Transactional
    public void heartbeat(String workerId, int protocolMajor, List<String> capabilities) {
        heartbeat();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO github_sync_worker_instance (worker_id, protocol_major, capabilities, last_seen_at)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                    ON CONFLICT (worker_id) DO UPDATE SET protocol_major = EXCLUDED.protocol_major,
                        capabilities = EXCLUDED.capabilities,
                        last_seen_at = GREATEST(github_sync_worker_instance.last_seen_at, EXCLUDED.last_seen_at)
                    WHERE github_sync_worker_instance.last_seen_at <= EXCLUDED.last_seen_at
                    """);
            statement.setString(1, workerId);
            statement.setInt(2, protocolMajor);
            statement.setArray(3, connection.createArrayOf("text", capabilities.toArray()));
            return statement;
        });
    }

    public GithubOfficialWorkerAvailabilityResponse officialStatus() {
        var required = new java.util.ArrayList<>(GithubOfficialDiscoveryCatalog.OWNER_REQUIRED_CAPABILITIES);
        required.add(com.iflytek.skillhub.githubsync.GithubPlatformControlRepository.CAPABILITY);
        return jdbc.query(connection -> {
            var statement = connection.prepareStatement("""
                    SELECT CURRENT_TIMESTAMP AS observed_at,
                        GREATEST((SELECT max(last_seen_at) FROM github_sync_worker_instance),
                            (SELECT last_seen_at FROM github_sync_worker_presence WHERE id = 1)) AS last_seen_at,
                        (SELECT count(*) FROM github_sync_worker_instance
                            WHERE protocol_major = 1 AND capabilities @> ?::text[]
                              AND last_seen_at >= CURRENT_TIMESTAMP - INTERVAL '90 seconds') AS compatible_count
                    """);
            statement.setArray(1, connection.createArrayOf("text", required.toArray()));
            return statement;
        }, result -> {
            result.next();
            Instant observed = result.getTimestamp("observed_at").toInstant();
            Timestamp last = result.getTimestamp("last_seen_at");
            Instant lastSeen = last == null ? null : last.toInstant();
            long compatible = result.getLong("compatible_count");
            String state = compatible > 0 ? "AVAILABLE" : lastSeen == null ? "NEVER_SEEN"
                    : !lastSeen.isBefore(observed.minusSeconds(90)) ? "INCOMPATIBLE" : "UNREACHABLE";
            return new GithubOfficialWorkerAvailabilityResponse(state, lastSeen, compatible, observed);
        });
    }

    @Transactional
    public int deleteExpiredInstances(Instant before, int limit) {
        if (before == null || limit < 1 || limit > 500) {
            throw new IllegalArgumentException("Invalid worker presence retention batch");
        }
        return jdbc.update("""
                DELETE FROM github_sync_worker_instance WHERE worker_id IN (
                    SELECT worker_id FROM github_sync_worker_instance WHERE last_seen_at < ?
                    ORDER BY last_seen_at, worker_id LIMIT ? FOR UPDATE SKIP LOCKED)
                """, Timestamp.from(before), limit);
    }

    public GithubSyncWorkerStatusResponse status() {
        return jdbc.query("""
                SELECT last_seen_at,
                       last_seen_at >= CURRENT_TIMESTAMP - INTERVAL '90 seconds' AS online
                FROM github_sync_worker_presence WHERE id = 1
                """, result -> result.next()
                ? new GithubSyncWorkerStatusResponse(
                        result.getTimestamp("last_seen_at").toInstant(), result.getBoolean("online"))
                : new GithubSyncWorkerStatusResponse(null, false));
    }
}
