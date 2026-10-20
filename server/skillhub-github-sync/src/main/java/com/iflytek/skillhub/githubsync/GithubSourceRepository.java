package com.iflytek.skillhub.githubsync;

import java.util.Optional;

public interface GithubSourceRepository {
    Optional<GithubSourceBinding> findByNamespaceIdAndRepositoryId(Long namespaceId, Long repositoryId);
    GithubSourceBinding save(GithubSourceBinding source);
}
