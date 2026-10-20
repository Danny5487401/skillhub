package com.iflytek.skillhub.githubsync;

public interface GithubSourceOriginRepository {
    boolean existsBySourceIdAndOriginTypeAndOriginKey(
            Long sourceId, GithubSourceOriginType originType, String originKey);
    GithubSourceOrigin save(GithubSourceOrigin origin);
}
