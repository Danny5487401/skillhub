package com.iflytek.skillhub.service;

import com.iflytek.skillhub.domain.audit.AuditDetail;
import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.githubsync.GithubSyncAdmissionPolicy;
import com.iflytek.skillhub.githubsync.GithubSyncAdmissionPolicyRepository;
import com.iflytek.skillhub.observability.RequestIdAccessor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GithubSyncAdmissionPolicyAppService {
    private final GithubSyncAdmissionPolicyRepository policies;
    private final AuditLogService auditLogService;
    private final RequestIdAccessor requestIdAccessor;

    public GithubSyncAdmissionPolicyAppService(GithubSyncAdmissionPolicyRepository policies,
                                               AuditLogService auditLogService,
                                               RequestIdAccessor requestIdAccessor) {
        this.policies = policies;
        this.auditLogService = auditLogService;
        this.requestIdAccessor = requestIdAccessor;
    }

    public GithubSyncAdmissionPolicy get() {
        return policies.get();
    }

    @Transactional
    public GithubSyncAdmissionPolicy update(int globalHigh, int globalLow,
                                            int namespaceHigh, int namespaceLow,
                                            int candidatePendingLimit, int candidatePerLeaseLimit,
                                            String actorId, AuditRequestContext request) {
        if (globalHigh <= 0 || globalLow < 0 || globalLow >= globalHigh
                || namespaceHigh <= 0 || namespaceLow < 0 || namespaceLow >= namespaceHigh
                || candidatePendingLimit < 1 || candidatePendingLimit > 100000
                || candidatePerLeaseLimit < 1 || candidatePerLeaseLimit > 10000
                || candidatePerLeaseLimit > candidatePendingLimit) {
            throw new DomainBadRequestException("error.githubSync.admissionPolicy.invalid");
        }
        GithubSyncAdmissionPolicy previous = policies.getForUpdate();
        GithubSyncAdmissionPolicy updated = policies.update(
                globalHigh, globalLow, namespaceHigh, namespaceLow,
                candidatePendingLimit, candidatePerLeaseLimit);
        auditLogService.record(actorId, "UPDATE_GITHUB_SYNC_ADMISSION_POLICY", "GITHUB_SYNC_POLICY",
                null, requestIdAccessor.current(), request.clientIp(), request.userAgent(),
                AuditDetail.builder()
                        .put("previousGlobalHigh", previous.globalHigh())
                        .put("previousGlobalLow", previous.globalLow())
                        .put("previousNamespaceHigh", previous.namespaceHigh())
                        .put("previousNamespaceLow", previous.namespaceLow())
                        .put("previousCandidatePendingLimit", previous.candidatePendingLimit())
                        .put("previousCandidatePerLeaseLimit", previous.candidatePerLeaseLimit())
                        .put("globalHigh", updated.globalHigh())
                        .put("globalLow", updated.globalLow())
                        .put("namespaceHigh", updated.namespaceHigh())
                        .put("namespaceLow", updated.namespaceLow())
                        .put("candidatePendingLimit", updated.candidatePendingLimit())
                        .put("candidatePerLeaseLimit", updated.candidatePerLeaseLimit())
                        .build());
        return updated;
    }
}
