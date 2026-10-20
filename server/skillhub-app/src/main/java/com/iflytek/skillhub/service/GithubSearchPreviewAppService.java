package com.iflytek.skillhub.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.dto.GithubSearchRuleRequest;
import com.iflytek.skillhub.githubsync.GithubSearchPreviewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class GithubSearchPreviewAppService {
    private final GithubSearchPreviewRepository previews;
    private final GithubDiscoveryAdminAppService discovery;
    private final ObjectMapper mapper;

    public GithubSearchPreviewAppService(GithubSearchPreviewRepository previews,
                                         GithubDiscoveryAdminAppService discovery, ObjectMapper mapper) {
        this.previews = previews;
        this.discovery = discovery;
        this.mapper = mapper;
    }

    @Transactional
    public Preview queue(GithubSearchRuleRequest request, String actorId) {
        String criteria = discovery.serializeSelectors(request.selectors());
        return get(previews.queue(criteria, actorId, Instant.now()));
    }

    public Preview get(UUID id) {
        GithubSearchPreviewRepository.Preview persisted = previews.find(id)
                .orElseThrow(() -> new DomainNotFoundException("error.githubSync.discoveryEntry.notFound"));
        try {
            JsonNode snapshot = mapper.readTree(persisted.snapshotJson());
            return new Preview(persisted.id(), persisted.status(), persisted.errorCode(), persisted.retryAt(),
                    snapshot.path("criteria"), snapshot.path("searchWindow").path("until").asText(),
                    snapshot.get("previewResult"), persisted.createdAt(), persisted.updatedAt());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid persisted GitHub search preview", exception);
        }
    }

    public record Preview(UUID id, String status, String errorCode, Instant retryAt, JsonNode criteria,
                          String searchedUntil, JsonNode result, Instant createdAt, Instant updatedAt) {}
}
