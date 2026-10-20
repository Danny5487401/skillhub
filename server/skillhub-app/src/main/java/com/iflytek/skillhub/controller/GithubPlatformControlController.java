package com.iflytek.skillhub.controller;

import com.iflytek.skillhub.githubsync.GithubOfficialLifecycleRepository;
import com.iflytek.skillhub.githubsync.GithubOfficialProtocolException;
import com.iflytek.skillhub.githubsync.GithubPlatformControlRepository;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Hidden
@RequestMapping("/internal/github-sync/v1/tasks")
public class GithubPlatformControlController {
    private final GithubPlatformControlRepository controls;

    public GithubPlatformControlController(GithubPlatformControlRepository controls) {
        this.controls = controls;
    }

    @PostMapping("/{taskId}/platform-control")
    public ResponseEntity<GithubPlatformControlRepository.Control> control(@PathVariable UUID taskId,
                                                                          @RequestBody ControlRequest body) {
        requireLease(body.lease());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(controls.control(taskId, body.lease().generation(), body.lease().token()));
    }

    @PostMapping("/{taskId}/platform-pause")
    public ResponseEntity<Map<String, String>> pause(@PathVariable UUID taskId, @RequestBody PauseRequest body) {
        requireLease(body.lease());
        controls.pause(taskId, body.lease().generation(), body.lease().token(), body.observedVersion(),
                body.kind(), body.discoveryCheckpoint(), body.repositoryProgress());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("status", "PAUSED"));
    }

    private void requireLease(GithubSyncInternalController.LeaseProof proof) {
        if (proof == null) {
            throw new GithubOfficialProtocolException(422, "LEASE_REQUIRED");
        }
    }

    public record ControlRequest(GithubSyncInternalController.LeaseProof lease) {}

    public record PauseRequest(GithubSyncInternalController.LeaseProof lease, long observedVersion, String kind,
                               String discoveryCheckpoint,
                               GithubOfficialLifecycleRepository.RepositoryProgress repositoryProgress) {}
}
