package com.iflytek.skillhub.service;

import com.iflytek.skillhub.domain.audit.AuditDetail;
import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.dto.GithubSyncSchedulePolicyRequest;
import com.iflytek.skillhub.dto.GithubSyncSchedulePolicyResponse;
import com.iflytek.skillhub.githubsync.GithubSyncSchedulePolicy;
import com.iflytek.skillhub.githubsync.GithubSyncSchedulePolicyRepository;
import com.iflytek.skillhub.observability.RequestIdAccessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

@Service
public class GithubSyncSchedulePolicyAppService {
    private final GithubSyncSchedulePolicyRepository policies;
    private final AuditLogService audits;
    private final RequestIdAccessor requestIds;
    private final Duration legacyRepositoryInterval;

    public GithubSyncSchedulePolicyAppService(GithubSyncSchedulePolicyRepository policies,
            AuditLogService audits, RequestIdAccessor requestIds,
            @Value("${skillhub.github-sync.repository-check-interval:PT24H}") Duration legacyRepositoryInterval) {
        this.policies = policies;
        this.audits = audits;
        this.requestIds = requestIds;
        this.legacyRepositoryInterval = legacyRepositoryInterval;
    }

    public GithubSyncSchedulePolicyResponse get() {
        return response(policies.get());
    }

    @Transactional
    public GithubSyncSchedulePolicyResponse update(GithubSyncSchedulePolicyRequest request,
            String actorId, AuditRequestContext context) {
        if (request.repositoryHours() < 1 || request.repositoryHours() > 720
                || request.curatedOwnerHours() < 1 || request.curatedOwnerHours() > 720
                || request.searchRuleHours() < 1 || request.searchRuleHours() > 720) {
            throw new DomainBadRequestException("error.githubSync.schedulePolicy.invalid");
        }
        GithubSyncSchedulePolicy previous = policies.getForUpdate();
        GithubSyncSchedulePolicy updated = policies.update(request.repositoryHours(),
                request.curatedOwnerHours(), request.searchRuleHours());
        audits.record(actorId, "UPDATE_GITHUB_SYNC_SCHEDULE_POLICY", "GITHUB_SYNC_POLICY", null,
                requestIds.current(), context.clientIp(), context.userAgent(),
                AuditDetail.builder()
                        .put("previousRepositoryHours", previous.repositoryHours())
                        .put("previousCuratedOwnerHours", previous.curatedOwnerHours())
                        .put("previousSearchRuleHours", previous.searchRuleHours())
                        .put("repositoryHours", updated.repositoryHours())
                        .put("curatedOwnerHours", updated.curatedOwnerHours())
                        .put("searchRuleHours", updated.searchRuleHours()).build());
        return response(updated);
    }

    private GithubSyncSchedulePolicyResponse response(GithubSyncSchedulePolicy policy) {
        Duration effective = policy.repositoryHours() == null
                ? legacyRepositoryInterval : Duration.ofHours(policy.repositoryHours());
        return new GithubSyncSchedulePolicyResponse(policy.repositoryHours(),
                policy.curatedOwnerHours(), policy.searchRuleHours(), effective.toString(),
                policy.updatedAt());
    }
}
