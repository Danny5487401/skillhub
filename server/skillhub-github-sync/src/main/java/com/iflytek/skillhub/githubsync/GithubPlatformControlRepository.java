package com.iflytek.skillhub.githubsync;

import java.time.Instant;
import java.util.UUID;

/** Platform-only acquisition control; accepted ingestions are not paused. */
public interface GithubPlatformControlRepository {
    String CAPABILITY = "platform-pause/v1";
    record State(long namespaceId, long version, boolean desiredPaused, String state,
                 long pendingStopCount, long expiredUnconfirmedCount, Instant updatedAt) {}
    record Control(String action, long platformVersion) {}
    State get();
    State update(long expectedVersion, boolean paused, String actor);
    Control control(UUID taskId, long generation, String token);
    void pause(UUID taskId, long generation, String token, long observedVersion,
               String kind, String discoveryCheckpoint,
               GithubOfficialLifecycleRepository.RepositoryProgress progress);
}
