package com.iflytek.skillhub.githubsync;

import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.UUID;
import java.util.List;

public interface GithubOfficialLifecycleRepository {
    boolean isOfficialDiscovery(UUID taskId);
    void report(UUID taskId, long generation, String token, GithubDiscoveryBatch batch,
                GithubOfficialDiscoveryBatch officialBatch);
    Control control(UUID taskId, long generation, String token);
    void pauseDiscovery(UUID taskId, long generation, String token, String checkpoint);
    void pauseRepository(UUID taskId, long generation, String token, RepositoryProgress progress);
    record RepositoryProgress(String observedCommitSha, String pathCheckpoint,
                              List<String> incompleteSubtrees, List<GithubRepositoryPathResult> pathResults) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown repository pause progress field: " + name);
        }
    }
    UUID recover(UUID taskId, long generation, String token, String reason, String rejectedCheckpoint);
    String complete(UUID taskId, long generation, String token, String checkpoint,
                    String coverage, Instant retryAt);

    record Control(String action, long configurationVersion, String reason, Instant retryAt) {}
}
