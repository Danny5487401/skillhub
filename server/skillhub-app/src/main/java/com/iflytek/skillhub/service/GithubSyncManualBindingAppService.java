package com.iflytek.skillhub.service;

import com.iflytek.skillhub.domain.audit.AuditDetail;
import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.dto.GithubSyncManualBindingResponse;
import com.iflytek.skillhub.githubsync.GithubSourceIgnoreRepository;
import com.iflytek.skillhub.infra.jpa.JdbcGithubSyncManualBindingRepository;
import com.iflytek.skillhub.observability.RequestIdAccessor;
import com.iflytek.skillhub.repository.GithubSyncManagementQueryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class GithubSyncManualBindingAppService {
    private final JdbcGithubSyncManualBindingRepository bindings;
    private final GithubTeamSourceAppService teamSources;
    private final GithubSyncManagementQueryRepository management;
    private final AuditLogService audits;
    private final RequestIdAccessor requestIds;
    private final GithubSourceIgnoreRepository ignores;

    public GithubSyncManualBindingAppService(JdbcGithubSyncManualBindingRepository bindings,
            GithubTeamSourceAppService teamSources, GithubSyncManagementQueryRepository management,
            AuditLogService audits, RequestIdAccessor requestIds,
            GithubSourceIgnoreRepository ignores) {
        this.bindings = bindings;
        this.teamSources = teamSources;
        this.management = management;
        this.audits = audits;
        this.requestIds = requestIds;
        this.ignores = ignores;
    }

    @Transactional
    public GithubSyncManualBindingResponse bindPlatform(UUID ingestionId, long skillId,
            String reason, String actorId, AuditRequestContext context) {
        return bind(management.platformNamespaceId(), ingestionId, skillId, reason, actorId, context);
    }

    @Transactional
    public GithubSyncManualBindingResponse bindTeam(String namespaceSlug, UUID ingestionId,
            long skillId, String reason, String actorId, AuditRequestContext context) {
        long namespaceId = teamSources.requireTeamAdmin(namespaceSlug, actorId, true).getId();
        return bind(namespaceId, ingestionId, skillId, reason, actorId, context);
    }

    private GithubSyncManualBindingResponse bind(long namespaceId, UUID ingestionId, long skillId,
            String reason, String actorId, AuditRequestContext context) {
        if (reason == null || reason.trim().length() < 10 || reason.length() > 1000) {
            throw new DomainBadRequestException("error.githubSync.binding.reason");
        }
        var result = bindings.bind(namespaceId, ingestionId, skillId);
        if (result.previousPath() != null
                && (ignores.ignoredForSource(result.sourceId(), result.previousPath())
                    || ignores.ignoredForSource(result.sourceId(), result.skillPath()))) {
            throw new DomainBadRequestException("error.githubSync.binding.notResolvable");
        }
        audits.record(actorId, result.previousPath() == null
                        ? "BIND_GITHUB_SOURCE_TO_SKILL" : "CONFIRM_GITHUB_SKILL_PATH_MOVE",
                "SKILL", skillId,
                requestIds.current(), context.clientIp(), context.userAgent(),
                AuditDetail.builder().put("namespaceId", result.namespaceId())
                        .put("repositoryId", result.repositoryId())
                        .put("sourceId", result.sourceId())
                        .put("ingestionId", result.ingestionId())
                        .put("skillPath", result.skillPath())
                        .put("previousPath", result.previousPath())
                        .put("reason", reason.trim()).build());
        return new GithubSyncManualBindingResponse(result.ingestionId(), result.sourceId(),
                result.skillId(), result.skillSlug(), result.skillPath(), "QUEUED_FOR_RETRY");
    }
}
