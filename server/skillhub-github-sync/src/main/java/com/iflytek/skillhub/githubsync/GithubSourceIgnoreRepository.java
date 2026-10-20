package com.iflytek.skillhub.githubsync;

import java.util.List;
import java.util.Optional;

public interface GithubSourceIgnoreRepository {
    Optional<List<String>> find(long namespaceId, long sourceId);
    Optional<List<String>> replace(long namespaceId, long sourceId, List<String> patterns);
    boolean ignoredForSource(long sourceId, String skillPath);
}
