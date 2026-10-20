package com.iflytek.skillhub.githubsync;

/**
 * Identity of one upstream Skill within a SkillHub namespace. Repository names and commits are
 * deliberately excluded: both can change without changing the source binding.
 */
public record RepositorySkillCoordinate(long namespaceId, long repositoryId, String skillPath) {

    public RepositorySkillCoordinate {
        if (namespaceId <= 0 || repositoryId <= 0) {
            throw new IllegalArgumentException("namespaceId and repositoryId must be positive");
        }
        if (skillPath == null || skillPath.isEmpty() || skillPath.length() > 1024
                || skillPath.startsWith("/") || skillPath.endsWith("/")
                || skillPath.indexOf('\\') >= 0 || skillPath.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("skillPath must be a normalized relative POSIX directory");
        }
        if (!skillPath.equals(".")) {
            for (String segment : skillPath.split("/", -1)) {
                if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                    throw new IllegalArgumentException("skillPath must be a normalized relative POSIX directory");
                }
            }
        }
    }
}
