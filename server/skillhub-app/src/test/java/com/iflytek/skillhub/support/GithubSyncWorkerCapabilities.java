package com.iflytek.skillhub.support;

import com.iflytek.skillhub.githubsync.GithubPlatformControlRepository;
import java.util.LinkedHashSet;
import java.util.List;

/** Existing worker fixtures opt in to platform checkpoints without changing production capability gates. */
public final class GithubSyncWorkerCapabilities {
    private GithubSyncWorkerCapabilities() {}
    public static List<String> platform(List<String> capabilities) {
        var result = new LinkedHashSet<>(capabilities);
        result.add(GithubPlatformControlRepository.CAPABILITY);
        return List.copyOf(result);
    }
}
