package com.iflytek.skillhub.service;

import com.iflytek.skillhub.domain.audit.AuditDetail;
import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceMemberRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.namespace.NamespaceStatus;
import com.iflytek.skillhub.domain.namespace.NamespaceType;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.dto.GithubDiscoveryEntryResponse;
import com.iflytek.skillhub.dto.GithubTeamRepositoryRequest;
import com.iflytek.skillhub.dto.GithubSyncRunSummaryResponse;
import com.iflytek.skillhub.dto.GithubSyncSourceSummaryResponse;
import com.iflytek.skillhub.dto.GithubSyncSkillPathResponse;
import com.iflytek.skillhub.dto.PageResponse;
import com.iflytek.skillhub.githubsync.GithubDiscoveryEntry;
import com.iflytek.skillhub.githubsync.GithubDiscoveryEntryRepository;
import com.iflytek.skillhub.observability.RequestIdAccessor;
import com.iflytek.skillhub.repository.GithubSyncManagementQueryRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class GithubTeamSourceAppService {
    private static final Pattern OWNER = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$");
    private static final Pattern REPOSITORY = Pattern.compile("^[A-Za-z0-9_.-]{1,100}$");
    private static final Set<String> VISIBILITIES = Set.of("PUBLIC", "NAMESPACE_ONLY");

    private final NamespaceRepository namespaces;
    private final NamespaceMemberRepository members;
    private final GithubDiscoveryEntryRepository entries;
    private final GithubSyncManagementQueryRepository management;
    private final AuditLogService auditLogService;
    private final RequestIdAccessor requestIdAccessor;

    public GithubTeamSourceAppService(NamespaceRepository namespaces,
                                      NamespaceMemberRepository members,
                                      GithubDiscoveryEntryRepository entries,
                                      GithubSyncManagementQueryRepository management,
                                      AuditLogService auditLogService,
                                      RequestIdAccessor requestIdAccessor) {
        this.namespaces = namespaces;
        this.members = members;
        this.entries = entries;
        this.management = management;
        this.auditLogService = auditLogService;
        this.requestIdAccessor = requestIdAccessor;
    }

    public List<GithubDiscoveryEntryResponse> list(String namespaceSlug, String userId) {
        Namespace namespace = requireTeamAdmin(namespaceSlug, userId, false);
        return entries.listTeamRepositories(namespace.getId()).stream().map(this::response).toList();
    }

    public PageResponse<GithubSyncSourceSummaryResponse> resolvedSources(
            String namespaceSlug, String userId, int page, int size) {
        Namespace namespace = requireTeamAdmin(namespaceSlug, userId, false);
        return management.sources(namespace.getId(), page, size);
    }

    public PageResponse<GithubSyncRunSummaryResponse> runs(
            String namespaceSlug, String userId, int page, int size) {
        Namespace namespace = requireTeamAdmin(namespaceSlug, userId, false);
        return management.runs(namespace.getId(), page, size);
    }

    public PageResponse<GithubSyncSkillPathResponse> skillPaths(
            String namespaceSlug, String userId, long sourceId, int page, int size) {
        Namespace namespace = requireTeamAdmin(namespaceSlug, userId, false);
        return management.skillPaths(namespace.getId(), sourceId, page, size);
    }

    @Transactional
    public GithubDiscoveryEntryResponse create(String namespaceSlug, GithubTeamRepositoryRequest request,
                                               String userId, AuditRequestContext context) {
        Namespace namespace = requireTeamAdmin(namespaceSlug, userId, true);
        if (request.ownerLogin() == null || request.repositoryName() == null) {
            throw new DomainBadRequestException("error.githubSync.discoveryEntry.repository");
        }
        String owner = request.ownerLogin().trim();
        String repository = request.repositoryName().trim();
        if (!OWNER.matcher(owner).matches() || !REPOSITORY.matcher(repository).matches()
                || repository.equals(".") || repository.equals("..")) {
            throw new DomainBadRequestException("error.githubSync.discoveryEntry.repository");
        }
        String visibility = visibility(request.visibility() == null ? "NAMESPACE_ONLY" : request.visibility());
        GithubDiscoveryEntry entry;
        try {
            entry = entries.createTeamRepository(namespace.getId(), owner, repository, visibility);
        } catch (DataIntegrityViolationException exception) {
            throw new DomainBadRequestException("error.githubSync.discoveryEntry.duplicate");
        }
        audit("CREATE_GITHUB_TEAM_SOURCE", entry, userId, context);
        return response(entry);
    }

    @Transactional
    public GithubDiscoveryEntryResponse setEnabled(String namespaceSlug, UUID entryId, boolean enabled,
                                                   String userId, AuditRequestContext context) {
        Namespace namespace = requireTeamAdmin(namespaceSlug, userId, true);
        GithubDiscoveryEntry previous = requireEntry(namespace.getId(), entryId);
        if (previous.enabled() == enabled) {
            return response(previous);
        }
        GithubDiscoveryEntry updated = entries.setTeamRepositoryEnabled(namespace.getId(), entryId, enabled);
        audit("SET_GITHUB_TEAM_SOURCE_ENABLED", updated, userId, context);
        return response(updated);
    }

    @Transactional
    public GithubDiscoveryEntryResponse setVisibility(String namespaceSlug, UUID entryId,
                                                      String requestedVisibility, String userId,
                                                      AuditRequestContext context) {
        Namespace namespace = requireTeamAdmin(namespaceSlug, userId, true);
        GithubDiscoveryEntry previous = requireEntry(namespace.getId(), entryId);
        String visibility = visibility(requestedVisibility);
        if (visibility.equals(previous.sourceVisibility())) {
            return response(previous);
        }
        GithubDiscoveryEntry updated = entries.setTeamRepositoryVisibility(namespace.getId(), entryId, visibility);
        audit("SET_GITHUB_TEAM_SOURCE_VISIBILITY", updated, userId, context);
        return response(updated);
    }

    Namespace requireTeamAdmin(String slug, String userId, boolean write) {
        Namespace namespace = namespaces.findBySlug(slug)
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
        return namespace;
    }

    private GithubDiscoveryEntry requireEntry(long namespaceId, UUID entryId) {
        return entries.findTeamRepository(namespaceId, entryId)
                .orElseThrow(() -> new DomainNotFoundException("error.githubSync.discoveryEntry.notFound"));
    }

    private String visibility(String value) {
        if (value == null || !VISIBILITIES.contains(value)) {
            throw new DomainBadRequestException("error.githubSync.teamSource.visibility");
        }
        return value;
    }

    private GithubDiscoveryEntryResponse response(GithubDiscoveryEntry entry) {
        return new GithubDiscoveryEntryResponse(entry.id(), entry.entryType(), entry.title(),
                entry.ownerLogin(), entry.repositoryName(), entry.repositoryId(),
                entry.sourceVisibility(), List.of(), entry.ruleVersion(), entry.enabled(),
                entry.targetIntervalSeconds() / 3600, entry.nextDueAt(), entry.updatedAt());
    }

    private void audit(String action, GithubDiscoveryEntry entry,
                       String actorId, AuditRequestContext context) {
        auditLogService.record(actorId, action, "GITHUB_DISCOVERY_ENTRY", null,
                requestIdAccessor.current(), context.clientIp(), context.userAgent(),
                AuditDetail.builder().put("entryId", entry.id()).put("namespaceId", entry.namespaceId())
                        .put("repositoryId", entry.repositoryId()).put("visibility", entry.sourceVisibility())
                        .put("enabled", entry.enabled()).build());
    }
}
