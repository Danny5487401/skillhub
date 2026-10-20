package com.iflytek.skillhub.githubsync;

import org.springframework.stereotype.Component;

import java.util.List;

/** Versioned Java-owned query definitions. Go compiles snapshots, never a second query catalog. */
@Component
public class GithubOfficialDiscoveryCatalog {
    public static final long GENERATION = 3;
    public static final String VERSION = "official-search-v3";
    public static final List<String> REQUIRED_CAPABILITIES = List.of(
            "official-discovery/v1", "official-checkpoint/v1", "official-pause/v1");

    public static final String OWNER_CAPABILITY = "official-owner/v1";
    public static final List<String> OWNER_REQUIRED_CAPABILITIES = List.of(
            "official-discovery/v1", "official-checkpoint/v1", "official-pause/v1", OWNER_CAPABILITY);

    public List<Definition> definitions() {
        return List.of(
                new Definition("metadata-agent-skills", 1, "REPOSITORY_METADATA", "CORE", "INCREMENTAL",
                        new Query("KEYWORD", "agent skills", "NAME_DESCRIPTION_TOPICS")),
                new Definition("topic-agent-skills", 1, "REPOSITORY_METADATA", "CORE", "INCREMENTAL",
                        new Query("TOPIC", "agent-skills", "NAME_DESCRIPTION_TOPICS")),
                new Definition("readme-skill-manifest", 1, "README", "CORE", "INCREMENTAL",
                        new Query("KEYWORD", "SKILL.md", "README")),
                new Definition("metadata-claude-skills", 1, "REPOSITORY_METADATA", "ECOSYSTEM", "INCREMENTAL",
                        new Query("KEYWORD", "claude skills", "NAME_DESCRIPTION_TOPICS")),
                new Definition("metadata-claude-code-skills", 1, "REPOSITORY_METADATA", "ECOSYSTEM", "INCREMENTAL",
                        new Query("KEYWORD", "claude code skills", "NAME_DESCRIPTION_TOPICS")),
                new Definition("topic-claude-skills", 1, "REPOSITORY_METADATA", "ECOSYSTEM", "HISTORY_RECHECK",
                        new Query("TOPIC", "claude-skills", "NAME_DESCRIPTION_TOPICS")),
                new Definition("metadata-codex-skills", 1, "REPOSITORY_METADATA", "ECOSYSTEM", "INCREMENTAL",
                        new Query("KEYWORD", "codex skills", "NAME_DESCRIPTION_TOPICS")),
                new Definition("topic-codex-skills", 1, "REPOSITORY_METADATA", "ECOSYSTEM", "HISTORY_RECHECK",
                        new Query("TOPIC", "codex-skills", "NAME_DESCRIPTION_TOPICS")),
                new Definition("metadata-openclaw-skills", 1, "REPOSITORY_METADATA", "ECOSYSTEM", "INCREMENTAL",
                        new Query("KEYWORD", "openclaw skills", "NAME_DESCRIPTION_TOPICS")),
                new Definition("topic-openclaw-skills", 1, "REPOSITORY_METADATA", "ECOSYSTEM", "HISTORY_RECHECK",
                        new Query("TOPIC", "openclaw-skills", "NAME_DESCRIPTION_TOPICS")),
                new Definition("metadata-gemini-skills", 1, "REPOSITORY_METADATA", "ECOSYSTEM", "HISTORY_RECHECK",
                        new Query("KEYWORD", "gemini skills", "NAME_DESCRIPTION_TOPICS")),
                new Definition("metadata-skill-registry", 1, "REPOSITORY_METADATA", "ECOSYSTEM", "HISTORY_RECHECK",
                        new Query("KEYWORD", "skill registry", "NAME_DESCRIPTION_TOPICS")),
                new Definition("readme-agent-skills", 1, "README", "ECOSYSTEM", "HISTORY_RECHECK",
                        new Query("KEYWORD", "agent skills", "README")),
                new Definition("readme-skill-workflow", 1, "README", "BROAD", "HISTORY_RECHECK",
                        new Query("KEYWORD", "skill workflow", "README")));
    }

    public record Definition(String key, long version, String channel, String tier,
                             String cadence, Query query) {}
    public record Query(String kind, String value, String scope) {}
}
