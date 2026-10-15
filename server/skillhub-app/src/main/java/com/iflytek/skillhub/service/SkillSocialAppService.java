package com.iflytek.skillhub.service;

import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.skill.VisibilityChecker;
import com.iflytek.skillhub.domain.social.SkillRatingService;
import com.iflytek.skillhub.domain.social.SkillStarService;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Object-authorized entry points for skill stars and ratings. */
@Service
public class SkillSocialAppService {
    private final SkillRepository skills;
    private final VisibilityChecker visibility;
    private final SkillStarService stars;
    private final SkillRatingService ratings;

    public SkillSocialAppService(SkillRepository skills, VisibilityChecker visibility,
                                 SkillStarService stars, SkillRatingService ratings) {
        this.skills = skills;
        this.visibility = visibility;
        this.stars = stars;
        this.ratings = ratings;
    }

    public void star(Long skillId, String userId, Map<Long, NamespaceRole> namespaceRoles, Set<String> platformRoles) {
        requireAccess(skillId, userId, namespaceRoles, platformRoles);
        stars.star(skillId, userId);
    }

    public boolean isStarred(Long skillId, String userId, Map<Long, NamespaceRole> namespaceRoles, Set<String> platformRoles) {
        requireAccess(skillId, userId, namespaceRoles, platformRoles);
        return userId != null && stars.isStarred(skillId, userId);
    }

    public void rate(Long skillId, String userId, short score, Map<Long, NamespaceRole> namespaceRoles, Set<String> platformRoles) {
        requireAccess(skillId, userId, namespaceRoles, platformRoles);
        ratings.rate(skillId, userId, score);
    }

    public Optional<Short> getUserRating(Long skillId, String userId, Map<Long, NamespaceRole> namespaceRoles, Set<String> platformRoles) {
        requireAccess(skillId, userId, namespaceRoles, platformRoles);
        return userId != null ? ratings.getUserRating(skillId, userId) : Optional.empty();
    }

    private void requireAccess(Long skillId, String userId, Map<Long, NamespaceRole> namespaceRoles, Set<String> platformRoles) {
        Skill skill = skills.findById(skillId)
                .orElseThrow(() -> new DomainNotFoundException("skill.not_found", skillId));
        if (!visibility.canAccess(skill, userId, namespaceRoles, platformRoles)) {
            throw new DomainForbiddenException("error.skill.access.denied", skillId);
        }
    }
}
