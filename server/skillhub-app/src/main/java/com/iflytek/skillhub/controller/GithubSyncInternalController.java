package com.iflytek.skillhub.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iflytek.skillhub.config.SkillPublishProperties;
import com.iflytek.skillhub.githubsync.GithubDiscoveryBatch;
import com.iflytek.skillhub.githubsync.GithubOfficialDiscoveryBatch;
import com.iflytek.skillhub.githubsync.GithubOfficialLifecycleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import com.iflytek.skillhub.githubsync.GithubDiscoveryCandidate;
import com.iflytek.skillhub.githubsync.GithubSyncDeferralReason;
import com.iflytek.skillhub.githubsync.GithubSyncLeaseService;
import com.iflytek.skillhub.githubsync.GithubPackageAdmissionResult;
import com.iflytek.skillhub.githubsync.GithubRepositoryPathResult;
import com.iflytek.skillhub.githubsync.GithubRepositoryMetadataResult;
import com.iflytek.skillhub.githubsync.GithubSyncFailureReason;
import com.iflytek.skillhub.githubsync.GithubSyncTaskKind;
import com.iflytek.skillhub.githubsync.GithubSyncTaskLease;
import com.iflytek.skillhub.service.GithubCredentialLeaseAppService;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@Hidden
@RequestMapping("/internal/github-sync/v1/tasks")
public class GithubSyncInternalController {
    private final GithubSyncLeaseService leases;
    private final GithubCredentialLeaseAppService credentialLeases;
    private final ObjectMapper objectMapper;
    private final SkillPublishProperties publishProperties;
    private final GithubOfficialLifecycleRepository official;

    public GithubSyncInternalController(GithubSyncLeaseService leases,
                                        GithubCredentialLeaseAppService credentialLeases,
                                        ObjectMapper objectMapper,
                                        SkillPublishProperties publishProperties) {
        this(leases, credentialLeases, objectMapper, publishProperties, null);
    }

    @Autowired
    public GithubSyncInternalController(GithubSyncLeaseService leases,
                                        GithubCredentialLeaseAppService credentialLeases,
                                        ObjectMapper objectMapper,
                                        SkillPublishProperties publishProperties,
                                        GithubOfficialLifecycleRepository official) {
        this.official = official;
        this.leases = leases;
        this.credentialLeases = credentialLeases;
        this.objectMapper = objectMapper;
        this.publishProperties = publishProperties;
    }

