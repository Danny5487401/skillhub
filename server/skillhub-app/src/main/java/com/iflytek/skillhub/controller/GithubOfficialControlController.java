package com.iflytek.skillhub.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.time.Instant;
import com.iflytek.skillhub.githubsync.GithubOfficialLifecycleRepository;
import com.iflytek.skillhub.githubsync.GithubOfficialProtocolException;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@Hidden
@RequestMapping("/internal/github-sync/v1/tasks")
public class GithubOfficialControlController {
    private final GithubOfficialLifecycleRepository official;

    public GithubOfficialControlController(GithubOfficialLifecycleRepository official) {
        this.official = official;
    }

    @PostMapping("/{taskId}/official-control")
    public ResponseEntity<?> control(@PathVariable UUID taskId, @RequestBody ControlRequest request) {
        requireLease(request.lease());
        var control = official.control(taskId, request.lease().generation(), request.lease().token());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                new ControlResponse(control.action(), control.configurationVersion(), control.reason(), control.retryAt()));
    }

    @PostMapping("/{taskId}/official-pause")
    public ResponseEntity<?> pause(@PathVariable UUID taskId, @RequestBody PauseRequest request) {
        requireLease(request.lease());
        if ("DISCOVERY".equals(request.kind()) && request.repositoryProgress() == null) {
            official.pauseDiscovery(taskId, request.lease().generation(), request.lease().token(), request.discoveryCheckpoint());
        } else if ("REPOSITORY_SYNC".equals(request.kind()) && request.discoveryCheckpoint() == null
                && request.repositoryProgress() != null) {
            official.pauseRepository(taskId, request.lease().generation(), request.lease().token(), request.repositoryProgress());
        } else {
            throw new GithubOfficialProtocolException(422, "RESULT_CONFLICT");
        }
        return ResponseEntity.ok(Map.of("status", "PAUSED"));
    }

    @PostMapping("/{taskId}/official-checkpoint-recovery")
    public ResponseEntity<?> recover(@PathVariable UUID taskId, @RequestBody RecoveryRequest request) {
        requireLease(request.lease());
        UUID replacement = official.recover(taskId, request.lease().generation(), request.lease().token(),
                request.reason(), request.rejectedCheckpoint());
        return ResponseEntity.ok(Map.of("status", "REPLAY_SCHEDULED", "replacementTaskId", replacement));
    }

    private void requireLease(GithubSyncInternalController.LeaseProof lease) {
        if (lease == null) {
            throw new IllegalArgumentException("lease is required");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ControlResponse(String action, long configurationVersion, String reason, Instant retryAt) {}

    public record ControlRequest(GithubSyncInternalController.LeaseProof lease) {}
    public record PauseRequest(GithubSyncInternalController.LeaseProof lease, String kind,
                               String discoveryCheckpoint, GithubOfficialLifecycleRepository.RepositoryProgress repositoryProgress) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown official pause field: " + name);
        }
    }
    public record RecoveryRequest(GithubSyncInternalController.LeaseProof lease, String reason, String rejectedCheckpoint) {}
}
