package com.iflytek.skillhub.githubsync;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

public interface GithubDiscoveryEntryRepository {
    List<GithubDiscoveryEntry> listPlatformEntries();

    List<GithubDiscoveryEntry> listTeamRepositories(long namespaceId);

    Optional<GithubDiscoveryEntry> findTeamRepository(long namespaceId, UUID id);

    Optional<GithubDiscoveryEntry> findPlatformEntry(UUID id);

    GithubDiscoveryEntry createSearchRule(String title, String criteriaJson,
                                          int targetIntervalSeconds);

    GithubDiscoveryEntry updateSearchRule(UUID id, String title, String criteriaJson,
                                          int targetIntervalSeconds, boolean enabled);

    GithubDiscoveryEntry createCuratedOwner(String ownerLogin, int targetIntervalSeconds);

    GithubDiscoveryEntry createCuratedRepository(String ownerLogin, String repositoryName);

    GithubDiscoveryEntry createTeamRepository(long namespaceId, String ownerLogin,
                                              String repositoryName, String visibility);

    GithubDiscoveryEntry setTeamRepositoryEnabled(long namespaceId, UUID id, boolean enabled);

    GithubDiscoveryEntry setTeamRepositoryVisibility(long namespaceId, UUID id, String visibility);

    GithubDiscoveryEntry setEnabled(UUID id, boolean enabled);

    int enqueueDue(Instant now, int maxEntries);
}
