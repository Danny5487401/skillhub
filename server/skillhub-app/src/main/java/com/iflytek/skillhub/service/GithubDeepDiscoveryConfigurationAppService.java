package com.iflytek.skillhub.service;

import com.iflytek.skillhub.domain.audit.AuditDetail;
import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.shared.exception.DomainConflictException;
import com.iflytek.skillhub.dto.GithubDeepDiscoveryConfigurationRequest;
import com.iflytek.skillhub.dto.GithubDeepDiscoveryConfigurationResponse;
import com.iflytek.skillhub.githubsync.GithubDeepDiscoveryConfiguration;
import com.iflytek.skillhub.githubsync.GithubDeepDiscoveryConfigurationRepository;
import com.iflytek.skillhub.observability.RequestIdAccessor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;

@Service
public class GithubDeepDiscoveryConfigurationAppService {
    private final GithubDeepDiscoveryConfigurationRepository configurations;
    private final AuditLogService audits;
    private final RequestIdAccessor requestIds;

    public GithubDeepDiscoveryConfigurationAppService(GithubDeepDiscoveryConfigurationRepository configurations,
            AuditLogService audits, RequestIdAccessor requestIds) {
        this.configurations = configurations;
        this.audits = audits;
        this.requestIds = requestIds;
    }

    public GithubDeepDiscoveryConfigurationResponse get() {
        return response(configurations.get());
    }

    @Transactional
    public GithubDeepDiscoveryConfigurationResponse update(GithubDeepDiscoveryConfigurationRequest request,
            String actorId, AuditRequestContext context) {
        if (request.expectedVersion() == null || request.expectedVersion() < 1
                || request.enabled() == null || request.budgetProfile() == null
                || request.exclusions() == null || request.exclusions().size() > 1000) {
            throw new DomainBadRequestException("error.githubSync.deepDiscovery.configurationInvalid");
        }
        var exclusions = new LinkedHashSet<GithubDeepDiscoveryConfiguration.Exclusion>();
        for (var exclusion : request.exclusions()) {
            if (exclusion == null) {
                throw new DomainBadRequestException("error.githubSync.deepDiscovery.exclusionInvalid");
            }
            try {
                exclusions.add(new GithubDeepDiscoveryConfiguration.Exclusion(exclusion.kind(), exclusion.value()));
            } catch (IllegalArgumentException exception) {
                throw new DomainBadRequestException("error.githubSync.deepDiscovery.exclusionInvalid");
            }
        }
        GithubDeepDiscoveryConfiguration previous = configurations.get();
        if (previous.version() != request.expectedVersion()) {
            throw new DomainConflictException("error.githubSync.deepDiscovery.configurationConflict");
        }
        GithubDeepDiscoveryConfiguration updated = configurations.update(request.expectedVersion(),
                request.enabled(), request.budgetProfile(), List.copyOf(exclusions), actorId)
                .orElseThrow(() -> new DomainConflictException("error.githubSync.deepDiscovery.configurationConflict"));
        audits.record(actorId, "UPDATE_GITHUB_DEEP_DISCOVERY_CONFIGURATION", "GITHUB_DEEP_DISCOVERY_CONFIG",
                null, requestIds.current(), context.clientIp(), context.userAgent(),
                AuditDetail.builder()
                        .put("namespaceId", updated.namespaceId())
                        .put("previousVersion", previous.version())
                        .put("version", updated.version())
                        .put("previousEnabled", previous.enabled())
                        .put("enabled", updated.enabled())
                        .put("previousBudgetProfile", previous.budgetProfile())
                        .put("budgetProfile", updated.budgetProfile())
                        .put("previousExclusions", previous.exclusions())
                        .put("exclusions", updated.exclusions()).build());
        return response(updated);
    }

    private GithubDeepDiscoveryConfigurationResponse response(GithubDeepDiscoveryConfiguration configuration) {
        return new GithubDeepDiscoveryConfigurationResponse(configuration.version(), configuration.enabled(),
                configuration.budgetProfile(), configuration.exclusions(), configuration.strategyVersion(),
                configuration.updatedAt(), configuration.updatedBy());
    }
}
