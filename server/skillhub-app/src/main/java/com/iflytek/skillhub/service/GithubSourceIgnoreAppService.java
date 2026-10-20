package com.iflytek.skillhub.service;

import com.iflytek.skillhub.domain.audit.AuditDetail;
import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.domain.namespace.NamespaceMemberRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.namespace.NamespaceStatus;
import com.iflytek.skillhub.domain.namespace.NamespaceType;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.dto.GithubSourceIgnoreResponse;
import com.iflytek.skillhub.githubsync.GithubSourceIgnoreRepository;
import com.iflytek.skillhub.githubsync.GithubSkillPathPolicy;
import com.iflytek.skillhub.observability.RequestIdAccessor;
import com.iflytek.skillhub.repository.GithubSyncManagementQueryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class GithubSourceIgnoreAppService {
    private final GithubSourceIgnoreRepository sources;
    private final GithubSyncManagementQueryRepository management;
    private final NamespaceRepository namespaces;
    private final NamespaceMemberRepository members;
    private final AuditLogService auditLogs;
    private final RequestIdAccessor requestIds;

    public GithubSourceIgnoreAppService(GithubSourceIgnoreRepository sources,
            GithubSyncManagementQueryRepository management, NamespaceRepository namespaces,
            NamespaceMemberRepository members, AuditLogService auditLogs,
            RequestIdAccessor requestIds) {
        this.sources = sources;
        this.management = management;
        this.namespaces = namespaces;
        this.members = members;
        this.auditLogs = auditLogs;
        this.requestIds = requestIds;
    }

    public GithubSourceIgnoreResponse platform(long sourceId) {
        return get(management.platformNamespaceId(), sourceId);
    }

    @Transactional
    public GithubSourceIgnoreResponse updatePlatform(long sourceId, List<String> patterns,
            String actorId, AuditRequestContext context) {
        return update(management.platformNamespaceId(), sourceId, patterns, actorId, context);
    }

    public GithubSourceIgnoreResponse team(String slug, long sourceId, String userId) {
        return get(teamNamespaceId(slug, userId, false), sourceId);
    }

    @Transactional
    public GithubSourceIgnoreResponse updateTeam(String slug, long sourceId, List<String> patterns,
            String userId, AuditRequestContext context) {
        return update(teamNamespaceId(slug, userId, true), sourceId, patterns, userId, context);
    }

    private GithubSourceIgnoreResponse get(long namespaceId, long sourceId) {
        return new GithubSourceIgnoreResponse(sourceId, sources.find(namespaceId, sourceId)
                .orElseThrow(() -> new DomainNotFoundException("error.githubSync.source.notFound")));
    }

    private GithubSourceIgnoreResponse update(long namespaceId, long sourceId, List<String> patterns,
            String actorId, AuditRequestContext context) {
        List<String> normalized;
        try {
            normalized = GithubSkillPathPolicy.normalizePatterns(patterns);
        } catch (IllegalArgumentException exception) {
            throw new DomainBadRequestException("error.githubSync.source.ignorePatterns");
        }
        List<String> saved = sources.replace(namespaceId, sourceId, normalized)
                .orElseThrow(() -> new DomainNotFoundException("error.githubSync.source.notFound"));
        auditLogs.record(actorId, "UPDATE_GITHUB_SOURCE_IGNORE_PATTERNS", "GITHUB_SOURCE", sourceId,
                requestIds.current(), context.clientIp(), context.userAgent(),
                AuditDetail.builder().put("namespaceId", namespaceId)
                        .put("patternCount", saved.size()).build());
        return new GithubSourceIgnoreResponse(sourceId, saved);
    }

    private long teamNamespaceId(String slug, String userId, boolean write) {
        var namespace = namespaces.findBySlug(slug)
                .orElseThrow(() -> new DomainNotFoundException("error.namespace.slug.notFound", slug));
        if (namespace.getType() != NamespaceType.TEAM) {
            throw new DomainNotFoundException("error.namespace.slug.notFound", slug);
        }
        NamespaceRole role = members.findByNamespaceIdAndUserId(namespace.getId(), userId)
                .map(member -> member.getRole()).orElse(null);
        if (role != NamespaceRole.OWNER && role != NamespaceRole.ADMIN) {
            throw new DomainForbiddenException("error.githubSync.teamSource.forbidden");
        }
        if (write && namespace.getStatus() != NamespaceStatus.ACTIVE) {
            throw new DomainBadRequestException("error.namespace.readonly", slug);
        }
        return namespace.getId();
    }
}
