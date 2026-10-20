package com.iflytek.skillhub.githubsync;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Candidate root rules; files inside a parent Skill package are unaffected. */
public final class GithubSkillPathPolicy {
    private static final Set<String> DEFAULT_IGNORED = Set.of(
            ".git", "node_modules", ".venv", "venv", "dist", "build", "target",
            "__pycache__", "test", "tests");

    private GithubSkillPathPolicy() {}

    public static List<String> normalizePatterns(List<String> requested) {
        if (requested == null || requested.size() > 64) {
            throw new IllegalArgumentException("Invalid GitHub source ignore patterns");
        }
        Set<String> unique = new HashSet<>();
        for (String pattern : requested) {
            if (pattern == null || pattern.isBlank() || pattern.length() > 1024
                    || !validPath(pattern) || !unique.add(pattern)) {
                throw new IllegalArgumentException("Invalid GitHub source ignore patterns");
            }
        }
        return unique.stream().sorted().toList();
    }

    public static boolean ignored(String skillPath, List<String> additionalPatterns) {
        if (skillPath == null || (!".".equals(skillPath) && !validPath(skillPath))) {
            throw new IllegalArgumentException("Invalid GitHub Skill path");
        }
        if (".".equals(skillPath)) {
            return false;
        }
        for (String segment : skillPath.split("/")) {
            if (DEFAULT_IGNORED.contains(segment)) {
                return true;
            }
        }
        for (String prefix : additionalPatterns) {
            if (skillPath.equals(prefix) || skillPath.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    private static boolean validPath(String path) {
        if (path.startsWith("/") || path.endsWith("/") || path.indexOf('\\') >= 0) {
            return false;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return path.codePoints().noneMatch(Character::isISOControl);
    }
}
