package com.iflytek.skillhub.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.domain.audit.AuditDetail;
import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.dto.GithubCuratedOwnerRequest;
import com.iflytek.skillhub.dto.GithubCuratedRepositoryRequest;
import com.iflytek.skillhub.dto.GithubDiscoveryEntryResponse;
import com.iflytek.skillhub.dto.GithubSearchRuleRequest;
import com.iflytek.skillhub.dto.GithubSearchSelectorRequest;
import com.iflytek.skillhub.githubsync.GithubDiscoveryEntry;
import com.iflytek.skillhub.githubsync.GithubDiscoveryEntryRepository;
import com.iflytek.skillhub.githubsync.GithubSyncSchedulePolicyRepository;
import com.iflytek.skillhub.observability.RequestIdAccessor;
import com.iflytek.skillhub.repository.GithubDiscoveryEntryStatusQueryRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class GithubDiscoveryAdminAppService {
    private static final Pattern OWNER = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$");
    private static final Pattern TOPIC = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9-]*$");
    private static final Pattern REPOSITORY = Pattern.compile("^[A-Za-z0-9_.-]{1,100}$");

    private final GithubDiscoveryEntryRepository entries;
    private final GithubSyncSchedulePolicyRepository schedulePolicies;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;
    private final RequestIdAccessor requestIdAccessor;
    private final GithubDiscoveryEntryStatusQueryRepository entryStatus;

    public GithubDiscoveryAdminAppService(GithubDiscoveryEntryRepository entries,
                                          GithubSyncSchedulePolicyRepository schedulePolicies,
                                          ObjectMapper objectMapper,
                                          AuditLogService auditLogService,
                                          RequestIdAccessor requestIdAccessor,
                                          GithubDiscoveryEntryStatusQueryRepository entryStatus) {
        this.entries = entries;
        this.schedulePolicies = schedulePolicies;
        this.objectMapper = objectMapper;
        this.auditLogService = auditLogService;
        this.requestIdAccessor = requestIdAccessor;
        this.entryStatus = entryStatus;
    }

    public List<GithubDiscoveryEntryResponse> list() {
        var configured = entries.listPlatformEntries();
        if (configured.isEmpty()) return List.of();
        var executions = entryStatus.latestForEntries(configured.getFirst().namespaceId(),
                configured.stream().map(GithubDiscoveryEntry::id).toList());
        return configured.stream().map(entry -> toResponse(entry, executions.get(entry.id()))).toList();
    }

    @Transactional
    public GithubDiscoveryEntryResponse createSearchRule(GithubSearchRuleRequest request,
                                                         String actorId, AuditRequestContext context) {
        String title = normalizeTitle(request.title());
        String criteriaJson = serializeSelectors(request.selectors());
        int interval = intervalSeconds(request.targetHours(), schedulePolicies.getForUpdate().searchRuleHours());
        GithubDiscoveryEntry created;
        try {
            created = entries.createSearchRule(title, criteriaJson, interval);
        } catch (DataIntegrityViolationException exception) {
            throw new DomainBadRequestException("error.githubSync.discoveryEntry.duplicate");
        }
        recordAudit("CREATE_GITHUB_DISCOVERY_ENTRY", created, actorId, context);
        return toResponse(created);
    }

    @Transactional
    public GithubDiscoveryEntryResponse updateSearchRule(UUID id, GithubSearchRuleRequest request,
                                                         String actorId, AuditRequestContext context) {
        GithubDiscoveryEntry existing = requireEntry(id);
        if (!"SEARCH_RULE".equals(existing.entryType())) {
            throw new DomainBadRequestException("error.githubSync.discoveryEntry.type");
        }
        String title = normalizeTitle(request.title());
        String criteriaJson = serializeSelectors(request.selectors());
        int interval = intervalSeconds(request.targetHours(), schedulePolicies.getForUpdate().searchRuleHours());
        GithubDiscoveryEntry updated;
        try {
            updated = entries.updateSearchRule(id, title, criteriaJson, interval, existing.enabled());
        } catch (DataIntegrityViolationException exception) {
            throw new DomainBadRequestException("error.githubSync.discoveryEntry.duplicate");
        }
        recordAudit("UPDATE_GITHUB_DISCOVERY_ENTRY", updated, actorId, context);
        return toResponse(updated);
    }

    @Transactional
    public GithubDiscoveryEntryResponse createCuratedOwner(GithubCuratedOwnerRequest request,
                                                           String actorId, AuditRequestContext context) {
        String ownerLogin = request.ownerLogin().trim();
        if (!OWNER.matcher(ownerLogin).matches()) {
            throw new DomainBadRequestException("error.githubSync.discoveryEntry.owner");
        }
        int interval = intervalSeconds(request.targetHours(), schedulePolicies.getForUpdate().curatedOwnerHours());
        GithubDiscoveryEntry created;
        try {
            created = entries.createCuratedOwner(ownerLogin, interval);
        } catch (DataIntegrityViolationException exception) {
            throw new DomainBadRequestException("error.githubSync.discoveryEntry.duplicate");
        }
        recordAudit("CREATE_GITHUB_DISCOVERY_ENTRY", created, actorId, context);
        return toResponse(created);
    }

    @Transactional
    public GithubDiscoveryEntryResponse createCuratedRepository(GithubCuratedRepositoryRequest request,
                                                                 String actorId, AuditRequestContext context) {
        String ownerLogin = request.ownerLogin().trim();
        String repositoryName = request.repositoryName().trim();
        if (!OWNER.matcher(ownerLogin).matches() || !REPOSITORY.matcher(repositoryName).matches()
                || repositoryName.equals(".") || repositoryName.equals("..")) {
            throw new DomainBadRequestException("error.githubSync.discoveryEntry.repository");
        }
        GithubDiscoveryEntry created;
        try {
            created = entries.createCuratedRepository(ownerLogin, repositoryName);
        } catch (DataIntegrityViolationException exception) {
            throw new DomainBadRequestException("error.githubSync.discoveryEntry.duplicate");
        }
        recordAudit("CREATE_GITHUB_DISCOVERY_ENTRY", created, actorId, context);
        return toResponse(created);
    }

    @Transactional
    public GithubDiscoveryEntryResponse setEnabled(UUID id, boolean enabled,
                                                   String actorId, AuditRequestContext context) {
        GithubDiscoveryEntry existing = requireEntry(id);
        GithubDiscoveryEntry updated = existing.enabled() == enabled
                ? existing : entries.setEnabled(id, enabled);
        if (existing.enabled() != enabled) {
            recordAudit("SET_GITHUB_DISCOVERY_ENTRY_ENABLED", updated, actorId, context);
        }
        return toResponse(updated);
    }

    private GithubDiscoveryEntry requireEntry(UUID id) {
        return entries.findPlatformEntry(id)
                .orElseThrow(() -> new DomainNotFoundException("error.githubSync.discoveryEntry.notFound"));
    }

    String serializeSelectors(List<GithubSearchSelectorRequest> requested) {
        if (requested == null || requested.isEmpty() || requested.size() > 20) {
            throw new DomainBadRequestException("error.githubSync.discoveryEntry.selectors");
        }
        List<GithubSearchSelectorRequest> normalized = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (GithubSearchSelectorRequest selector : requested) {
            if (selector == null || selector.kind() == null || selector.value() == null
                    || selector.priority() == null) {
                throw new DomainBadRequestException("error.githubSync.discoveryEntry.selectors");
            }
            String value = selector.value().trim();
            if (value.isEmpty() || value.codePointCount(0, value.length()) > 100
                    || !Set.of("CORE", "EXTENSION").contains(selector.priority())) {
                throw new DomainBadRequestException("error.githubSync.discoveryEntry.selectors");
            }
            if ("KEYWORD".equals(selector.kind())) {
                boolean valid = value.codePoints().allMatch(codePoint ->
                        Character.isLetterOrDigit(codePoint) || codePoint == ' '
                                || codePoint == '-' || codePoint == '_');
                if (!valid) {
                    throw new DomainBadRequestException("error.githubSync.discoveryEntry.selectors");
                }
            } else if (!"TOPIC".equals(selector.kind()) || !TOPIC.matcher(value).matches()) {
                throw new DomainBadRequestException("error.githubSync.discoveryEntry.selectors");
            }
            String coordinate = selector.kind() + ":" + value.toLowerCase(Locale.ROOT);
            if (!seen.add(coordinate)) {
                throw new DomainBadRequestException("error.githubSync.discoveryEntry.selectors");
            }
            normalized.add(new GithubSearchSelectorRequest(selector.kind(), value, selector.priority()));
        }
        try {
            return objectMapper.writeValueAsString(Map.of("selectors", normalized));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize GitHub search criteria", exception);
        }
    }

    private String normalizeTitle(String title) {
        if (title == null || title.isBlank() || title.trim().codePointCount(0, title.trim().length()) > 128) {
            throw new DomainBadRequestException("error.githubSync.discoveryEntry.title");
        }
        return title.trim();
    }

    private int intervalSeconds(Integer targetHours, int fallbackHours) {
        int hours = targetHours == null ? fallbackHours : targetHours;
        if (hours < 1 || hours > 720) {
            throw new DomainBadRequestException("error.githubSync.discoveryEntry.interval");
        }
        return hours * 3600;
    }

    private GithubDiscoveryEntryResponse toResponse(GithubDiscoveryEntry entry) {
        return toResponse(entry, entryStatus.latest(entry.namespaceId(), entry.id()));
    }

    private GithubDiscoveryEntryResponse toResponse(GithubDiscoveryEntry entry, GithubDiscoveryEntryResponse.Execution execution) {
        List<GithubSearchSelectorRequest> selectors = List.of();
        if (entry.criteriaJson() != null) {
            try {
                JsonNode stored = objectMapper.readTree(entry.criteriaJson()).path("selectors");
                if (!stored.isArray()) {
                    throw new IllegalStateException("GitHub search criteria is malformed");
                }
                List<GithubSearchSelectorRequest> parsed = new ArrayList<>();
                for (JsonNode selector : stored) {
                    parsed.add(new GithubSearchSelectorRequest(selector.path("kind").asText(),
                            selector.path("value").asText(), selector.path("priority").asText()));
                }
                selectors = List.copyOf(parsed);
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Could not parse GitHub search criteria", exception);
            }
        }
        return new GithubDiscoveryEntryResponse(entry.id(), entry.entryType(), entry.title(),
                entry.ownerLogin(), entry.repositoryName(), entry.repositoryId(),
                entry.sourceVisibility(), selectors,
                entry.ruleVersion(), entry.enabled(),
                entry.targetIntervalSeconds() / 3600, entry.nextDueAt(), entry.updatedAt(),
                execution);
    }

    private void recordAudit(String action, GithubDiscoveryEntry entry,
                             String actorId, AuditRequestContext context) {
        auditLogService.record(actorId, action, "GITHUB_DISCOVERY_ENTRY", null,
                requestIdAccessor.current(), context.clientIp(), context.userAgent(),
                AuditDetail.builder().put("entryId", entry.id()).put("entryType", entry.entryType())
                        .put("ruleVersion", entry.ruleVersion()).put("enabled", entry.enabled()).build());
    }
}
