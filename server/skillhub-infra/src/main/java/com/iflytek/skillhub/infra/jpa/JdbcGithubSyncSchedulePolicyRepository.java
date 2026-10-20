package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.githubsync.GithubSyncSchedulePolicy;
import com.iflytek.skillhub.githubsync.GithubSyncSchedulePolicyRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcGithubSyncSchedulePolicyRepository implements GithubSyncSchedulePolicyRepository {
    private final JdbcTemplate jdbc;

    public JdbcGithubSyncSchedulePolicyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public GithubSyncSchedulePolicy get() {
        return read("SELECT * FROM github_sync_schedule_policy WHERE id = TRUE");
    }

    @Override
    public GithubSyncSchedulePolicy getForUpdate() {
        return read("SELECT * FROM github_sync_schedule_policy WHERE id = TRUE FOR UPDATE");
    }

    @Override
    public GithubSyncSchedulePolicy update(int repositoryHours, int curatedOwnerHours, int searchRuleHours) {
        jdbc.update("""
                UPDATE github_sync_schedule_policy
                SET repository_hours = ?, curated_owner_hours = ?, search_rule_hours = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = TRUE
                """, repositoryHours, curatedOwnerHours, searchRuleHours);
        jdbc.update("""
                UPDATE github_discovery_entry
                SET target_interval_seconds = ?,
                    next_due_at = LEAST(next_due_at, CURRENT_TIMESTAMP + (? * INTERVAL '1 second')),
                    updated_at = CURRENT_TIMESTAMP
                WHERE entry_type = 'CURATED_REPOSITORY'
                """, repositoryHours * 3600, repositoryHours * 3600);
        jdbc.update("""
                UPDATE github_discovery_entry
                SET target_interval_seconds = ?,
                    next_due_at = LEAST(next_due_at, CURRENT_TIMESTAMP + (? * INTERVAL '1 second')),
                    updated_at = CURRENT_TIMESTAMP
                WHERE entry_type = 'CURATED_OWNER'
                """, curatedOwnerHours * 3600, curatedOwnerHours * 3600);
        jdbc.update("""
                UPDATE github_discovery_entry
                SET target_interval_seconds = ?,
                    next_due_at = LEAST(next_due_at, CURRENT_TIMESTAMP + (? * INTERVAL '1 second')),
                    updated_at = CURRENT_TIMESTAMP
                WHERE entry_type = 'SEARCH_RULE'
                """, searchRuleHours * 3600, searchRuleHours * 3600);
        jdbc.update("""
                UPDATE github_source_binding
                SET next_check_at = LEAST(COALESCE(next_check_at, CURRENT_TIMESTAMP),
                                          CURRENT_TIMESTAMP + (? * INTERVAL '1 second')),
                    updated_at = CURRENT_TIMESTAMP
                WHERE status = 'ACTIVE'
                """, repositoryHours * 3600);
        return get();
    }

    private GithubSyncSchedulePolicy read(String sql) {
        return jdbc.query(sql, result -> {
            if (!result.next()) {
                throw new IllegalStateException("GitHub sync schedule policy is missing");
            }
            return new GithubSyncSchedulePolicy(result.getObject("repository_hours", Integer.class),
                    result.getInt("curated_owner_hours"), result.getInt("search_rule_hours"),
                    result.getTimestamp("updated_at").toInstant());
        });
    }
}
