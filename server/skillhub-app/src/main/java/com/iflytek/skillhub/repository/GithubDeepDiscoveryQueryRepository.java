package com.iflytek.skillhub.repository;

import com.iflytek.skillhub.dto.GithubDeepDiscoveryReadResponse.*;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Presentation joins use JDBC to page candidates before bounded provenance/path aggregation. */
@Repository
@Transactional(readOnly = true)
public class GithubDeepDiscoveryQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final com.iflytek.skillhub.infra.jpa.JdbcGithubSyncCapacityGate capacity;
    public GithubDeepDiscoveryQueryRepository(JdbcTemplate jdbc,
            com.iflytek.skillhub.infra.jpa.JdbcGithubSyncCapacityGate capacity) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
        this.capacity = capacity;
    }
    public record BudgetWait(boolean blocked, Instant retryAt) {}

    public BudgetWait discoveryBudgetWait() {
        return jdbc.getJdbcTemplate().queryForObject("""
                SELECT budget.next_lease_eligible_at > clock_timestamp() OR EXISTS (
                    SELECT 1 FROM github_sync_task WHERE kind='DISCOVERY' AND official_query_id IS NOT NULL
                      AND status='LEASED' AND lease_expires_at > clock_timestamp()) AS blocked,
                    CASE WHEN budget.next_lease_eligible_at > clock_timestamp()
                         THEN budget.next_lease_eligible_at END AS retry_at
                FROM github_official_discovery_budget budget WHERE id
                """, (rs, row) -> new BudgetWait(rs.getBoolean("blocked"),
                    rs.getTimestamp("retry_at") == null ? null : rs.getTimestamp("retry_at").toInstant()));
    }

    private static final String STATUS = """
        CASE c.status WHEN 'BOUND' THEN 'VALID' WHEN 'REJECTED' THEN
            CASE WHEN c.last_error_code='NO_VALID_SKILL' THEN 'NO_VALID_SKILL' ELSE 'VALIDATION_FAILED' END
            ELSE c.status END
        """;
    private static final String BASE = """
        FROM github_discovery_candidate c JOIN github_candidate_discovery_eligibility e ON e.candidate_id=c.id
        WHERE c.namespace_id=:ns AND e.has_official
        """;
    private static final String PROJECTION = """
        SELECT c.*, e.source_id, NOT e.acquisition_allowed AS paused,
          CASE WHEN e.source_status='PAUSED' THEN 'SOURCE_PAUSED'
               WHEN NOT e.acquisition_allowed AND e.excluded THEN 'EXCLUDED'
               WHEN NOT e.acquisition_allowed THEN 'DISABLED' ELSE NULL END AS wait_reason,
        """ + STATUS + " AS display_status ";
    private static final String CHANNELS = """
        SELECT s.*, o.channels, o.last_discovered_at FROM selected s
        LEFT JOIN LATERAL (SELECT array_agg(DISTINCT q.definition_json->>'channel') AS channels,
            max(o.last_seen_at) AS last_discovered_at
          FROM github_discovery_candidate_origin o JOIN github_deep_discovery_query q ON q.id::text=o.origin_key
          WHERE o.candidate_id=s.id AND o.origin_type='OFFICIAL_DISCOVERY') o ON TRUE
        """;

    public DiscoveryPage<RepositorySummary> repositories(long namespace, int page, int size, Instant asOf,
            String search, RepositoryStatus status, Channel channel, Boolean paused) {
        var params = parameters(namespace).addValue("asOf", Timestamp.from(asOf));
        String filters = " AND c.first_seen_at<=:asOf";
        if (search != null && !search.isBlank()) {
            filters += " AND strpos(lower(c.owner_login || '/' || c.repository_name), lower(:search))>0";
            params.addValue("search", search.trim());
        }
        if (status != null) { filters += " AND (" + STATUS + ")=:status"; params.addValue("status", status.name()); }
        if (paused != null) { filters += " AND NOT e.acquisition_allowed=:paused"; params.addValue("paused", paused); }
        if (channel != null) {
            filters += " " + """
                AND EXISTS (SELECT 1 FROM github_discovery_candidate_origin origin
                    JOIN github_deep_discovery_query q ON q.id::text=origin.origin_key
                    WHERE origin.candidate_id=c.id AND origin.origin_type='OFFICIAL_DISCOVERY'
                      AND q.definition_json->>'channel'=:channel)
                """;
            params.addValue("channel", channel.name());
        }
        String where = BASE + filters;
        long total = count("SELECT count(*) " + where, params);
        params.addValue("limit", size).addValue("offset", (long)page*size);
        String selected;
        if (paused == null) {
            // Eligibility affects projected pause/source fields, not candidate selection here.
            // Keep it correlated after pagination instead of expanding it for every candidate.
            selected = "WITH candidate_page AS MATERIALIZED (SELECT c.* FROM github_discovery_candidate c "
                    + "WHERE c.namespace_id=:ns AND EXISTS (SELECT 1 FROM github_discovery_candidate_origin origin "
                    + "WHERE origin.candidate_id=c.id AND origin.origin_type='OFFICIAL_DISCOVERY')" + filters
                    + " ORDER BY c.first_seen_at DESC,c.id DESC LIMIT :limit OFFSET :offset), selected AS ("
                    + PROJECTION + " FROM candidate_page c JOIN LATERAL ("
                    + "SELECT * FROM github_candidate_discovery_eligibility eligibility "
                    + "WHERE eligibility.candidate_id=c.id AND eligibility.namespace_id=c.namespace_id "
                    + "AND eligibility.repository_id=c.repository_id OFFSET 0) e ON TRUE) ";
        } else {
            selected = "WITH selected AS (" + PROJECTION + where
                    + " ORDER BY c.first_seen_at DESC,c.id DESC LIMIT :limit OFFSET :offset) ";
        }
        var rows = jdbc.query(selected + CHANNELS + " ORDER BY s.first_seen_at DESC,s.id DESC",
                params, (rs,n)->repository(rs));
        return new DiscoveryPage<>(rows,total,page,size,asOf);
    }

    public RepositorySummary repository(long namespace, long repositoryId) {
        var rows = jdbc.query("WITH selected AS (" + PROJECTION + BASE
                + " AND c.repository_id=:repo AND e.namespace_id=:ns AND e.repository_id=:repo) " + CHANNELS,
                parameters(namespace).addValue("repo",repositoryId), (rs,n)->repository(rs));
        if (rows.isEmpty()) throw new DomainNotFoundException("error.githubSync.source.notFound");
        return rows.getFirst();
    }
    private RepositorySummary repository(ResultSet rs) throws SQLException {
        var values=rs.getArray("channels");
        List<Channel> channels=values==null?List.of():Arrays.stream((String[])values.getArray())
                .filter(java.util.Objects::nonNull).map(Channel::valueOf).sorted().toList();
        return new RepositorySummary(rs.getLong("repository_id"),rs.getString("owner_login"),
                rs.getString("repository_name"),instant(rs,"first_seen_at"),instant(rs,"last_discovered_at"),
                RepositoryStatus.valueOf(rs.getString("display_status")),channels,rs.getObject("source_id",Long.class),
                rs.getBoolean("paused"),rs.getString("wait_reason"),rs.getLong("id"));
    }

    public RepositoryDetail detail(long namespace,long repositoryId) {
        var repository=repository(namespace,repositoryId);
        var params=parameters(namespace).addValue("repo",Long.toString(repositoryId));
        record Task(Instant completed, Instant eligible, UUID run) {}
        var tasks=jdbc.query("""
            SELECT t.completed_at, CASE WHEN t.status IN ('QUEUED','RETRY_WAIT') THEN GREATEST(t.due_at,t.retry_at) END eligible,
                CASE WHEN r.details_purged_at IS NULL THEN r.id END run_id
            FROM github_sync_task t JOIN github_sync_run r ON r.id=t.run_id
            WHERE t.namespace_id=:ns AND t.kind='REPOSITORY_SYNC' AND t.snapshot_json->>'repositoryId'=:repo
            ORDER BY t.created_at DESC,t.id DESC LIMIT 1
            """,params,(rs,n)->new Task(instant(rs,"completed_at"),instant(rs,"eligible"),rs.getObject("run_id",UUID.class)));
        var task=tasks.isEmpty()?null:tasks.getFirst();
        Timestamp completed=jdbc.queryForObject("""
            SELECT max(completed_at) FROM github_sync_task WHERE namespace_id=:ns AND kind='REPOSITORY_SYNC'
                AND snapshot_json->>'repositoryId'=:repo
            """,params,Timestamp.class);
        Instant checked=completed==null?null:completed.toInstant();
        Instant next=jdbc.query("""
            SELECT CASE WHEN e.acquisition_allowed THEN COALESCE(s.next_check_at,c.next_validation_at) END eligible
            FROM github_discovery_candidate c JOIN github_candidate_discovery_eligibility e ON e.candidate_id=c.id
            LEFT JOIN github_source_binding s ON s.id=e.source_id WHERE c.id=:candidate
            """,new MapSqlParameterSource("candidate",repository.candidateId()),rs->rs.next()?instant(rs,"eligible"):null);
        if (task!=null && task.eligible()!=null && !repository.paused()) next=task.eligible();
        String reason=switch(repository.status()) {
            case PENDING,VALIDATING -> "NOT_CHECKED";
            case NO_VALID_SKILL -> "NO_VALID_SKILL";
            case VALIDATION_FAILED -> "VALIDATION_FAILED";
            case UNREACHABLE -> "SOURCE_UNREADABLE";
            case VALID -> null;
        };
        if ("EXCLUDED".equals(repository.waitReason()) || "DISABLED".equals(repository.waitReason())) reason=repository.waitReason();
        var ingestion=jdbc.query("""
            SELECT last_error_code FROM github_sync_package_ingestion WHERE namespace_id=:ns AND repository_id=:repository
            AND status IN ('BLOCKED','READY_FOR_REVIEW') ORDER BY updated_at DESC,id DESC LIMIT 1
            """,params.addValue("repository",repositoryId),(rs,n)->rs.getString("last_error_code"));
        if (!ingestion.isEmpty() && ingestion.getFirst()!=null) reason=
                ingestion.getFirst().contains("CONFLICT") || ingestion.getFirst().startsWith("PATH_MOVE_")
                        ?"CONFLICT":"VALIDATION_FAILED";
        if (count("""
            SELECT count(*) FROM review_task r JOIN github_sync_package_ingestion i ON i.id=r.github_sync_ingestion_id
            WHERE i.namespace_id=:ns AND i.repository_id=:repository AND r.status='PENDING'
            """,params)>0) reason="REVIEW_PENDING";
        return new RepositoryDetail(repository,checked,next,reason,List.of(),task==null?null:task.run(),null);
    }

    public DiscoveryPage<Provenance> provenance(long namespace,long repositoryId,int page,int size,Instant asOf) {
        var repository=repository(namespace,repositoryId);
        var params=parameters(namespace).addValue("candidate",repository.candidateId()).addValue("asOf",Timestamp.from(asOf))
            .addValue("limit",size).addValue("offset",(long)page*size);
        String where=" FROM github_discovery_candidate_origin o WHERE o.candidate_id=:candidate AND o.origin_type='OFFICIAL_DISCOVERY' AND o.first_seen_at<=:asOf";
        long total=count("SELECT count(*)"+where,params);
        var rows=jdbc.query("WITH selected AS (SELECT o.*"+where+" ORDER BY o.last_seen_at DESC,o.id DESC LIMIT :limit OFFSET :offset) " + """
            SELECT o.*,q.id query_id,q.query_version,q.definition_json->>'channel' channel,
                CASE WHEN r.details_purged_at IS NULL THEN r.id END run_id
            FROM selected o JOIN github_deep_discovery_query q ON q.id::text=o.origin_key
            LEFT JOIN github_sync_run r ON r.id=o.recent_run_id
            ORDER BY o.last_seen_at DESC,o.id DESC
            """,params,(rs,n)->new Provenance(rs.getObject("id",UUID.class),Channel.valueOf(rs.getString("channel")),
                rs.getString("latest_strategy_version"),rs.getObject("query_id",UUID.class),rs.getLong("query_version"),
                rs.getObject("recent_shard_id",UUID.class),rs.getObject("run_id",UUID.class),instant(rs,"first_seen_at"),
                instant(rs,"last_seen_at"),rs.getObject("recent_seed_repository_id",Long.class)));
        return new DiscoveryPage<>(rows,total,page,size,asOf);
    }

    public DiscoveryPage<PathResult> paths(long namespace,long repositoryId,int page,int size,Instant asOf) {
        repository(namespace,repositoryId);
        return pathsForRepository(namespace,repositoryId,page,size,asOf);
    }

    /** Shared safe path projection; caller must check repository visibility before invoking. */
    DiscoveryPage<PathResult> pathsForRepository(long namespace,long repositoryId,int page,int size,Instant asOf) {
        var params=parameters(namespace).addValue("repo",Long.toString(repositoryId)).addValue("asOf",Timestamp.from(asOf))
            .addValue("limit",size).addValue("offset",(long)page*size);
        String latest="""
            WITH latest AS (SELECT DISTINCT ON (p.skill_path) p.*,t.run_id
                FROM github_sync_task t JOIN github_sync_path_result p ON p.task_id=t.id
                WHERE t.namespace_id=:ns AND t.kind='REPOSITORY_SYNC' AND t.snapshot_json->>'repositoryId'=:repo
                  AND p.created_at<=:asOf
                ORDER BY p.skill_path,t.created_at DESC,t.id DESC,p.updated_at DESC,p.id DESC)
            """;
        long total=count(latest+" SELECT count(*) FROM latest",params);
        var rows=jdbc.query(latest+"""
            ,selected AS (SELECT * FROM latest ORDER BY skill_path LIMIT :limit OFFSET :offset)
            SELECT p.*,COALESCE(i.published_skill_id,reused.published_skill_id) published_skill_id,
                COALESCE(i.published_version_id,reused.published_version_id) published_version_id,review.id review_id,
                CASE WHEN r.details_purged_at IS NULL THEN r.id END visible_run_id
            FROM selected p LEFT JOIN github_sync_package_ingestion i ON i.id=p.ingestion_id
            LEFT JOIN LATERAL (
                SELECT published.id,published.published_skill_id,published.published_version_id
                FROM github_source_skill_binding binding
                JOIN github_sync_package_ingestion published ON published.id=binding.latest_ingestion_id
                    AND published.namespace_id=binding.namespace_id AND published.repository_id=binding.repository_id
                    AND published.skill_path=binding.skill_path AND published.content_sha256=binding.latest_content_sha256
                    AND published.published_skill_id=binding.skill_id AND published.created_at<=i.created_at
                JOIN skill_version version ON version.id=published.published_version_id
                    AND version.skill_id=binding.skill_id AND version.github_sync_ingestion_id=published.id
                WHERE i.status='NO_CHANGE' AND binding.namespace_id=i.namespace_id
                    AND binding.repository_id=i.repository_id AND binding.skill_path=i.skill_path
                    AND binding.latest_content_sha256=i.content_sha256
            ) reused ON TRUE
            LEFT JOIN LATERAL (SELECT id FROM review_task WHERE github_sync_ingestion_id=COALESCE(reused.id,i.id)
                ORDER BY submitted_at DESC,id DESC LIMIT 1) review ON TRUE
            LEFT JOIN github_sync_run r ON r.id=p.run_id ORDER BY p.skill_path
            """,params,(rs,n)->new PathResult(rs.getString("skill_path"),rs.getString("outcome"),
                reasonCode(rs.getString("reason")),rs.getObject("published_skill_id",Long.class),
                rs.getObject("published_version_id",Long.class),rs.getObject("review_id",Long.class),
                rs.getObject("visible_run_id",UUID.class),instant(rs,"updated_at")));
        return new DiscoveryPage<>(rows,total,page,size,asOf);
    }

    public Recent recent(long namespace,Instant from,Instant until) {
        Instant since=jdbc.queryForObject("SELECT available_since FROM github_discovery_metrics_epoch WHERE id",new MapSqlParameterSource(),Timestamp.class).toInstant();
        if(from.isBefore(since)) return new Recent(from,until,null,null,null,null,false,since);
        var metrics=jdbc.query("""
            SELECT event_type,count(*) value FROM github_discovery_outcome_event
            WHERE namespace_id=:ns AND official_at_event AND occurred_at>=:from AND occurred_at<:until GROUP BY event_type
            """,parameters(namespace).addValue("from",Timestamp.from(from)).addValue("until",Timestamp.from(until)),
            rs->{var map=new java.util.HashMap<String,Long>();while(rs.next())map.put(rs.getString("event_type"),rs.getLong("value"));return map;});
        boolean uncertain=count("""
            SELECT count(*) FROM github_discovery_outcome_event
            WHERE namespace_id=:ns AND official_at_event AND event_type='FIRST_VALID' AND NOT first_occurrence_known
                AND occurred_at>=:from AND occurred_at<:until
            """,parameters(namespace).addValue("from",Timestamp.from(from)).addValue("until",Timestamp.from(until)))>0;
        return new Recent(from,until,metrics.getOrDefault("CANDIDATE_CREATED",0L),uncertain?null:metrics.getOrDefault("FIRST_VALID",0L),
                metrics.getOrDefault("NEW_SKILL",0L),metrics.getOrDefault("UPDATED_VERSION",0L),!uncertain,since);
    }
    public Current current(long namespace,Instant observed) {
        var params=parameters(namespace);
        long pending=count("SELECT count(*) "+BASE+" AND c.status IN ('PENDING','VALIDATING','REJECTED')",params);
        long reviews=count("""
            SELECT count(DISTINCT r.subject_version_id) FROM review_task r
            JOIN github_sync_package_ingestion i ON i.id=r.github_sync_ingestion_id
            JOIN github_discovery_candidate c ON c.namespace_id=i.namespace_id AND c.repository_id=i.repository_id
            WHERE i.namespace_id=:ns AND r.subject_type='SKILL_VERSION' AND r.status='PENDING'
            AND EXISTS(SELECT 1 FROM github_discovery_candidate_origin o WHERE o.candidate_id=c.id AND o.origin_type='OFFICIAL_DISCOVERY')
            """,params);
        return new Current(pending,reviews,observed);
    }
    public List<UUID> recentRuns(long namespace) {
        return jdbc.query("""
            SELECT r.id FROM github_sync_run r WHERE r.namespace_id=:ns AND r.details_purged_at IS NULL
              AND EXISTS(SELECT 1 FROM github_sync_task t WHERE t.run_id=r.id AND (t.official_query_id IS NOT NULL OR
                EXISTS(SELECT 1 FROM github_discovery_candidate c JOIN github_discovery_candidate_origin o ON o.candidate_id=c.id
                  WHERE c.namespace_id=t.namespace_id AND c.repository_id::text=t.snapshot_json->>'repositoryId' AND o.origin_type='OFFICIAL_DISCOVERY')))
            ORDER BY r.created_at DESC,r.id DESC LIMIT 5
            """,parameters(namespace),(rs,n)->rs.getObject("id",UUID.class));
    }
    public record ChannelState(Channel channel,long completed,long pending,long gaps,Instant progress,boolean running,Instant retry) {}
    public List<ChannelState> channelStates(long namespace) {
        return jdbc.query("""
            SELECT q.definition_json->>'channel' channel,
              COALESCE(sum(CASE WHEN q.query_source='OWNER' THEN CASE WHEN q.active_task_id IS NULL AND q.last_full_recheck_at IS NOT NULL AND q.gap_reason IS NULL AND q.next_due_at>CURRENT_TIMESTAMP THEN 1 ELSE 0 END ELSE s.completed END),0) completed,
              COALESCE(sum(CASE WHEN q.query_source='OWNER' THEN CASE WHEN (q.active_task_id IS NOT NULL OR q.next_due_at<=CURRENT_TIMESTAMP) AND q.gap_reason IS NULL THEN 1 ELSE 0 END ELSE CASE WHEN s.total=0 AND q.next_due_at<=CURRENT_TIMESTAMP THEN 1 ELSE s.pending END END),0) pending,
              COALESCE(sum(CASE WHEN q.query_source='OWNER' THEN CASE WHEN q.gap_reason IS NOT NULL THEN 1 ELSE 0 END ELSE s.gaps END),0) gaps,
              max(q.last_progress_at) progress,
              bool_or(t.status='LEASED' AND t.lease_expires_at>CURRENT_TIMESTAMP AND t.last_progress_at>CURRENT_TIMESTAMP-INTERVAL '90 seconds') running,
              min(GREATEST(t.due_at,t.retry_at)) FILTER(WHERE t.status IN ('QUEUED','RETRY_WAIT')) retry
            FROM github_deep_discovery_query q LEFT JOIN github_sync_task t ON t.id=q.active_task_id
            LEFT JOIN LATERAL (SELECT count(*) total,count(*) FILTER(WHERE state='COMPLETE') completed,
              count(*) FILTER(WHERE state IN ('PENDING','ACTIVE')) pending,count(*) FILTER(WHERE state='GAP') gaps
              FROM github_official_discovery_shard WHERE query_id=q.id) s ON TRUE
            WHERE q.namespace_id=:ns AND (q.retired_at IS NULL OR q.active_task_id IS NOT NULL
                OR EXISTS(SELECT 1 FROM github_official_discovery_shard active_root
                    WHERE active_root.query_id=q.id AND active_root.parent_id IS NULL AND active_root.state<>'COMPLETE')) GROUP BY q.definition_json->>'channel'
            """,parameters(namespace),(rs,n)->new ChannelState(Channel.valueOf(rs.getString("channel")),rs.getLong("completed"),
                rs.getLong("pending"),rs.getLong("gaps"),instant(rs,"progress"),rs.getBoolean("running"),instant(rs,"retry")));
    }
    /** Each row is one last observation, never a sum of token allowances. */
    public List<Quota> quotas() {
        return jdbc.query("""
            SELECT o.resource,o.observed_at,o.valid_until,
              CASE WHEN o.valid_until<=CURRENT_TIMESTAMP THEN 'UNKNOWN'
                   WHEN o.state='PRIMARY_EXHAUSTED' THEN 'EXHAUSTED'
                   WHEN o.state='SECONDARY_COOLDOWN' THEN 'COOLING_DOWN' ELSE o.state END display_state,
              CASE WHEN o.valid_until>CURRENT_TIMESTAMP AND o.state IN ('OBSERVED','PRIMARY_EXHAUSTED') THEN o.remaining::bigint END remaining,
              CASE WHEN GREATEST(CASE WHEN o.valid_until>CURRENT_TIMESTAMP AND o.state='PRIMARY_EXHAUSTED' THEN o.reset_at END,c.primary_until,s.cooldown_until)>CURRENT_TIMESTAMP
                THEN GREATEST(CASE WHEN o.valid_until>CURRENT_TIMESTAMP AND o.state='PRIMARY_EXHAUSTED' THEN o.reset_at END,c.primary_until,s.cooldown_until) END retry_at
            FROM github_official_quota_observation o
            LEFT JOIN github_official_resource_cooldown c ON c.resource=o.resource AND o.credential_id IS NULL
            LEFT JOIN github_official_secondary_cooldown s ON s.id
            ORDER BY o.observed_at DESC,o.observation_key,o.resource LIMIT 101
            """,new MapSqlParameterSource(),(rs,n)->new Quota(rs.getString("resource"),null,rs.getString("display_state"),
                rs.getObject("remaining",Long.class),instant(rs,"observed_at"),instant(rs,"valid_until"),instant(rs,"retry_at")));
    }
    public record QuotaWait(String reason,String resource,Instant retryAt) {}
    public List<QuotaWait> quotaWaits() {
        return jdbc.query("""
            SELECT 'QUOTA_COOLDOWN' reason,NULL::text resource,cooldown_until retry_at
            FROM github_official_secondary_cooldown WHERE cooldown_until>CURRENT_TIMESTAMP
            UNION ALL
            SELECT 'PRIMARY_RATE_LIMIT',resource,primary_until FROM github_official_resource_cooldown
            WHERE primary_until>CURRENT_TIMESTAMP
              AND NOT EXISTS(SELECT 1 FROM github_system_credential)
            """,new MapSqlParameterSource(),(rs,n)->new QuotaWait(rs.getString("reason"),rs.getString("resource"),instant(rs,"retry_at")));
    }
    public boolean admissionBlocked(long namespace) {
        return !capacity.allowsFullAcquisition(namespace, Instant.now());
    }
    /** Same eligibility as credential selection, without locking rows or reading secrets. */
    public boolean credentialPoolUnavailable(String resource) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM github_system_credential) AND NOT EXISTS(
                SELECT 1 FROM github_system_credential c WHERE c.status='ACTIVE'
                  AND (c.rate_limited_until IS NULL OR c.rate_limited_until<=CURRENT_TIMESTAMP)
                  AND NOT EXISTS(SELECT 1 FROM github_system_credential_quota q
                    WHERE q.credential_id=c.id AND q.resource=:resource AND q.remaining=0 AND q.reset_at>CURRENT_TIMESTAMP))
            """,new MapSqlParameterSource("resource",resource),Boolean.class));
    }
    public boolean candidateCapacityBlocked(long namespace) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT (SELECT count(*) FROM github_discovery_candidate WHERE namespace_id=:ns
                AND status IN ('PENDING','VALIDATING')) >= candidate_pending_limit
            FROM github_sync_admission_policy WHERE id
            """,parameters(namespace),Boolean.class));
    }
    public record WaitEvidence(String reason,String work,Instant retryAt) {}
    public List<WaitEvidence> discoveryWaits(long namespace,Channel channel) {
        var params=parameters(namespace).addValue("channel",channel==null?null:channel.name());
        return jdbc.query("""
            WITH active_queries AS (
                SELECT q.* FROM github_deep_discovery_query q WHERE q.namespace_id=:ns
                    AND (CAST(:channel AS text) IS NULL OR q.definition_json->>'channel'=:channel)
                    AND (q.retired_at IS NULL OR q.active_task_id IS NOT NULL OR EXISTS(
                        SELECT 1 FROM github_official_discovery_shard s WHERE s.query_id=q.id AND s.parent_id IS NULL AND s.state<>'COMPLETE'))
            ), evidence AS (
                SELECT q.wait_reason reason,GREATEST(t.due_at,t.retry_at) retry_at FROM active_queries q
                  LEFT JOIN github_sync_task t ON t.id=q.active_task_id WHERE q.wait_reason IS NOT NULL AND q.wait_reason NOT IN ('DISABLED','EXCLUDED','SOURCE_PAUSED')
                UNION ALL SELECT t.last_error_code,GREATEST(t.due_at,t.retry_at) FROM active_queries q
                  JOIN github_sync_task t ON t.id=q.active_task_id WHERE t.status IN ('QUEUED','RETRY_WAIT') AND t.last_error_code IS NOT NULL AND t.last_error_code NOT IN ('DISABLED','EXCLUDED','SOURCE_PAUSED')
                UNION ALL SELECT t.official_pause_reason,GREATEST(t.due_at,t.retry_at) FROM active_queries q
                  JOIN github_sync_task t ON t.id=q.active_task_id WHERE t.status IN ('QUEUED','RETRY_WAIT') AND t.official_pause_reason IS NOT NULL AND t.official_pause_reason NOT IN ('DISABLED','EXCLUDED','SOURCE_PAUSED')
                UNION ALL SELECT 'COVERAGE_GAP',s.retry_at FROM active_queries q
                  JOIN github_official_discovery_shard s ON s.query_id=q.id WHERE s.state='GAP'
                UNION ALL SELECT 'COVERAGE_GAP',GREATEST(q.next_due_at,t.retry_at) FROM active_queries q
                  LEFT JOIN github_sync_task t ON t.id=q.active_task_id WHERE q.query_source='OWNER' AND q.gap_reason IS NOT NULL
                UNION ALL SELECT 'EXCLUDED',NULL FROM active_queries q
                  JOIN github_official_owner_eligibility e ON e.query_id=q.id WHERE e.excluded
            ) SELECT reason,CASE WHEN bool_or(retry_at IS NULL) THEN NULL ELSE max(retry_at) END retry_at
              FROM evidence WHERE reason IN ('DISABLED','EXCLUDED','SOURCE_PAUSED','BUDGET','PRIMARY_RATE_LIMIT','SECONDARY_COOLDOWN','QUOTA_COOLDOWN','CREDENTIAL_UNAVAILABLE','CANDIDATE_CAPACITY','REVIEW_BACKPRESSURE','COVERAGE_GAP','GITHUB_RATE_LIMITED','BACKPRESSURE_ACTIVE','CANDIDATE_BUDGET_EXCEEDED') GROUP BY reason ORDER BY reason LIMIT 32
            """,params,(rs,n)->new WaitEvidence(rs.getString("reason"),"NEW_DISCOVERY",instant(rs,"retry_at")));
    }
    public List<WaitEvidence> repositoryWaits(long namespace,Long repositoryId) {
        var params=parameters(namespace).addValue("repo",repositoryId==null?null:repositoryId.toString()).addValue("numericRepo",repositoryId);
        return jdbc.query("""
            WITH relevant AS (
                SELECT t.*,CASE WHEN e.source_id IS NULL THEN 'CANDIDATE_VALIDATION' ELSE 'SOURCE_UPDATE' END work
                FROM github_sync_task t JOIN github_discovery_candidate c ON c.namespace_id=t.namespace_id
                    AND c.repository_id::text=t.snapshot_json->>'repositoryId'
                JOIN github_candidate_discovery_eligibility e ON e.candidate_id=c.id
                WHERE t.namespace_id=:ns AND t.kind='REPOSITORY_SYNC' AND e.has_official
                    AND (CAST(:repo AS text) IS NULL OR t.snapshot_json->>'repositoryId'=:repo)
                    AND t.status IN ('QUEUED','LEASED','RETRY_WAIT')
            ), evidence AS (
                SELECT official_pause_reason reason,work,GREATEST(due_at,retry_at) retry_at FROM relevant
                    WHERE status IN ('QUEUED','RETRY_WAIT') AND official_pause_reason IS NOT NULL AND official_pause_reason NOT IN ('DISABLED','EXCLUDED','SOURCE_PAUSED')
                UNION ALL SELECT last_error_code,work,GREATEST(due_at,retry_at) FROM relevant
                    WHERE status='RETRY_WAIT' AND last_error_code IS NOT NULL AND last_error_code NOT IN ('DISABLED','EXCLUDED','SOURCE_PAUSED')
                UNION ALL SELECT CASE WHEN e.source_status='PAUSED' THEN 'SOURCE_PAUSED'
                        WHEN e.excluded THEN 'EXCLUDED' ELSE 'DISABLED' END,
                    CASE WHEN e.source_id IS NULL THEN 'CANDIDATE_VALIDATION' ELSE 'SOURCE_UPDATE' END,NULL
                    FROM github_candidate_discovery_eligibility e WHERE e.namespace_id=:ns AND e.has_official
                      AND NOT e.acquisition_allowed AND (CAST(:numericRepo AS bigint) IS NULL OR e.repository_id=:numericRepo)
            ) SELECT reason,work,CASE WHEN bool_or(retry_at IS NULL) THEN NULL ELSE max(retry_at) END retry_at
              FROM evidence WHERE reason IN ('DISABLED','EXCLUDED','SOURCE_PAUSED','BUDGET','PRIMARY_RATE_LIMIT','SECONDARY_COOLDOWN','QUOTA_COOLDOWN','CREDENTIAL_UNAVAILABLE','CANDIDATE_CAPACITY','REVIEW_BACKPRESSURE','COVERAGE_GAP','GITHUB_RATE_LIMITED','BACKPRESSURE_ACTIVE','CANDIDATE_BUDGET_EXCEEDED') GROUP BY reason,work ORDER BY reason,work LIMIT 32
            """,params,(rs,n)->new WaitEvidence(rs.getString("reason"),rs.getString("work"),instant(rs,"retry_at")));
    }
    public boolean compatibleWorker(Channel channel) {
        String capabilities=channel==Channel.OWNER_EXPANSION
            ? "ARRAY['official-discovery/v1','official-checkpoint/v1','official-pause/v1','official-owner/v1','platform-pause/v1']::text[]"
            : "ARRAY['official-discovery/v1','official-checkpoint/v1','official-pause/v1','platform-pause/v1']::text[]";
        return count("SELECT count(*) FROM github_sync_worker_instance WHERE protocol_major=1 AND last_seen_at>CURRENT_TIMESTAMP-INTERVAL '90 seconds' AND capabilities @> "+capabilities,new MapSqlParameterSource())>0;
    }
    public boolean repositoryNeedsOfficialWorker(long namespace,long repositoryId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT official_only FROM github_candidate_discovery_eligibility WHERE namespace_id=:ns AND repository_id=:repo
            """,parameters(namespace).addValue("repo",repositoryId),Boolean.class));
    }
    public boolean hasOfficialOnlyCandidates(long namespace) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM github_candidate_discovery_eligibility
                WHERE namespace_id=:ns AND has_official AND official_only)
            """,parameters(namespace),Boolean.class));
    }
    public boolean pauseCapableWorker() {
        return count("""
            SELECT count(*) FROM github_sync_worker_instance WHERE protocol_major=1
                AND last_seen_at>CURRENT_TIMESTAMP-INTERVAL '90 seconds'
                AND capabilities @> ARRAY['official-pause/v1','platform-pause/v1']::text[]
            """,new MapSqlParameterSource())>0;
    }
    public List<String> progressingWork(long namespace,Long repositoryId,Channel channel) {
        if (channel != null && repositoryId == null) {
            // Channel progress only concerns official discovery. Avoid expanding candidate
            // eligibility for every repository when no repository work can match this filter.
            return jdbc.query("""
                SELECT DISTINCT 'NEW_DISCOVERY' AS work FROM github_sync_task t
                JOIN github_deep_discovery_query q ON q.id=t.official_query_id
                JOIN github_deep_discovery_config config ON config.namespace_id=t.namespace_id
                WHERE t.namespace_id=:ns AND t.kind='DISCOVERY' AND t.status='LEASED'
                    AND t.lease_expires_at>CURRENT_TIMESTAMP
                    AND t.last_progress_at>CURRENT_TIMESTAMP-INTERVAL '90 seconds'
                    AND q.definition_json->>'channel'=:channel AND config.enabled
                    AND NOT EXISTS (SELECT 1 FROM github_official_owner_eligibility oe
                        WHERE oe.query_id=q.id AND oe.excluded)
                """,parameters(namespace).addValue("channel",channel.name()),(rs,n)->rs.getString("work"));
        }
        return jdbc.query("""
            WITH tasks AS (
              SELECT t.kind,t.mode,COALESCE(e.source_id,t.source_id) source_id FROM github_sync_task t
              LEFT JOIN github_discovery_candidate c ON c.namespace_id=t.namespace_id
                AND c.repository_id::text=t.snapshot_json->>'repositoryId'
                AND (CAST(:numericRepo AS bigint) IS NULL OR c.repository_id=:numericRepo)
              LEFT JOIN github_candidate_discovery_eligibility e ON e.candidate_id=c.id AND e.namespace_id=:ns
                AND (CAST(:numericRepo AS bigint) IS NULL OR e.repository_id=:numericRepo)
              LEFT JOIN github_deep_discovery_query q ON q.id=t.official_query_id
              JOIN github_deep_discovery_config config ON config.namespace_id=t.namespace_id
              WHERE t.namespace_id=:ns AND t.status='LEASED' AND t.lease_expires_at>CURRENT_TIMESTAMP
                AND t.last_progress_at>CURRENT_TIMESTAMP-INTERVAL '90 seconds'
                AND (CAST(:repo AS text) IS NULL OR t.snapshot_json->>'repositoryId'=:repo)
                AND (CAST(:channel AS text) IS NULL OR q.definition_json->>'channel'=:channel)
                AND ((t.official_query_id IS NOT NULL AND config.enabled AND NOT EXISTS(
                     SELECT 1 FROM github_official_owner_eligibility oe WHERE oe.query_id=q.id AND oe.excluded))
                  OR (e.has_official AND e.acquisition_allowed))
            ) SELECT DISTINCT CASE WHEN kind='DISCOVERY' THEN 'NEW_DISCOVERY'
                WHEN source_id IS NULL THEN 'CANDIDATE_VALIDATION' ELSE 'SOURCE_UPDATE' END work FROM tasks
              UNION SELECT 'PACKAGE_ACQUISITION' FROM tasks WHERE kind='REPOSITORY_SYNC' AND mode='FULL'
            """,parameters(namespace).addValue("repo",repositoryId==null?null:repositoryId.toString())
                .addValue("channel",channel==null?null:channel.name()).addValue("numericRepo",repositoryId),(rs,n)->rs.getString("work"));
    }
    private long count(String sql,MapSqlParameterSource params) { return jdbc.queryForObject(sql,params,Long.class); }
    private static MapSqlParameterSource parameters(long namespace) { return new MapSqlParameterSource("ns",namespace); }
    private static Instant instant(ResultSet rs,String column) throws SQLException { var value=rs.getTimestamp(column);return value==null?null:value.toInstant(); }
    private static String reasonCode(String value) { return value!=null && value.matches("[A-Z][A-Z0-9_]{0,99}")?value:null; }
}
