package com.iflytek.skillhub.controller;

import com.iflytek.skillhub.repository.GithubSyncWorkerPresenceRepository;
import com.iflytek.skillhub.dto.GithubSyncWorkerHeartbeatRequest;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Hidden
@RequestMapping("/internal/github-sync/v1")
public class GithubSyncWorkerHeartbeatController {
    private final GithubSyncWorkerPresenceRepository presence;

    public GithubSyncWorkerHeartbeatController(GithubSyncWorkerPresenceRepository presence) {
        this.presence = presence;
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<Void> heartbeat(@RequestBody(required = false) GithubSyncWorkerHeartbeatRequest request) {
        if (request == null || request.workerId() == null) {
            presence.heartbeat();
        } else {
            presence.heartbeat(request.workerId(), request.protocolMajor(), request.capabilities());
        }
        return ResponseEntity.noContent().build();
    }

    public ResponseEntity<Void> heartbeat() {
        return heartbeat(null);
    }
}
