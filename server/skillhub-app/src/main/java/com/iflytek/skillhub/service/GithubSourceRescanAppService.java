package com.iflytek.skillhub.service;

import com.iflytek.skillhub.domain.audit.AuditDetail;
import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.dto.GithubSourceRescanResponse;
import com.iflytek.skillhub.githubsync.GithubSourceTaskRepository;
import com.iflytek.skillhub.githubsync.GithubSyncPublicationRepository;
import com.iflytek.skillhub.githubsync.GithubSourceIgnoreRepository;
import com.iflytek.skillhub.observability.RequestIdAccessor;
import com.iflytek.skillhub.repository.GithubSyncManagementQueryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class GithubSourceRescanAppService {
    private final GithubSourceTaskRepository tasks;
    private final GithubSyncPublicationRepository publications;
    private final GithubSourceIgnoreRepository ignores;
    private final GithubTeamSourceAppService teamSources;
    private final GithubSyncManagementQueryRepository management;
    private final AuditLogService audits;
    private final RequestIdAccessor requestIds;

    public GithubSourceRescanAppService(GithubSourceTaskRepository tasks,
            GithubSyncPublicationRepository publications, GithubSourceIgnoreRepository ignores,
            GithubTeamSourceAppService teamSources, GithubSyncManagementQueryRepository management,
            AuditLogService audits, RequestIdAccessor requestIds) {
        this.tasks = tasks;
        this.publications = publications;
        this.ignores = ignores;
        this.teamSources = teamSources;
        this.management = management;
        this.audits = audits;
        this.requestIds = requestIds;
    }

    @Transactional
    public GithubSourceRescanResponse platform(long sourceId, String actorId,
                                               AuditRequestContext context) {
        return request(management.platformNamespaceId(), sourceId, actorId, context);
    }

    @Transactional
    public GithubSourceRescanResponse team(String slug, long sourceId, String actorId,
                                           AuditRequestContext context) {
        long namespaceId = teamSources.requireTeamAdmin(slug, actorId, true).getId();
        return request(namespaceId, sourceId, actorId, context);
    }

    @Transactional
    public GithubSourceRescanResponse retryPlatformSkill(long sourceId, String skillPath,
            String actorId, AuditRequestContext context) {
        return retrySkill(management.platformNamespaceId(), sourceId, skillPath, actorId, context);
    }

    @Transactional
    public GithubSourceRescanResponse retryTeamSkill(String slug, long sourceId, String skillPath,
            String actorId, AuditRequestContext context) {
        long namespaceId = teamSources.requireTeamAdmin(slug, actorId, true).getId();
        return retrySkill(namespaceId, sourceId, skillPath, actorId, context);
    }

    private GithubSourceRescanResponse retrySkill(long namespaceId, long sourceId,
            String skillPath, String actorId, AuditRequestContext context) {
        if (skillPath == null || skillPath.isBlank() || skillPath.length() > 1024) {
            throw new DomainBadRequestException("error.badRequest");
        }
        Instant now = Instant.now();
        var result = tasks.requestRescan(namespaceId, sourceId, now)
                .orElseThrow(() -> new DomainNotFoundException("error.githubSync.source.notFound"));
        if ("PAUSED".equals(result.status())) {
            throw new DomainBadRequestException("error.githubSync.source.paused");
        }
        boolean ignored;
        try {
            ignored = ignores.ignoredForSource(sourceId, skillPath);
        } catch (IllegalArgumentException exception) {
            throw new DomainBadRequestException("error.badRequest");
        }
        if (ignored || !publications.requestRejectedRetry(namespaceId, sourceId, skillPath, now)) {
            throw new DomainBadRequestException("error.badRequest");
        }
        audits.record(actorId, "RETRY_REJECTED_GITHUB_SKILL", "GITHUB_SOURCE", sourceId,
                requestIds.current(), context.clientIp(), context.userAgent(),
                AuditDetail.builder().put("namespaceId", namespaceId).put("skillPath", skillPath)
                        .put("taskId", result.taskId()).build());
        return new GithubSourceRescanResponse(sourceId, result.status(), result.taskId(),
                result.taskStatus(), result.nextCheckAt());
    }

    private GithubSourceRescanResponse request(long namespaceId, long sourceId,
                                               String actorId, AuditRequestContext context) {
        var result = tasks.requestRescan(namespaceId, sourceId, Instant.now())
                .orElseThrow(() -> new DomainNotFoundException("error.githubSync.source.notFound"));
        if ("PAUSED".equals(result.status())) {
            throw new DomainBadRequestException("error.githubSync.source.paused");
        }
        audits.record(actorId, "REQUEST_GITHUB_SOURCE_RESCAN", "GITHUB_SOURCE", sourceId,
                requestIds.current(), context.clientIp(), context.userAgent(),
                AuditDetail.builder().put("namespaceId", namespaceId)
                        .put("status", result.status()).put("taskId", result.taskId()).build());
        return new GithubSourceRescanResponse(sourceId, result.status(), result.taskId(),
                result.taskStatus(), result.nextCheckAt());
    }
}
