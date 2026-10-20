package com.iflytek.skillhub.listener;

import com.iflytek.skillhub.domain.event.ReviewApprovedEvent;
import com.iflytek.skillhub.domain.event.ReviewRejectedEvent;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

@Component
public class GithubSyncReviewResolutionListener {
    private final JdbcTemplate jdbc;

    public GithubSyncReviewResolutionListener(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void approved(ReviewApprovedEvent event) {
        settle(event.reviewId(), "APPROVED");
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void rejected(ReviewRejectedEvent event) {
        settle(event.reviewId(), "REJECTED");
    }

    private void settle(Long reviewId, String status) {
        UUID ingestionId = jdbc.query("""
                SELECT github_sync_ingestion_id FROM review_task WHERE id = ?
                """, result -> result.next() ? result.getObject(1, UUID.class) : null, reviewId);
        if (ingestionId == null) {
            return;
        }
        if ("APPROVED".equals(status)) {
            Boolean needsResolution = jdbc.query("""
                    SELECT namespace.type = 'CATALOG'
                           AND COALESCE(ingestion.license_evidence_json ->> 'status', 'UNKNOWN') <> 'RECOGNIZED'
                           AND NULLIF(BTRIM(review.review_comment), '') IS NULL AS required
                    FROM review_task review
                    JOIN github_sync_package_ingestion ingestion
                      ON ingestion.id = review.github_sync_ingestion_id
                    JOIN namespace ON namespace.id = ingestion.namespace_id
                    WHERE review.id = ?
                    """, result -> result.next() ? result.getBoolean("required") : false, reviewId);
            if (Boolean.TRUE.equals(needsResolution)) {
                throw new DomainBadRequestException("review.sync.licenseResolutionRequired");
            }
        }
        // The review keeps its immutable source path even after a confirmed move.
        // Fence by the exact current content target and its Skill/source identities.
        UUID latestId = jdbc.query("""
                SELECT binding.latest_ingestion_id
                FROM github_source_skill_binding binding
                JOIN github_sync_package_ingestion ingestion
                  ON ingestion.id = binding.latest_ingestion_id
                 AND ingestion.namespace_id = binding.namespace_id
                 AND ingestion.repository_id = binding.repository_id
                JOIN github_sync_task task
                  ON task.id = ingestion.task_id AND task.source_id = binding.source_id
                 AND task.namespace_id = binding.namespace_id
                JOIN review_task review
                  ON review.github_sync_ingestion_id = ingestion.id
                 AND review.skill_id = binding.skill_id AND review.namespace_id = binding.namespace_id
                JOIN skill_version version
                  ON version.id = review.skill_version_id AND version.skill_id = binding.skill_id
                 AND version.github_sync_ingestion_id = ingestion.id
                WHERE ingestion.id = ? AND review.id = ?
                FOR UPDATE OF binding
                """, result -> result.next() ? result.getObject(1, UUID.class) : null, ingestionId, reviewId);
        if (!ingestionId.equals(latestId)) {
            throw new IllegalStateException("GitHub sync review is no longer the latest target");
        }
        int updated = jdbc.update("""
                UPDATE github_sync_package_ingestion ingestion
                SET status = ?, updated_at = CURRENT_TIMESTAMP
                WHERE ingestion.id = ?
                  AND ingestion.status = 'READY_FOR_REVIEW'
                """, status, ingestionId);
        if (updated != 1) {
            throw new IllegalStateException("GitHub sync review is no longer the latest target");
        }
    }
}
