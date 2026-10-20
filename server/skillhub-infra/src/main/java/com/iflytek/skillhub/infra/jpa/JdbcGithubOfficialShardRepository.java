package com.iflytek.skillhub.infra.jpa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iflytek.skillhub.githubsync.GithubOfficialDiscoveryBatch;
import com.iflytek.skillhub.githubsync.GithubOfficialProtocolException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Bounded sequential binary trees. Callers hold configuration/query lease locks in one transaction. */
@Repository
public class JdbcGithubOfficialShardRepository {
    public static final int MAX_DEPTH = 32;
    public static final int MAX_CHILDREN = 126;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcGithubOfficialShardRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Work next(UUID queryId, UUID proposedTaskId, ObjectNode freshSnapshot, int candidateBudget, Instant now) {
        List<Shard> pending = jdbc.query("""
                SELECT * FROM github_official_discovery_shard WHERE query_id = ?
                  AND (state = 'PENDING' OR (state = 'GAP' AND retry_at <= ?))
                ORDER BY CASE WHEN state = 'PENDING' THEN 0 ELSE 1 END, range_since, depth, id LIMIT 1 FOR UPDATE
                """, (result, row) -> row(result), queryId, Timestamp.from(now));
        if (pending.isEmpty()) {
            boolean unfinished = Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM github_official_discovery_shard
                        WHERE query_id = ? AND parent_id IS NULL AND state <> 'COMPLETE')
                    """, Boolean.class, queryId));
            if (unfinished) {
                throw new IllegalStateException("Official shard tree has no resumable leaf");
            }
            jdbc.update("DELETE FROM github_official_discovery_shard WHERE query_id = ? AND parent_id IS NULL AND state = 'COMPLETE'", queryId);
            JsonNode official = freshSnapshot.path("officialDiscovery");
            UUID rootId = UUID.randomUUID();
            insert(rootId, queryId, rootId, null, 0, official, "PENDING", null);
            pending = jdbc.query("SELECT * FROM github_official_discovery_shard WHERE id = ?",
                    (result, row) -> row(result), rootId);
        }
        Shard node = pending.getFirst();
        Shard root = root(node.rootId());
        int descendants = jdbc.queryForObject("SELECT count(*) FROM github_official_discovery_shard WHERE root_id = ? AND parent_id IS NOT NULL", Integer.class, root.id());
        UUID taskId = node.taskId() == null ? proposedTaskId : node.taskId();
        boolean reuse = node.taskId() != null;
        ObjectNode official = node.snapshot().deepCopy();
        official.put("shardId", taskId.toString());
        official.remove("checkpoint");
        ObjectNode budget = (ObjectNode) official.path("budget");
        budget.put("maxSplitDepth", Math.min(budget.path("maxSplitDepth").asInt(), Math.max(0, root.snapshot().path("budget").path("maxSplitDepth").asInt() - node.depth())));
        budget.put("maxChildShards", Math.min(budget.path("maxChildShards").asInt(), Math.max(0, root.snapshot().path("budget").path("maxChildShards").asInt() - descendants)));
        budget.put("candidateBudget", Math.min(budget.path("candidateBudget").asInt(), candidateBudget));
        // Task is inserted by the scheduler before task_id is assigned (FK order).
        ObjectNode wrapper = mapper.createObjectNode();
        wrapper.set("officialDiscovery", official);
        String checkpoint = node.checkpoint();
        if (checkpoint != null && !reuse) {
            try {
                ObjectNode saved = (ObjectNode) mapper.readTree(checkpoint);
                saved.put("shardId", taskId.toString());
                checkpoint = saved.toString();
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                throw new IllegalStateException("Invalid durable GAP checkpoint", exception);
            }
        }
        return new Work(node.id(), taskId, reuse, wrapper, checkpoint, node.generation());
    }

    public void assigned(Work work) {
        int updated = jdbc.update("""
                UPDATE github_official_discovery_shard SET state = 'ACTIVE', task_id = ?, snapshot_json = ?::jsonb
                WHERE id = ? AND state IN ('PENDING', 'GAP')
                """, work.taskId(), work.snapshot().path("officialDiscovery").toString(), work.shardId());
        require(updated == 1);
    }

    public void gap(UUID taskId, UUID queryId, JsonNode snapshot, String checkpoint,
                    long generation, Instant retryAt, String reason) {
        Shard node = ensure(taskId, queryId, snapshot);
        require("ACTIVE".equals(node.state()));
        jdbc.update("""
                UPDATE github_official_discovery_shard SET state = 'GAP', retry_at = ?, gap_reason = ?,
                    gap_checkpoint = ?, last_lease_generation = ?, snapshot_json = ?::jsonb WHERE id = ?
                """, Timestamp.from(retryAt), reason, checkpoint, generation, snapshot.toString(), node.id());
    }

    public Instant nextDue(UUID queryId, Instant now) {
        return jdbc.queryForObject("""
                SELECT CASE WHEN bool_or(state = 'PENDING') THEN ? ELSE min(retry_at) FILTER (WHERE state = 'GAP') END
                FROM github_official_discovery_shard WHERE query_id = ?
                """, (result, row) -> result.getTimestamp(1).toInstant(), Timestamp.from(now), queryId);
    }

    /** Lazily represents tasks created before V102, keeping their immutable zero-split budget. */
    public Shard ensure(UUID taskId, UUID queryId, JsonNode snapshot) {
        List<Shard> nodes = jdbc.query("SELECT * FROM github_official_discovery_shard WHERE task_id = ? FOR UPDATE",
                (result, row) -> row(result), taskId);
        if (!nodes.isEmpty()) {
            return nodes.getFirst();
        }
        UUID id = UUID.randomUUID();
        insert(id, queryId, id, null, 0, snapshot, "ACTIVE", taskId);
        return jdbc.queryForObject("SELECT * FROM github_official_discovery_shard WHERE id = ?", (result, row) -> row(result), id);
    }

    public void split(UUID taskId, UUID queryId, JsonNode snapshot, List<GithubOfficialDiscoveryBatch.Range> ranges) {
        Shard parent = ensure(taskId, queryId, snapshot);
        Shard root = root(parent.rootId());
        require("ACTIVE".equals(parent.state()) && ranges != null && ranges.size() == 2);
        int descendants = jdbc.queryForObject("SELECT count(*) FROM github_official_discovery_shard WHERE root_id = ? AND parent_id IS NOT NULL", Integer.class, root.id());
        int maxDepth = Math.min(MAX_DEPTH, root.snapshot().path("budget").path("maxSplitDepth").asInt());
        int maxChildren = Math.min(MAX_CHILDREN, root.snapshot().path("budget").path("maxChildShards").asInt());
        require(parent.depth() < maxDepth && descendants + 2 <= maxChildren
                && snapshot.path("budget").path("maxSplitDepth").asInt() > 0
                && snapshot.path("budget").path("maxChildShards").asInt() >= 2);
        var left = ranges.get(0);
        var right = ranges.get(1);
        require(valid(left) && valid(right) && parent.since().equals(left.since())
                && left.until().equals(right.since()) && parent.until().equals(right.until()));
        Instant cutoff = Instant.parse(snapshot.path("fixedCutoffAt").asText());
        require(!right.until().isAfter(cutoff));
        for (var range : ranges) {
            ObjectNode child = parent.snapshot().deepCopy();
            child.remove("checkpoint");
            child.putObject("range").put("since", range.since().toString()).put("until", range.until().toString());
            insert(UUID.randomUUID(), queryId, root.id(), parent.id(), parent.depth() + 1, child, "PENDING", null);
        }
        jdbc.update("UPDATE github_official_discovery_shard SET state = 'SPLIT' WHERE id = ?", parent.id());
    }

    public Completion finish(UUID taskId, UUID queryId, JsonNode snapshot, boolean split) {
        Shard node = ensure(taskId, queryId, snapshot);
        Shard root = root(node.rootId());
        require(split ? "SPLIT".equals(node.state()) : "ACTIVE".equals(node.state()));
        if (!split) {
            jdbc.update("UPDATE github_official_discovery_shard SET state = 'COMPLETE' WHERE id = ?", node.id());
        }
        boolean unfinished = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM github_official_discovery_shard
                    WHERE root_id = ? AND state NOT IN ('SPLIT', 'COMPLETE'))
                """, Boolean.class, root.id()));
        if (unfinished) {
            return null;
        }
        jdbc.update("UPDATE github_official_discovery_shard SET state = 'COMPLETE', completed_at = now() WHERE id = ?", root.id());
        return new Completion(root.until(), "HISTORICAL".equals(root.snapshot().path("mode").asText()));
    }

    public void recover(UUID taskId, UUID replacement, UUID queryId, JsonNode snapshot) {
        Shard node = ensure(taskId, queryId, snapshot);
        require(!"SPLIT".equals(node.state()));
        jdbc.update("UPDATE github_official_discovery_shard SET task_id = ? WHERE id = ?", replacement, node.id());
    }

    public void replayFailed(long namespaceId) {
        jdbc.update("""
                UPDATE github_official_discovery_shard shard SET task_id = NULL,
                    state = CASE WHEN shard.state = 'ACTIVE' THEN
                        CASE WHEN shard.gap_checkpoint IS NULL THEN 'PENDING' ELSE 'GAP' END ELSE shard.state END,
                    last_lease_generation = GREATEST(shard.last_lease_generation, task.lease_generation)
                FROM github_sync_task task WHERE shard.task_id = task.id AND task.status = 'FAILED' AND task.namespace_id = ?
                """, namespaceId);
    }

    private Shard root(UUID id) {
        return jdbc.queryForObject("SELECT * FROM github_official_discovery_shard WHERE id = ? FOR UPDATE",
                (result, row) -> row(result), id);
    }

    private boolean valid(GithubOfficialDiscoveryBatch.Range range) {
        return range != null && range.since() != null && range.until() != null
                && range.since().getNano() == 0 && range.until().getNano() == 0 && range.since().isBefore(range.until())
                && (range.partition() == null || range.partition().isEmpty());
    }

    private void insert(UUID id, UUID queryId, UUID rootId, UUID parentId, int depth, JsonNode snapshot,
                        String state, UUID taskId) {
        jdbc.update("""
                INSERT INTO github_official_discovery_shard
                    (id, query_id, root_id, parent_id, depth, range_since, range_until, snapshot_json, state, task_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """, id, queryId, rootId, parentId, depth,
                Timestamp.from(Instant.parse(snapshot.path("range").path("since").asText())),
                Timestamp.from(Instant.parse(snapshot.path("range").path("until").asText())), snapshot.toString(), state, taskId);
    }

    private Shard row(java.sql.ResultSet result) throws java.sql.SQLException {
        try {
            return new Shard(result.getObject("id", UUID.class), result.getObject("root_id", UUID.class),
                    result.getInt("depth"), result.getTimestamp("range_since").toInstant(),
                    result.getTimestamp("range_until").toInstant(), (ObjectNode) mapper.readTree(result.getString("snapshot_json")),
                    result.getString("state"), result.getObject("task_id", UUID.class),
                    result.getString("gap_checkpoint"), result.getLong("last_lease_generation"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Invalid persisted official shard snapshot", exception);
        }
    }

    private static void require(boolean valid) {
        if (!valid) {
            throw new GithubOfficialProtocolException(409, "RESULT_CONFLICT");
        }
    }

    public record Shard(UUID id, UUID rootId, int depth, Instant since, Instant until, ObjectNode snapshot, String state, UUID taskId, String checkpoint, long generation) {}
    public record Work(UUID shardId, UUID taskId, boolean reuse, ObjectNode snapshot, String checkpoint, long generation) {}
    public record Completion(Instant until, boolean historical) {}
}
