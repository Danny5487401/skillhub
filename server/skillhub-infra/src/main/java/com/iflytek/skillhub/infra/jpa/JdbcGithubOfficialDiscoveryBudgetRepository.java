package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.githubsync.GithubOfficialDiscoveryBudgetRepository;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcGithubOfficialDiscoveryBudgetRepository implements GithubOfficialDiscoveryBudgetRepository {
    private final JdbcTemplate jdbc;

    public JdbcGithubOfficialDiscoveryBudgetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void reserveSuccessfulClaim(UUID taskId, long generation) {
        // Claim already holds config -> budget -> task -> credential locks in this transaction.
        // The reservation key makes retries idempotent; task retention may clean the audit row without resetting the global pacing timestamp.
        boolean official=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM github_sync_task WHERE id=? AND official_query_id IS NOT NULL)",Boolean.class,taskId));
        if (!official) return;
        int reserved=jdbc.update("""
                WITH lease_grant AS MATERIALIZED (
                    SELECT task.id, task.lease_generation, task.official_query_id,
                           config.budget_profile,
                           (task.snapshot_json #>> '{officialDiscovery,budget,maxRequests}')::integer AS requests,
                           clock_timestamp() AS granted_at
                    FROM github_sync_task task
                    JOIN github_deep_discovery_config config ON config.namespace_id=task.namespace_id
                    WHERE task.id=? AND task.lease_generation=? AND task.status='LEASED'
                      AND task.kind='DISCOVERY' AND task.official_query_id IS NOT NULL
                      AND task.lease_expires_at > clock_timestamp()
                ), reservation AS (
                    INSERT INTO github_official_discovery_reservation
                        (task_id,lease_generation,budget_profile,reserved_requests,granted_at,next_lease_eligible_at)
                    SELECT id,lease_generation,budget_profile,requests,granted_at,
                           granted_at + make_interval(secs => requests * 3600.0 / CASE budget_profile
                               WHEN 'ECONOMY' THEN 60 WHEN 'BALANCED' THEN 180 ELSE 360 END)
                    FROM lease_grant
                    ON CONFLICT DO NOTHING
                    RETURNING granted_at,next_lease_eligible_at
                ), paced AS (
                    UPDATE github_official_discovery_budget budget
                    SET next_lease_eligible_at=reservation.next_lease_eligible_at
                    FROM reservation WHERE budget.id
                )
                UPDATE github_deep_discovery_query query
                SET last_successful_claim_at=reservation.granted_at
                FROM lease_grant,reservation WHERE query.id=lease_grant.official_query_id
                """, taskId, generation);
        if (reserved == 0 && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM github_official_discovery_reservation WHERE task_id=? AND lease_generation=?)",Boolean.class,taskId,generation))) {
            throw new IllegalStateException("Official discovery lease expired before budget reservation");
        }
    }
}
