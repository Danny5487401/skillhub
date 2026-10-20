package com.iflytek.skillhub.repository;

import com.iflytek.skillhub.dto.GithubDiscoveryEntryResponse.Execution;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.Map;
import java.util.List;
import java.util.HashMap;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads only durable execution identity and safe codes; never returns task snapshots or error bodies. */
@Repository
public class GithubDiscoveryEntryStatusQueryRepository {
    private final JdbcTemplate jdbc;
    public GithubDiscoveryEntryStatusQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<UUID, Execution> latestForEntries(long namespace, List<UUID> entries) {
        if (entries.isEmpty()) {
            return Map.of();
        }
        var named = new NamedParameterJdbcTemplate(jdbc);
        var values = new MapSqlParameterSource("ns", namespace)
                .addValue("entries", entries.stream().map(UUID::toString).toList());
        return named.query("""
            SELECT DISTINCT ON (t.snapshot_json->>'ruleId') t.snapshot_json->>'ruleId' entry_id,
                   t.id,t.status,t.last_error_code,t.retry_at,
                   CASE WHEN r.details_purged_at IS NULL THEN r.id END run_id
            FROM github_sync_task t JOIN github_sync_run r ON r.id=t.run_id AND r.namespace_id=t.namespace_id
            WHERE t.namespace_id=:ns AND t.snapshot_json->>'ruleId' IN (:entries)
              AND t.snapshot_json->>'entryType' IN ('CURATED_REPOSITORY','CURATED_OWNER','SEARCH_RULE')
            ORDER BY t.snapshot_json->>'ruleId',t.created_at DESC,t.id DESC
            """, values, rs -> {
                Map<UUID, Execution> result = new HashMap<>();
                while (rs.next()) {
                    Timestamp retry = rs.getTimestamp("retry_at");
                    result.put(UUID.fromString(rs.getString("entry_id")), new Execution(rs.getObject("id", UUID.class),
                            rs.getObject("run_id", UUID.class),rs.getString("status"),rs.getString("last_error_code"),
                            retry == null ? null : retry.toInstant()));
                }
                return result;
            });
    }
    public Execution latest(long namespace, UUID entryId) {
        return jdbc.query("""
            SELECT t.id,t.status,t.last_error_code,t.retry_at,
                   CASE WHEN r.details_purged_at IS NULL THEN r.id END run_id
            FROM github_sync_task t JOIN github_sync_run r ON r.id=t.run_id AND r.namespace_id=t.namespace_id
            WHERE t.namespace_id=? AND t.snapshot_json->>'ruleId'=?
              AND t.snapshot_json->>'entryType' IN ('CURATED_REPOSITORY','CURATED_OWNER','SEARCH_RULE')
            ORDER BY t.created_at DESC,t.id DESC LIMIT 1
            """, rs -> {
                if (!rs.next()) {
                    return null;
                }
                Timestamp retry = rs.getTimestamp("retry_at");
                return new Execution(rs.getObject("id", UUID.class), rs.getObject("run_id", UUID.class),
                        rs.getString("status"), rs.getString("last_error_code"), retry == null ? null : retry.toInstant());
            }, namespace, entryId.toString());
    }
}
