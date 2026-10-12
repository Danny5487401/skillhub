package com.iflytek.skillhub.service;

import com.iflytek.skillhub.domain.audit.AuditDetail;
import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.namespace.NamespaceType;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.shared.exception.DomainConflictException;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillAvatarCandidate;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.skill.SkillStatus;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.skill.VisibilityChecker;
import com.iflytek.skillhub.domain.skill.service.SkillQueryService;
import com.iflytek.skillhub.dto.AvatarReviewSettingsResponse;
import com.iflytek.skillhub.dto.SkillAvatarStatusResponse;
import com.iflytek.skillhub.infra.jpa.SkillAvatarCandidateJpaRepository;
import com.iflytek.skillhub.infra.jpa.SkillJpaRepository;
import com.iflytek.skillhub.observability.RequestIdAccessor;
import com.iflytek.skillhub.storage.ObjectStorageService;
import jakarta.persistence.EntityManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class SkillAvatarAppService {
    private final SkillJpaRepository skills;
    private final NamespaceRepository namespaces;
    private final SkillAvatarCandidateJpaRepository candidates;
    private final ObjectStorageService storage;
    private final SkillAvatarImageProcessor imageProcessor;
    private final AvatarReviewSettingsService settings;
    private final AvatarModerationGateway moderation;
    private final VisibilityChecker visibility;
    private final SkillQueryService skillQueries;
    private final AuditLogService audit;
    private final RequestIdAccessor requestIds;
    private final EntityManager entityManager;

    public SkillAvatarAppService(SkillJpaRepository skills, NamespaceRepository namespaces,
                                 SkillAvatarCandidateJpaRepository candidates, ObjectStorageService storage,
                                 SkillAvatarImageProcessor imageProcessor, AvatarReviewSettingsService settings,
                                 AvatarModerationGateway moderation, VisibilityChecker visibility,
                                 SkillQueryService skillQueries, AuditLogService audit, RequestIdAccessor requestIds,
                                 EntityManager entityManager) {
        this.skills = skills;
        this.namespaces = namespaces;
        this.candidates = candidates;
        this.storage = storage;
        this.imageProcessor = imageProcessor;
        this.settings = settings;
        this.moderation = moderation;
        this.visibility = visibility;
        this.skillQueries = skillQueries;
        this.audit = audit;
        this.requestIds = requestIds;
        this.entityManager = entityManager;
    }

    @Transactional
    public SkillAvatarStatusResponse submit(String namespaceSlug, String slug, String actor, byte[] image,
                                            AuditRequestContext context) {
        Skill skill = lockedSkill(namespaceSlug, slug);
        requireOwner(skill, actor);
        byte[] normalized = imageProcessor.normalize(image);
        AvatarReviewSettingsResponse policy = settings.current();
        boolean privateOnly = skill.getVisibility() == SkillVisibility.PRIVATE;
        boolean machineEnabled = !privateOnly && policy.machineEnabled();
        boolean manualEnabled = !privateOnly && policy.manualEnabled();
        String provider = machineEnabled ? moderation.requireConfiguredProvider() : null;
        String sha256 = sha256(normalized);
        String key = "skill-avatars/" + skill.getId() + "/" + UUID.randomUUID() + ".png";
        storage.putObject(key, new ByteArrayInputStream(normalized), normalized.length, "image/png");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int result) {
                if (result != STATUS_COMMITTED) {
                    storage.deleteObject(key);
                }
            }
        });

        if (skill.getPendingAvatarId() != null) {
            candidates.findById(skill.getPendingAvatarId()).ifPresent(SkillAvatarCandidate::supersede);
        }
        SkillAvatarCandidate candidate = new SkillAvatarCandidate(skill.getId(), actor, key, sha256,
                skill.getVisibility(), machineEnabled, manualEnabled, provider);
        if (machineEnabled) {
            // Only the machine verdict is reusable. Every new audience has its own human decision.
            boolean reusedPass = !candidates.findByContentSha256AndMachineProviderAndMachineEnabledTrueAndManualEnabledAndMachineStatus(
                    sha256, provider, manualEnabled, SkillAvatarCandidate.MachineStatus.PASSED).stream()
                    .findFirst().isEmpty();
            boolean reusedBlock = !candidates.findByContentSha256AndMachineProviderAndMachineEnabledTrueAndManualEnabledAndMachineStatus(
                    sha256, provider, manualEnabled, SkillAvatarCandidate.MachineStatus.BLOCKED).stream()
                    .findFirst().isEmpty();
            if (reusedPass) {
                candidate.machineResult(SkillAvatarCandidate.MachineStatus.PASSED);
            } else if (reusedBlock) {
                candidate.machineResult(SkillAvatarCandidate.MachineStatus.BLOCKED);
            }
        }
        candidate = candidates.saveAndFlush(candidate);
        skill.setPendingAvatarId(candidate.getId());
        if (!machineEnabled && !manualEnabled
                || candidate.getMachineStatus() == SkillAvatarCandidate.MachineStatus.PASSED && !manualEnabled) {
            activate(skill, candidate);
        }
        log(actor, "SKILL_AVATAR_SUBMIT", candidate.getId(), context,
                AuditDetail.builder().put("skillId", skill.getId()).put("audience", candidate.getAudience().name()).build());
        return status(skill, candidate, namespaceSlug);
    }

    /** Runs outside submit's transaction; result application locks the Skill before changing pointers. */
    @Transactional
    public SkillAvatarStatusResponse processMachine(long candidateId, AuditRequestContext context) {
        SkillAvatarCandidate candidate = findCandidate(candidateId);
        if (candidate.getStatus() != SkillAvatarCandidate.Status.PENDING
                || candidate.getMachineStatus() != SkillAvatarCandidate.MachineStatus.PENDING
                && candidate.getMachineStatus() != SkillAvatarCandidate.MachineStatus.ERROR) {
            throw new DomainConflictException("error.skill.avatar.notPending");
        }
        byte[] bytes;
        try (InputStream input = storage.getObject(candidate.getObjectKey())) {
            bytes = input.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read private avatar candidate", failure);
        }
        AvatarModerationProvider.Verdict verdict = moderation.moderate(candidate.getMachineProvider(), bytes);
        Skill skill = skills.findLockedById(candidate.getSkillId()).orElseThrow(() -> notFound());
        entityManager.refresh(candidate);
        if (!Objects.equals(skill.getPendingAvatarId(), candidateId)
                || candidate.getStatus() != SkillAvatarCandidate.Status.PENDING) {
            return status(skill, candidate, namespaceSlug(skill));
        }
        candidate.machineResult(switch (verdict) {
            case PASS -> SkillAvatarCandidate.MachineStatus.PASSED;
            case BLOCK -> SkillAvatarCandidate.MachineStatus.BLOCKED;
            case ERROR -> SkillAvatarCandidate.MachineStatus.ERROR;
        });
        if (verdict == AvatarModerationProvider.Verdict.PASS && !candidate.isManualEnabled()) {
            activate(skill, candidate);
        }
        log(candidate.getSubmittedBy(), "SKILL_AVATAR_MACHINE_RESULT", candidate.getId(), context,
                AuditDetail.of("verdict", verdict.name()));
        return status(skill, candidate, namespaceSlug(skill));
    }

    @Transactional(readOnly = true)
    public SkillAvatarStatusResponse ownerStatus(String namespaceSlug, String slug, String actor) {
        Skill skill = findSkill(namespaceSlug, slug);
        requireOwner(skill, actor);
        return status(skill, candidates.findBySkillIdOrderByCreatedAtDesc(skill.getId()).stream().findFirst().orElse(null),
                namespaceSlug);
    }

    @Transactional(readOnly = true)
    public InputStream readActive(String namespaceSlug, String slug, String actor,
                                  Map<Long, NamespaceRole> namespaceRoles, Set<String> platformRoles) {
        skillQueries.getSkillDetail(namespaceSlug, slug, actor, namespaceRoles == null ? Map.of() : namespaceRoles);
        Skill skill = findSkill(namespaceSlug, slug);
        if (!visibility.canAccess(skill, actor, namespaceRoles, platformRoles)
                || SkillAvatarUrls.current(skill, namespaceSlug) == null) {
            throw notFound();
        }
        SkillAvatarCandidate candidate = findCandidate(skill.getActiveAvatarId());
        if (candidate.getStatus() != SkillAvatarCandidate.Status.ACTIVE || candidate.getObjectKey() == null) {
            throw notFound();
        }
        return storage.getObject(candidate.getObjectKey());
    }

    @Transactional(readOnly = true)
    public InputStream readCandidate(String namespaceSlug, String slug, long candidateId, String actor,
                                     Map<Long, NamespaceRole> namespaceRoles, Set<String> platformRoles) {
        Skill skill = findSkill(namespaceSlug, slug);
        SkillAvatarCandidate candidate = findCandidate(candidateId);
        if (!skill.getId().equals(candidate.getSkillId()) || candidate.getObjectKey() == null
                || !skill.getOwnerId().equals(actor) && !canReview(skill, actor, namespaceRoles, platformRoles)) {
            throw notFound();
        }
        return storage.getObject(candidate.getObjectKey());
    }

    @Transactional
    public SkillAvatarStatusResponse appeal(String namespaceSlug, String slug, long candidateId, String actor,
                                            AuditRequestContext context) {
        Skill skill = lockedSkill(namespaceSlug, slug);
        requireOwner(skill, actor);
        SkillAvatarCandidate candidate = currentPending(skill, candidateId);
        candidate.appeal();
        log(actor, "SKILL_AVATAR_APPEAL", candidateId, context, AuditDetail.of("skillId", skill.getId()));
        return status(skill, candidate, namespaceSlug);
    }

    @Transactional(readOnly = true)
    public void requireCurrentOwnerCandidate(String namespaceSlug, String slug, long candidateId, String actor) {
        Skill skill = findSkill(namespaceSlug, slug);
        requireOwner(skill, actor);
        if (!Objects.equals(skill.getPendingAvatarId(), candidateId)) {
            throw new DomainConflictException("error.skill.avatar.notPending");
        }
    }

    @Transactional
    public SkillAvatarStatusResponse decide(String namespaceSlug, String slug, long candidateId, boolean approved,
                                            String reason, String actor, Map<Long, NamespaceRole> namespaceRoles,
                                            Set<String> platformRoles, AuditRequestContext context) {
        Skill skill = lockedSkill(namespaceSlug, slug);
        requireReviewer(skill, actor, namespaceRoles, platformRoles);
        SkillAvatarCandidate candidate = currentPending(skill, candidateId);
        if (candidate.isMachineEnabled() && candidate.getMachineStatus() != SkillAvatarCandidate.MachineStatus.PASSED
                && candidate.getMachineStatus() != SkillAvatarCandidate.MachineStatus.BLOCKED) {
            throw new DomainConflictException("error.skill.avatar.machineIncomplete");
        }
        if (reason == null || reason.isBlank()) {
            throw new DomainBadRequestException("error.skill.avatar.reasonRequired");
        }
        if (approved) {
            candidate.approve(actor, reason.trim());
            activate(skill, candidate);
        } else {
            candidate.reject(actor, reason.trim());
            skill.setPendingAvatarId(null);
        }
        log(actor, approved ? "SKILL_AVATAR_APPROVE" : "SKILL_AVATAR_REJECT", candidateId, context,
                AuditDetail.builder().put("skillId", skill.getId()).put("reason", reason.trim()).build());
        return status(skill, candidate, namespaceSlug);
    }

    @Transactional
    public void remove(String namespaceSlug, String slug, String actor, boolean byAdmin, String reason,
                       Map<Long, NamespaceRole> namespaceRoles, Set<String> platformRoles,
                       AuditRequestContext context) {
        Skill skill = lockedSkill(namespaceSlug, slug);
        if (byAdmin) {
            requireReviewer(skill, actor, namespaceRoles, platformRoles);
            if (reason == null || reason.isBlank()) {
                throw new DomainBadRequestException("error.skill.avatar.reasonRequired");
            }
        } else {
            requireOwner(skill, actor);
        }
        if (skill.getActiveAvatarId() == null && skill.getPendingAvatarId() == null) {
            throw notFound();
        }
        if (skill.getActiveAvatarId() != null) {
            candidates.findById(skill.getActiveAvatarId()).ifPresent(SkillAvatarCandidate::remove);
        }
        if (skill.getPendingAvatarId() != null) {
            candidates.findById(skill.getPendingAvatarId()).ifPresent(SkillAvatarCandidate::remove);
        }
        skill.clearAvatar();
        skill.setPendingAvatarId(null);
        log(actor, byAdmin ? "SKILL_AVATAR_TAKEDOWN" : "SKILL_AVATAR_DELETE", skill.getId(), context,
                AuditDetail.of("reason", reason == null ? "" : reason.trim()));
    }

    @Transactional(readOnly = true)
    public List<SkillAvatarStatusResponse> reviewQueue(String actor, Map<Long, NamespaceRole> namespaceRoles,
                                                       Set<String> platformRoles) {
        return java.util.stream.Stream.concat(
                    candidates.findByStatusAndReviewStatusOrderByCreatedAtAsc(
                            SkillAvatarCandidate.Status.PENDING, SkillAvatarCandidate.ReviewStatus.PENDING).stream(),
                    candidates.findByStatusAndReviewStatusOrderByCreatedAtAsc(
                            SkillAvatarCandidate.Status.PENDING, SkillAvatarCandidate.ReviewStatus.APPEAL_PENDING).stream())
                .filter(candidate -> candidate.getMachineStatus() != SkillAvatarCandidate.MachineStatus.ERROR)
                .filter(candidate -> candidate.getMachineStatus() != SkillAvatarCandidate.MachineStatus.PENDING)
                .filter(candidate -> ((SkillRepository) skills).findById(candidate.getSkillId())
                        .filter(skill -> canReview(skill, actor, namespaceRoles, platformRoles)).isPresent())
                .map(candidate -> {
                    Skill skill = ((SkillRepository) skills).findById(candidate.getSkillId()).orElseThrow(() -> notFound());
                    return status(skill, candidate, namespaceSlug(skill));
                }).toList();
    }

    private SkillAvatarCandidate currentPending(Skill skill, long candidateId) {
        if (!Objects.equals(skill.getPendingAvatarId(), candidateId)) {
            throw new DomainConflictException("error.skill.avatar.notPending");
        }
        SkillAvatarCandidate candidate = findCandidate(candidateId);
        if (candidate.getStatus() != SkillAvatarCandidate.Status.PENDING) {
            throw new DomainConflictException("error.skill.avatar.notPending");
        }
        return candidate;
    }

    private void activate(Skill skill, SkillAvatarCandidate candidate) {
        if (skill.getActiveAvatarId() != null) {
            candidates.findById(skill.getActiveAvatarId()).ifPresent(SkillAvatarCandidate::supersede);
        }
        candidate.activate();
        skill.activateAvatar(candidate.getId(), candidate.getAudience());
        skill.setPendingAvatarId(null);
    }

    private Skill findSkill(String namespaceSlug, String slug) {
        Namespace namespace = namespaces.findBySlug(namespaceSlug).orElseThrow(() -> notFound());
        return skills.findByNamespaceIdAndSlug(namespace.getId(), slug).stream()
                .filter(skill -> skill.getStatus() == SkillStatus.ACTIVE).findFirst()
                .orElseThrow(() -> notFound());
    }

    private Skill lockedSkill(String namespaceSlug, String slug) {
        Skill skill = findSkill(namespaceSlug, slug);
        return skills.findLockedById(skill.getId()).orElseThrow(() -> notFound());
    }

    private SkillAvatarCandidate findCandidate(Long candidateId) {
        return candidates.findById(candidateId).orElseThrow(() -> notFound());
    }

    private String namespaceSlug(Skill skill) {
        return namespaces.findById(skill.getNamespaceId()).orElseThrow(() -> notFound()).getSlug();
    }

    private void requireOwner(Skill skill, String actor) {
        if (actor == null || !skill.getOwnerId().equals(actor)) {
            throw new DomainForbiddenException("error.skill.avatar.ownerOnly");
        }
    }

    private void requireReviewer(Skill skill, String actor, Map<Long, NamespaceRole> roles,
                                 Set<String> platformRoles) {
        if (actor == null || !canReview(skill, actor, roles, platformRoles)) {
            throw new DomainForbiddenException("error.skill.avatar.reviewerOnly");
        }
    }

    private boolean canReview(Skill skill, String actor, Map<Long, NamespaceRole> roles,
                              Set<String> platformRoles) {
        if (actor == null) {
            return false;
        }
        Namespace namespace = namespaces.findById(skill.getNamespaceId()).orElseThrow(() -> notFound());
        if (namespace.getType() == NamespaceType.GLOBAL) {
            return platformRoles != null && (platformRoles.contains("SKILL_ADMIN") || platformRoles.contains("SUPER_ADMIN"));
        }
        NamespaceRole role = roles == null ? null : roles.get(skill.getNamespaceId());
        return role == NamespaceRole.OWNER || role == NamespaceRole.ADMIN;
    }

    private SkillAvatarStatusResponse status(Skill skill, SkillAvatarCandidate candidate, String namespaceSlug) {
        return new SkillAvatarStatusResponse(SkillAvatarUrls.current(skill, namespaceSlug),
                candidate == null ? null : candidate.getId(),
                candidate == null ? null : candidate.getStatus().name(),
                candidate == null ? null : candidate.getMachineStatus().name(),
                candidate == null ? null : candidate.getReviewStatus().name(),
                candidate == null ? null : candidate.getReviewReason(),
                candidate == null || candidate.getObjectKey() == null ? null
                        : SkillAvatarUrls.candidate(namespaceSlug, skill, candidate.getId()),
                namespaceSlug, skill.getSlug());
    }

    private String sha256(byte[] image) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private void log(String actor, String action, Long targetId, AuditRequestContext context, String detail) {
        audit.record(actor, action, "SKILL_AVATAR", targetId, requestIds.current(),
                context.clientIp(), context.userAgent(), detail);
    }

    private DomainNotFoundException notFound() {
        return new DomainNotFoundException("error.skill.avatar.notFound");
    }
}
