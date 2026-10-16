package com.iflytek.skillhub.service;

import com.iflytek.skillhub.auth.rbac.RbacService;
import com.iflytek.skillhub.domain.audit.AuditDetail;
import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.skill.service.SkillSlugResolutionService;
import com.iflytek.skillhub.domain.skill.service.SkillVisibilityService;
import com.iflytek.skillhub.observability.RequestIdAccessor;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SkillVisibilityAppService {
    private final NamespaceRepository namespaces;
    private final SkillSlugResolutionService resolver;
    private final SkillVisibilityService visibility;
    private final RbacService rbac;
    private final SkillAvatarAppService avatars;
    private final AuditLogService audit;
    private final RequestIdAccessor requestIds;

    public SkillVisibilityAppService(NamespaceRepository namespaces, SkillSlugResolutionService resolver,
            SkillVisibilityService visibility, RbacService rbac, SkillAvatarAppService avatars,
            AuditLogService audit, RequestIdAccessor requestIds) {
        this.namespaces = namespaces; this.resolver = resolver; this.visibility = visibility;
        this.rbac = rbac; this.avatars = avatars; this.audit = audit; this.requestIds = requestIds;
    }

    @Transactional
    public Result change(String namespace, String slug, SkillVisibility target, String actor,
            Map<Long, NamespaceRole> roles, AuditRequestContext context) {
        String cleanNamespace = namespace.startsWith("@") ? namespace.substring(1) : namespace;
        var ns = namespaces.findBySlug(cleanNamespace)
                .orElseThrow(() -> new DomainNotFoundException("namespace.not_found", cleanNamespace));
        var skill = resolver.resolve(ns.getId(), slug, actor, SkillSlugResolutionService.Preference.CURRENT_USER);
        skill = visibility.lockedSkill(skill.getId());
        var previous = skill.getVisibility();
        var task = visibility.change(skill.getId(), target, actor, roles == null ? Map.of() : roles,
                rbac.getUserRoleCodes(actor));
        if (task != null) {
            avatars.attachScopeToReview(skill.getId(), target, task, actor, context);
        }
        audit.record(actor, task == null ? "SKILL_VISIBILITY_CHANGED" : "SKILL_VISIBILITY_SUBMITTED", "SKILL",
                skill.getId(), requestIds.current(), context.clientIp(), context.userAgent(),
                AuditDetail.builder().put("previousVisibility", previous.name()).put("targetVisibility", target.name())
                        .put("reviewTaskId", task == null ? null : task.getId()).build());
        return new Result(task == null ? target : previous, task == null ? null : target,
                task == null ? null : task.getId());
    }

    @Transactional(readOnly = true)
    public Result current(String namespace, String slug, String actor, Map<Long, NamespaceRole> roles) {
        String cleanNamespace = namespace.startsWith("@") ? namespace.substring(1) : namespace;
        var ns = namespaces.findBySlug(cleanNamespace)
                .orElseThrow(() -> new DomainNotFoundException("namespace.not_found", cleanNamespace));
        var skill = resolver.resolve(ns.getId(), slug, actor, SkillSlugResolutionService.Preference.CURRENT_USER);
        var task = visibility.pending(skill.getId(), actor, roles == null ? Map.of() : roles, rbac.getUserRoleCodes(actor));
        return new Result(skill.getVisibility(), task == null ? null : task.getRequestedVisibility(), task == null ? null : task.getId());
    }

    public record Result(SkillVisibility visibility, SkillVisibility requestedVisibility, Long reviewTaskId) {}
}
