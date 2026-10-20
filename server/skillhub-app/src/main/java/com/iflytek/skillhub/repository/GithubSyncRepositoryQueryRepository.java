package com.iflytek.skillhub.repository;

import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.dto.GithubDeepDiscoveryReadResponse.PathResult;
import com.iflytek.skillhub.dto.GithubSyncRepositoryResponse.Detail;
import com.iflytek.skillhub.dto.GithubSyncRepositoryResponse.Origin;
import com.iflytek.skillhub.dto.GithubSyncRepositoryResponse.OriginType;
import com.iflytek.skillhub.dto.GithubSyncRepositoryResponse.Page;
import com.iflytek.skillhub.dto.GithubSyncRepositoryResponse.SourceStatus;
import com.iflytek.skillhub.dto.GithubSyncRepositoryResponse.State;
import com.iflytek.skillhub.dto.GithubSyncRepositoryResponse.Summary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Unifies source/candidate identities before pagination; provenance never multiplies repository rows. */
@Repository
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class GithubSyncRepositoryQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final GithubSyncManagementQueryRepository management;
    private final GithubDeepDiscoveryQueryRepository paths;
    public GithubSyncRepositoryQueryRepository(JdbcTemplate jdbc, GithubSyncManagementQueryRepository management,
                                               GithubDeepDiscoveryQueryRepository paths) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
        this.management = management;
        this.paths = paths;
    }
    private static final String BASE = """
        WITH repositories AS (
            SELECT s.repository_id,s.id source_id,c.id candidate_id,s.owner_login,s.repository_name,
                   LEAST(s.created_at,c.first_seen_at) first_seen_at,'REGISTERED' state,
                   s.status source_status,c.status candidate_status,s.next_check_at,s.last_error_code
            FROM github_source_binding s LEFT JOIN github_discovery_candidate c
                ON c.namespace_id=s.namespace_id AND c.repository_id=s.repository_id
            WHERE s.namespace_id=:ns
            UNION ALL
            SELECT c.repository_id,NULL::bigint,c.id,c.owner_login,c.repository_name,c.first_seen_at,
                   CASE c.status WHEN 'BOUND' THEN 'BOUND_WITHOUT_SOURCE'
                       WHEN 'REJECTED' THEN CASE WHEN c.last_error_code='NO_VALID_SKILL' THEN 'NO_VALID_SKILL'
                           ELSE 'VALIDATION_FAILED' END ELSE c.status END,
                   NULL::varchar,c.status,NULL::timestamptz,c.last_error_code
            FROM github_discovery_candidate c WHERE c.namespace_id=:ns
                AND NOT EXISTS (SELECT 1 FROM github_source_binding s
                    WHERE s.namespace_id=c.namespace_id AND s.repository_id=c.repository_id)
        )
        """;
    private static final String ORIGIN_TYPES = """
        SELECT r.*,ARRAY(SELECT origin_type FROM (
            SELECT origin_type FROM github_source_origin WHERE source_id=r.source_id
            UNION SELECT origin_type FROM github_discovery_candidate_origin WHERE candidate_id=r.candidate_id
        ) origins ORDER BY origin_type) origin_types FROM selected r
        """;

    public Page<Summary> repositories(int page, int size, Instant asOf, String q,
                                     OriginType originType, State state, SourceStatus sourceStatus) {
        Instant bound = validate(page, size, asOf);
        if (q != null && q.length() > 200) {
            invalid();
        }
        var parameters = parameters().addValue("asOf", Timestamp.from(bound))
                .addValue("limit", size).addValue("offset", (long) page * size);
        String filter = " WHERE r.first_seen_at<=:asOf";
        if (q != null && !q.isBlank()) {
            filter += " AND (r.owner_login||'/'||r.repository_name) ILIKE :q ESCAPE '!'";
            parameters.addValue("q", "%" + q.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        if (state != null) {
            filter += " AND r.state=:state";
            parameters.addValue("state", state.name());
        }
        if (sourceStatus != null) {
            filter += " AND r.source_status=:sourceStatus";
            parameters.addValue("sourceStatus", sourceStatus.name());
        }
        if (originType != null) {
            filter += """
                 AND (EXISTS (SELECT 1 FROM github_source_origin o WHERE o.source_id=r.source_id AND o.origin_type=:origin)
                   OR EXISTS (SELECT 1 FROM github_discovery_candidate_origin o WHERE o.candidate_id=r.candidate_id AND o.origin_type=:origin))
                """;
            parameters.addValue("origin", originType.name());
        }
        Long total = jdbc.queryForObject(BASE + " SELECT count(*) FROM repositories r" + filter, parameters, Long.class);
        var items = jdbc.query(BASE + ",selected AS MATERIALIZED (SELECT r.* FROM repositories r" + filter
                + " ORDER BY first_seen_at DESC,repository_id DESC LIMIT :limit OFFSET :offset) "
                + ORIGIN_TYPES + " ORDER BY first_seen_at DESC,repository_id DESC", parameters, (rs, n) -> summary(rs));
        return new Page<>(items, total == null ? 0 : total, page, size, bound);
    }

    private Summary repository(long repositoryId) {
        if (repositoryId < 1) {
            invalid();
        }
        var rows = jdbc.query(BASE + ",selected AS (SELECT * FROM repositories WHERE repository_id=:repo) "
                + ORIGIN_TYPES, parameters().addValue("repo", repositoryId), (rs, n) -> summary(rs));
        if (rows.isEmpty()) {
            throw new DomainNotFoundException("error.githubSync.source.notFound");
        }
        return rows.getFirst();
    }

    public Detail detail(long repositoryId) {
        Summary repository = repository(repositoryId);
        var parameters = parameters().addValue("repo", repositoryId).addValue("repoText", Long.toString(repositoryId))
                .addValue("source", repository.sourceId());
        var runIds = jdbc.query("""
            SELECT t.run_id FROM github_sync_task t JOIN github_sync_run r ON r.id=t.run_id
            WHERE t.namespace_id=:ns AND r.namespace_id=:ns AND r.details_purged_at IS NULL
              AND (t.source_id=:source OR t.snapshot_json->>'repositoryId'=:repoText)
              AND COALESCE(t.snapshot_json->>'entryType','')<>'SEARCH_PREVIEW'
            ORDER BY t.created_at DESC,t.id DESC LIMIT 1
            """, parameters, (rs, n) -> rs.getObject("run_id", UUID.class));
        Instant checked = jdbc.queryForObject("""
            SELECT max(completed_at) FROM github_sync_task WHERE namespace_id=:ns AND kind='REPOSITORY_SYNC'
              AND status='ACQUISITION_COMPLETE' AND (source_id=:source OR snapshot_json->>'repositoryId'=:repoText)
            """, parameters, (rs, n) -> instant(rs, 1));
        return jdbc.query(BASE + " SELECT * FROM repositories WHERE repository_id=:repo", parameters,
                rs -> {
                    rs.next();
                    return new Detail(repository, runIds.isEmpty() ? null : runIds.getFirst(),
                            checked, instant(rs, "next_check_at"), rs.getString("last_error_code"));
                });
    }

    public Page<Origin> origins(long repositoryId, int page, int size, Instant asOf) {
        Instant bound = validate(page, size, asOf);
        Summary repository = repository(repositoryId);
        var parameters = parameters().addValue("source", repository.sourceId()).addValue("candidate", repository.candidateId())
                .addValue("asOf", Timestamp.from(bound)).addValue("limit", size).addValue("offset", (long) page * size);
        String base = """
            WITH combined AS (
                SELECT origin_type,origin_key,created_at first_seen_at,NULL::timestamptz last_seen_at
                FROM github_source_origin WHERE source_id=:source AND created_at<=:asOf
                UNION ALL
                SELECT origin_type,origin_key,first_seen_at,last_seen_at FROM github_discovery_candidate_origin
                WHERE candidate_id=:candidate AND first_seen_at<=:asOf
            ), origins AS (SELECT origin_type,origin_key,min(first_seen_at) first_seen_at,max(last_seen_at) last_seen_at
                FROM combined GROUP BY origin_type,origin_key)
            """;
        Long total = jdbc.queryForObject(base + " SELECT count(*) FROM origins", parameters, Long.class);
        var items = jdbc.query(base + """
            ,selected AS (SELECT * FROM origins ORDER BY first_seen_at DESC,origin_type,origin_key LIMIT :limit OFFSET :offset)
            SELECT o.*,entry.id entry_id,q.id query_id,q.definition_json->>'channel' channel,
                   CASE WHEN r.details_purged_at IS NULL THEN r.id END run_id,co.recent_seed_repository_id
            FROM selected o
            LEFT JOIN LATERAL (SELECT e.id FROM github_discovery_entry e WHERE e.namespace_id=:ns
                AND e.entry_type=o.origin_type AND ((o.origin_type='CURATED_OWNER' AND lower(e.owner_login)=lower(o.origin_key))
                    OR (o.origin_type<>'CURATED_OWNER' AND e.id::text=o.origin_key)) LIMIT 1) entry ON TRUE
            LEFT JOIN github_deep_discovery_query q ON o.origin_type='OFFICIAL_DISCOVERY'
                AND q.namespace_id=:ns AND q.id::text=o.origin_key
            LEFT JOIN github_discovery_candidate_origin co ON co.candidate_id=:candidate
                AND co.origin_type=o.origin_type AND co.origin_key=o.origin_key
            LEFT JOIN github_sync_run r ON r.id=co.recent_run_id AND r.namespace_id=:ns
            ORDER BY o.first_seen_at DESC,o.origin_type,o.origin_key
            """, parameters, (rs, n) -> new Origin(OriginType.valueOf(rs.getString("origin_type")), rs.getString("origin_key"),
                instant(rs, "first_seen_at"), instant(rs, "last_seen_at"), rs.getObject("entry_id", UUID.class),
                rs.getString("channel"), rs.getObject("query_id", UUID.class), rs.getObject("run_id", UUID.class),
                rs.getObject("recent_seed_repository_id", Long.class)));
        return new Page<>(items, total == null ? 0 : total, page, size, bound);
    }

    public Page<PathResult> paths(long repositoryId, int page, int size, Instant asOf) {
        Instant bound = validate(page, size, asOf);
        repository(repositoryId);
        var result = paths.pathsForRepository(management.platformNamespaceId(), repositoryId, page, size, bound);
        return new Page<>(result.items(), result.total(), page, size, bound);
    }
    private MapSqlParameterSource parameters() {
        return new MapSqlParameterSource("ns", management.platformNamespaceId());
    }

    private Summary summary(ResultSet rs) throws SQLException {
        String sourceStatus = rs.getString("source_status");
        String[] origins = (String[]) rs.getArray("origin_types").getArray();
        return new Summary(rs.getLong("repository_id"), rs.getObject("source_id", Long.class), rs.getObject("candidate_id", Long.class),
                rs.getString("owner_login"), rs.getString("repository_name"), instant(rs, "first_seen_at"),
                State.valueOf(rs.getString("state")), sourceStatus == null ? null : SourceStatus.valueOf(sourceStatus),
                rs.getString("candidate_status"), Arrays.stream(origins).map(OriginType::valueOf).toList());
    }
    private static Instant validate(int page, int size, Instant asOf) {
        Instant now = Instant.now();
        if (page < 0 || size < 1 || size > 100 || asOf != null && asOf.isAfter(now)) {
            invalid();
        }
        return asOf == null ? now : asOf;
    }
    private static void invalid() {
        throw new DomainBadRequestException("error.badRequest");
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
    private static Instant instant(ResultSet rs, int column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
