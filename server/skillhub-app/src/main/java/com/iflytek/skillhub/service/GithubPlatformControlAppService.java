package com.iflytek.skillhub.service;

import com.iflytek.skillhub.domain.audit.AuditDetail;
import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.domain.shared.exception.DomainConflictException;
import com.iflytek.skillhub.githubsync.GithubOfficialProtocolException;
import com.iflytek.skillhub.githubsync.GithubPlatformControlRepository;
import com.iflytek.skillhub.observability.RequestIdAccessor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GithubPlatformControlAppService {
    private final GithubPlatformControlRepository controls;
    private final AuditLogService audits;
    private final RequestIdAccessor requestIds;

    public GithubPlatformControlAppService(GithubPlatformControlRepository controls, AuditLogService audits,
                                          RequestIdAccessor requestIds) {
        this.controls = controls;
        this.audits = audits;
        this.requestIds = requestIds;
    }

    public GithubPlatformControlRepository.State get() {
        return controls.get();
    }

    @Transactional
    public GithubPlatformControlRepository.State update(long expectedVersion, boolean paused, String actor,
                                                         AuditRequestContext context) {
        GithubPlatformControlRepository.State state;
        try {
            state = controls.update(expectedVersion, paused, actor);
        } catch (GithubOfficialProtocolException exception) {
            if ("CONTROL_CHANGED".equals(exception.code())) {
                throw new DomainConflictException("error.request.conflict");
            }
            throw exception;
        }
        audits.record(actor, "UPDATE_GITHUB_PLATFORM_CONTROL", "GITHUB_PLATFORM_CONTROL", null, requestIds.current(),
                context.clientIp(), context.userAgent(), AuditDetail.builder().put("namespaceId", state.namespaceId())
                        .put("version", state.version()).put("paused", state.desiredPaused()).build());
        return state;
    }
}
