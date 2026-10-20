package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.githubsync.GithubSyncAdmissionPolicy;
import com.iflytek.skillhub.githubsync.GithubSyncAdmissionPolicyRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/** The single policy row is the same lock used by package admission. */
@Repository
public class JdbcGithubSyncAdmissionPolicyRepository implements GithubSyncAdmissionPolicyRepository {
    private final JdbcTemplate jdbc;

    public JdbcGithubSyncAdmissionPolicyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public GithubSyncAdmissionPolicy get() {
        return Objects.requireNonNull(jdbc.query("""
                SELECT global_high, global_low, namespace_high, namespace_low,
                       candidate_pending_limit, candidate_per_lease_limit,
                       global_paused, updated_at
                FROM github_sync_admission_policy WHERE id = TRUE
                """, result -> result.next() ? map(result) : null),
                "GitHub sync admission policy is missing");
    }

    @Override
    public GithubSyncAdmissionPolicy getForUpdate() {
        return Objects.requireNonNull(jdbc.query("""
                SELECT global_high, global_low, namespace_high, namespace_low,
                       candidate_pending_limit, candidate_per_lease_limit,
                       global_paused, updated_at
                FROM github_sync_admission_policy WHERE id = TRUE FOR UPDATE
                """, result -> result.next() ? map(result) : null),
                "GitHub sync admission policy is missing");
    }

    @Override
    public GithubSyncAdmissionPolicy update(int globalHigh, int globalLow,
                                            int namespaceHigh, int namespaceLow,
                                            int candidatePendingLimit, int candidatePerLeaseLimit) {
        return Objects.requireNonNull(jdbc.query("""
                UPDATE github_sync_admission_policy
                SET global_high = ?, global_low = ?, namespace_high = ?, namespace_low = ?,
                    candidate_pending_limit = ?, candidate_per_lease_limit = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = TRUE
                RETURNING global_high, global_low, namespace_high, namespace_low,
                          candidate_pending_limit, candidate_per_lease_limit,
                          global_paused, updated_at
                """, statement -> {
            statement.setInt(1, globalHigh);
            statement.setInt(2, globalLow);
            statement.setInt(3, namespaceHigh);
            statement.setInt(4, namespaceLow);
            statement.setInt(5, candidatePendingLimit);
            statement.setInt(6, candidatePerLeaseLimit);
        }, result -> result.next() ? map(result) : null),
                "GitHub sync admission policy is missing");
    }

    private static GithubSyncAdmissionPolicy map(ResultSet result) throws SQLException {
        return new GithubSyncAdmissionPolicy(
                (Integer) result.getObject("global_high"),
                (Integer) result.getObject("global_low"),
                (Integer) result.getObject("namespace_high"),
                (Integer) result.getObject("namespace_low"),
                result.getInt("candidate_pending_limit"),
                result.getInt("candidate_per_lease_limit"),
                result.getBoolean("global_paused"),
                result.getTimestamp("updated_at").toInstant());
    }
}