    @PostMapping("/claim")
    public ResponseEntity<?> claim(@RequestBody ClaimRequest request) {
        if (request.protocolMajor() == null) {
            throw new IllegalArgumentException("protocolMajor is required");
        }
        if (request.protocolMajor() != 1) {
            return ResponseEntity.status(426)
                    .body(new ApiError("PROTOCOL_UNSUPPORTED", "Unsupported protocol major"));
        }
        var claimed = request.capabilities() == null || request.capabilities().isEmpty()
                ? credentialLeases.claim(request.kind(), request.workerId())
                : credentialLeases.claim(request.kind(), request.workerId(), request.capabilities());
        return claimed
                .<ResponseEntity<?>>map(task -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore()).body(toResponse(task)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/{taskId}/renew")
    public ResponseEntity<?> renew(@PathVariable UUID taskId, @RequestBody RenewRequest request) {
        if (request.lease() == null) {
            throw new IllegalArgumentException("lease is required");
        }
        return leases.renew(taskId, request.lease().generation(), request.lease().token())
                .<ResponseEntity<?>>map(expiresAt -> ResponseEntity.ok(new RenewResponse(expiresAt)))
                .orElseGet(() -> ResponseEntity.status(409)
                        .body(new ApiError("LEASE_STALE", "Lease is no longer current")));
    }

    @PostMapping("/{taskId}/repository-metadata")
    public ResponseEntity<?> repositoryMetadata(@PathVariable UUID taskId,
                                                @RequestBody RepositoryMetadataRequest request) {
        if (request.lease() == null) {
            throw new IllegalArgumentException("lease is required");
        }
        GithubRepositoryMetadataResult result = leases.updateRepositoryMetadata(taskId,
                request.lease().generation(), request.lease().token(), request.repositoryId(),
                request.owner(), request.name(), request.defaultBranch(), request.ownerId());
        return switch (result) {
            case UPDATED -> ResponseEntity.noContent().build();
            case LEASE_STALE -> staleLease();
            case REPOSITORY_MISMATCH -> ResponseEntity.status(409)
                    .body(new ApiError("REPOSITORY_MISMATCH", "GitHub repository ID differs from task source"));
        };
    }

    @PostMapping("/{taskId}/credential-status")
    public ResponseEntity<?> credentialStatus(@PathVariable UUID taskId,
                                               @RequestBody CredentialStatusRequest request) {
        if (request.lease() == null) {
            throw new IllegalArgumentException("lease is required");
        }
        return credentialLeases.reportStatus(taskId, request.lease().generation(),
                request.lease().token(), request.credentialId(), request.status(), request.retryAt(),
                request.githubQuota() == null ? null : new GithubCredentialLeaseAppService.Quota(
                        request.githubQuota().resource(), request.githubQuota().remaining(),
                        request.githubQuota().resetAt()), request.officialQuotaObservation())
                ? ResponseEntity.noContent().build() : staleLease();
    }

    @PostMapping("/{taskId}/official-quota")
    public ResponseEntity<?> officialQuota(@PathVariable UUID taskId, @RequestBody OfficialQuotaRequest request) {
        if (request.lease() == null) {
            throw new IllegalArgumentException("lease is required");
        }
        return credentialLeases.reportOfficialQuota(taskId, request.lease().generation(), request.lease().token(), request.observation())
                ? ResponseEntity.noContent().build() : staleLease();
    }

    public record OfficialQuotaRequest(LeaseProof lease,
            com.iflytek.skillhub.githubsync.GithubOfficialQuotaRepository.Observation observation) {}

    @PostMapping("/{taskId}/package-admissions")
    public ResponseEntity<?> admitPackage(@PathVariable UUID taskId, @RequestBody PackageAdmissionRequest request) {
        if (request.lease() == null) {
            throw new IllegalArgumentException("lease is required");
        }
        GithubPackageAdmissionResult result = leases.admitPackage(taskId, request.lease().generation(),
                request.lease().token(), request.commitSha(), request.skillPath());
        return switch (result.status()) {
            case ADMITTED -> ResponseEntity.ok(new PackageAdmissionResponse(result.admissionId()));
            case LEASE_STALE -> staleLease();
            case COMMIT_MISMATCH -> ResponseEntity.status(409)
                    .body(new ApiError("COMMIT_MISMATCH", "Task is pinned to a different commit"));
            case BACKPRESSURE_ACTIVE -> ResponseEntity.status(409)
                    .body(new ApiError("BACKPRESSURE_ACTIVE", "Package acquisition is paused"));
            case PATH_IGNORED -> ResponseEntity.status(409)
                    .body(new ApiError("PATH_IGNORED", "Skill path is ignored by source policy"));
        };
    }

    @PostMapping("/{taskId}/discovered-repositories")
    public ResponseEntity<?> reportDiscoveryBatch(@PathVariable UUID taskId,
                                                   @RequestBody DiscoveryBatchRequest request) {
        if (request.lease() == null) {
            throw new IllegalArgumentException("lease is required");
        }
        GithubDiscoveryBatch batch = new GithubDiscoveryBatch(request.previousCheckpoint(),
                request.checkpoint(), request.repositories(),
                request.deferredCount() == null ? 0 : request.deferredCount(), request.deferralReason());
        if (request.officialBatch() != null) {
            official.report(taskId, request.lease().generation(), request.lease().token(), batch, request.officialBatch());
            return ResponseEntity.accepted().build();
        }
        if (batch.repositories().stream().anyMatch(candidate -> candidate.officialProvenance() != null)) {
            return ResponseEntity.status(409).body(new ApiError("RESULT_CONFLICT", "Official provenance requires an official batch"));
        }
        return switch (leases.reportDiscoveryBatch(taskId, request.lease().generation(),
                request.lease().token(), batch)) {
            case ACCEPTED -> ResponseEntity.accepted().build();
            case LEASE_STALE -> ResponseEntity.status(409)
                    .body(new ApiError("LEASE_STALE", "Lease is no longer current"));
            case CHECKPOINT_CONFLICT -> ResponseEntity.status(409)
                    .body(new ApiError("CHECKPOINT_CONFLICT", "Discovery checkpoint has advanced"));
            case RESULT_CONFLICT -> ResponseEntity.status(409)
                    .body(new ApiError("RESULT_CONFLICT", "Discovery batch conflicts with a prior report"));
            case BUDGET_EXCEEDED -> ResponseEntity.unprocessableEntity()
                    .body(new ApiError("CANDIDATE_BUDGET_EXCEEDED", "Discovery candidate budget exceeded"));
        };
    }

    @PostMapping("/{taskId}/preview-results")
    public ResponseEntity<?> completePreview(@PathVariable UUID taskId,
                                              @RequestBody PreviewResultRequest request) {
        if (request.lease() == null || request.selectors() == null
                || request.selectors().isEmpty() || request.selectors().size() > 20) {
            throw new IllegalArgumentException("preview lease and 1 to 20 selector results are required");
        }
        for (PreviewSelectorResult selector : request.selectors()) {
            if (selector == null || selector.kind() == null
                    || !List.of("KEYWORD", "TOPIC").contains(selector.kind())
                    || selector.value() == null || selector.value().isBlank()
                    || selector.priority() == null
                    || !List.of("CORE", "EXTENSION").contains(selector.priority())
                    || selector.totalCount() < 0 || selector.samples() == null
                    || selector.samples().size() > 5) {
                throw new IllegalArgumentException("invalid preview selector result");
            }
            for (GithubDiscoveryCandidate sample : selector.samples()) {
                if (sample == null || sample.repositoryId() <= 0
                        || sample.owner() == null || sample.name() == null) {
                    throw new IllegalArgumentException("invalid preview repository sample");
                }
            }
        }
        String result;
        try {
            result = objectMapper.writeValueAsString(java.util.Map.of("selectors", request.selectors()));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid preview result", exception);
        }
        return leases.completePreview(taskId, request.lease().generation(), request.lease().token(), result)
                ? ResponseEntity.ok(new CompleteResponse("ACQUISITION_COMPLETE")) : staleLease();
    }

    @PostMapping("/{taskId}/complete")
    public ResponseEntity<?> complete(@PathVariable UUID taskId, @RequestBody CompleteRequest request) {
        if (request.lease() == null) {
            throw new IllegalArgumentException("lease is required");
        }
        if ("HEAD_ONLY".equals(request.coverage())) {
            if (request.pathResults() != null || request.incompleteSubtrees() != null
                    || request.pathCheckpoint() != null || request.discoveryCheckpoint() != null
                    || request.retryAt() != null || request.deferralReason() != null
                    || request.githubQuota() != null) {
                return unsupportedCoverage();
            }
            if (leases.completeHeadOnly(taskId, request.lease().generation(),
                    request.lease().token(), request.observedCommitSha())) {
                return ResponseEntity.ok(new CompleteResponse("ACQUISITION_COMPLETE"));
            }
            return staleLease();
        }
        if ("COMPLETE".equals(request.coverage()) || "INCOMPLETE".equals(request.coverage())) {
            if (request.githubQuota() != null) {
                return unsupportedCoverage();
            }
            if (request.discoveryCheckpoint() != null) {
                if (request.observedCommitSha() != null || request.pathResults() != null
                        || request.incompleteSubtrees() != null || request.pathCheckpoint() != null) {
                    return unsupportedCoverage();
                }
                if (official != null && official.isOfficialDiscovery(taskId)) {
                    return ResponseEntity.ok(new CompleteResponse(official.complete(taskId,
                            request.lease().generation(), request.lease().token(), request.discoveryCheckpoint(),
                            request.coverage(), request.retryAt())));
                }
                return switch (leases.completeDiscovery(taskId, request.lease().generation(),
                        request.lease().token(), request.discoveryCheckpoint(),
                        "COMPLETE".equals(request.coverage()), request.retryAt(), request.deferralReason())) {
                    case ACQUISITION_COMPLETE -> ResponseEntity.ok(new CompleteResponse("ACQUISITION_COMPLETE"));
                    case RETRY_WAIT -> ResponseEntity.ok(new CompleteResponse("RETRY_WAIT"));
                    case LEASE_STALE -> staleLease();
                    case CHECKPOINT_CONFLICT -> ResponseEntity.status(409)
                            .body(new ApiError("CHECKPOINT_CONFLICT", "Discovery checkpoint has advanced"));
                };
            }
            return switch (leases.completeRepository(taskId, request.lease().generation(),
                    request.lease().token(), request.observedCommitSha(),
                    "COMPLETE".equals(request.coverage()), request.pathCheckpoint(),
                    request.incompleteSubtrees(), request.pathResults(), request.retryAt(),
                    request.deferralReason())) {
                case ACQUISITION_COMPLETE -> ResponseEntity.ok(new CompleteResponse("ACQUISITION_COMPLETE"));
                case PARTIAL -> ResponseEntity.ok(new CompleteResponse("PARTIAL"));
                case RETRY_WAIT -> ResponseEntity.ok(new CompleteResponse("RETRY_WAIT"));
                case LEASE_STALE -> staleLease();
                case COMMIT_MISMATCH -> ResponseEntity.status(409)
                        .body(new ApiError("COMMIT_MISMATCH", "Task is pinned to a different commit"));
                case CHECKPOINT_CONFLICT -> ResponseEntity.status(409)
                        .body(new ApiError("CHECKPOINT_CONFLICT", "Path checkpoint has advanced"));
                case RESULT_CONFLICT -> ResponseEntity.status(409)
                        .body(new ApiError("RESULT_CONFLICT", "Path results conflict with persisted acquisition"));
                case COVERAGE_CONFLICT -> ResponseEntity.status(409)
                        .body(new ApiError("COVERAGE_CONFLICT", "Repository acquisition remains incomplete"));
            };
        }
        return unsupportedCoverage();
    }

    private ResponseEntity<ApiError> staleLease() {
        return ResponseEntity.status(409)
                .body(new ApiError("LEASE_STALE", "Lease is no longer current"));
    }

    private ResponseEntity<ApiError> unsupportedCoverage() {
        return ResponseEntity.unprocessableEntity()
                .body(new ApiError("COVERAGE_UNSUPPORTED", "This completion coverage is not implemented"));
    }

    @PostMapping("/{taskId}/fail")
    public ResponseEntity<?> fail(@PathVariable UUID taskId, @RequestBody FailRequest request) {
        if (request.lease() == null || request.reason() == null) {
            throw new IllegalArgumentException("lease and reason are required");
        }
        return leases.fail(taskId, request.lease().generation(), request.lease().token(),
                        request.reason(), request.detail(), request.githubQuota() == null ? null
                                : new GithubSyncLeaseService.Quota(
                                        request.githubQuota().resource(),
                                        request.githubQuota().remaining(),
                                        request.githubQuota().resetAt()), request.retryAt())
                .<ResponseEntity<?>>map(retryAt -> ResponseEntity.ok(new FailResponse("RETRY_WAIT", retryAt)))
                .orElseGet(() -> ResponseEntity.status(409)
                        .body(new ApiError("LEASE_STALE", "Lease is no longer current")));
    }

    private ClaimedTaskResponse toResponse(GithubCredentialLeaseAppService.Claimed claimed) {
        GithubSyncLeaseService.LeasedTask leased = claimed.lease();
        GithubSyncTaskLease task = leased.task();
        JsonNode snapshot;
        try {
            snapshot = objectMapper.readTree(task.snapshotJson());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid persisted GitHub sync task snapshot", exception);
        }
        boolean official = snapshot.has("officialDiscovery");
        if (official) {
            snapshot = snapshot.get("officialDiscovery");
        }
        if (task.kind() == GithubSyncTaskKind.DISCOVERY && snapshot instanceof ObjectNode discovery) {
            ObjectNode discoveryCopy = discovery.deepCopy();
            if (task.discoveryCheckpoint() == null) {
                discoveryCopy.remove("checkpoint");
            } else {
                discoveryCopy.put("checkpoint", task.discoveryCheckpoint());
            }
            snapshot = discoveryCopy;
        }
        if (task.kind() == GithubSyncTaskKind.REPOSITORY_SYNC && task.fixedCommitSha() != null
                && snapshot instanceof ObjectNode repository) {
            ObjectNode repositoryCopy = repository.deepCopy();
            repositoryCopy.put("fixedCommitSha", task.fixedCommitSha());
            if (task.pathCheckpoint() != null) {
                repositoryCopy.put("pathCheckpoint", task.pathCheckpoint());
            }
            try {
                repositoryCopy.set("incompleteSubtrees", objectMapper.readTree(task.incompleteSubtreesJson()));
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Invalid persisted incomplete GitHub subtrees", exception);
            }
            var retryPaths = repositoryCopy.putArray("retryPaths");
            task.retryPaths().forEach(retryPaths::add);
            var acceptedPaths = repositoryCopy.putArray("acceptedPaths");
            task.acceptedPaths().forEach(acceptedPaths::add);
            snapshot = repositoryCopy;
        }
        return new ClaimedTaskResponse(
                task.taskId(), task.runId(), task.kind().name(),
                task.mode() == null ? null : task.mode().name(),
                task.namespaceId(), task.sourceId(),
                new LeaseProof(task.leaseGeneration(), leased.token()), task.leaseExpiresAt(),
                task.kind() == GithubSyncTaskKind.DISCOVERY && !official ? snapshot : null,
                task.kind() == GithubSyncTaskKind.REPOSITORY_SYNC ? snapshot : null,
                claimed.credential() == null ? null : new CredentialResponse(
                        claimed.credential().credentialId(), claimed.credential().token()),
                publishProperties.getMaxPackageSize(), publishProperties.getMaxSingleFileSize(),
                publishProperties.getMaxFileCount(), publishProperties.getMaxPackageSize(),
                official ? snapshot : null,
                task.requiredCapabilities().isEmpty() ? null : task.requiredCapabilities(), task.platformControlVersion());
    }

    public record ClaimRequest(String workerId, Integer protocolMajor, GithubSyncTaskKind kind,
                               List<String> capabilities) {
        public ClaimRequest(String workerId, Integer protocolMajor, GithubSyncTaskKind kind) {
            this(workerId, protocolMajor, kind, null);
        }
    }
    public record LeaseProof(long generation, String token) {}
    public record RepositoryMetadataRequest(LeaseProof lease, long repositoryId,
                                            String owner, String name, String defaultBranch, Long ownerId) {
        public RepositoryMetadataRequest(LeaseProof lease, long repositoryId, String owner, String name, String defaultBranch) {
            this(lease, repositoryId, owner, name, defaultBranch, null);
        }
    }
    public record RenewRequest(LeaseProof lease) {}
    public record RenewResponse(Instant leaseExpiresAt) {}
    public record CredentialStatusRequest(LeaseProof lease, UUID credentialId,
                                          String status, Instant retryAt, CredentialQuotaRequest githubQuota,
                                          com.iflytek.skillhub.githubsync.GithubOfficialQuotaRepository.Observation officialQuotaObservation) {
        public CredentialStatusRequest(LeaseProof lease, UUID credentialId, String status, Instant retryAt, CredentialQuotaRequest githubQuota) {
            this(lease, credentialId, status, retryAt, githubQuota, null);
        }
    }
    public record CredentialQuotaRequest(String resource, int remaining, Instant resetAt) {}
    public record PackageAdmissionRequest(LeaseProof lease, String commitSha, String skillPath) {}
    public record PackageAdmissionResponse(UUID admissionId) {}
    public record DiscoveryBatchRequest(LeaseProof lease, List<GithubDiscoveryCandidate> repositories,
                                        String previousCheckpoint, String checkpoint, Integer deferredCount,
                                        GithubSyncDeferralReason deferralReason, GithubOfficialDiscoveryBatch officialBatch) {
        public DiscoveryBatchRequest(LeaseProof lease, List<GithubDiscoveryCandidate> repositories,
                String previousCheckpoint, String checkpoint, Integer deferredCount, GithubSyncDeferralReason deferralReason) {
            this(lease, repositories, previousCheckpoint, checkpoint, deferredCount, deferralReason, null);
        }
    }
    public record PreviewSelectorResult(String kind, String value, String priority, int totalCount,
                                        boolean incompleteResults, boolean limitReached,
                                        List<GithubDiscoveryCandidate> samples) {}
    public record PreviewResultRequest(LeaseProof lease, List<PreviewSelectorResult> selectors) {}
    public record CompleteRequest(LeaseProof lease, String coverage, String observedCommitSha,
                                  List<String> incompleteSubtrees, String pathCheckpoint,
                                  List<GithubRepositoryPathResult> pathResults,
                                  String discoveryCheckpoint, GithubSyncDeferralReason deferralReason,
                                  JsonNode githubQuota, Instant retryAt) {}
    public record CompleteResponse(String status) {}
    public record FailRequest(LeaseProof lease, GithubSyncFailureReason reason, Instant retryAt,
                              CredentialQuotaRequest githubQuota, String detail) {}
    public record FailResponse(String status, Instant retryAt) {}
    public record ApiError(String code, String message) {}
    public record CredentialResponse(UUID credentialId, String token) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ClaimedTaskResponse(
            UUID taskId, UUID runId, String kind, String mode, long namespaceId, Long sourceId,
            LeaseProof lease, Instant leaseExpiresAt, JsonNode discovery, JsonNode repository,
            CredentialResponse credential,
            long maxPackageBytes, long maxSingleFileBytes, int maxFilesPerPackage,
            long maxUploadBytes, JsonNode officialDiscovery, List<String> requiredCapabilities, Long platformControlVersion) {
        public ClaimedTaskResponse(UUID taskId, UUID runId, String kind, String mode, long namespaceId,
                Long sourceId, LeaseProof lease, Instant leaseExpiresAt, JsonNode discovery,
                JsonNode repository, CredentialResponse credential, long maxPackageBytes,
                long maxSingleFileBytes, int maxFilesPerPackage, long maxUploadBytes) {
            this(taskId, runId, kind, mode, namespaceId, sourceId, lease, leaseExpiresAt, discovery,
                    repository, credential, maxPackageBytes, maxSingleFileBytes, maxFilesPerPackage,
                    maxUploadBytes, null, null, null);
        }
    }
}
