package com.iflytek.skillhub.domain.namespace;

import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.domain.shared.exception.DomainConflictException;
import com.iflytek.skillhub.domain.user.UserAccountRepository;
import com.iflytek.skillhub.domain.user.UserStatus;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class NamespaceInvitationService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final NamespaceRepository namespaces;
    private final NamespaceInvitationRepository invitations;
    private final NamespaceMemberRepository members;
    private final UserAccountRepository users;
    private final AuditLogService audit;
    private final Clock clock;
    private final NamespaceInvitationHistoryRepository histories;

    public NamespaceInvitationService(NamespaceRepository namespaces, NamespaceInvitationRepository invitations,
                                      NamespaceMemberRepository members, UserAccountRepository users,
                                      AuditLogService audit, Clock clock, NamespaceInvitationHistoryRepository histories) {
        this.namespaces = namespaces;
        this.invitations = invitations;
        this.members = members;
        this.users = users;
        this.audit = audit;
        this.clock = clock;
        this.histories = histories;
    }

    public record Created(String token, Instant expiresAt, boolean replaced) {}
    public enum PreviewOutcome { CONFIRM_REQUIRED, ALREADY_MEMBER }
    public enum AcceptOutcome { JOINED, ALREADY_MEMBER }
    public record Preview(String namespaceSlug, String namespaceDisplayName, String namespaceAvatarUrl,
                          PreviewOutcome outcome, Instant expiresAt) {}
    public record Accepted(String namespaceSlug, AcceptOutcome outcome) {}

    @Transactional
    public Created create(String slug, String actorId) {
        return create(slug, actorId, false);
    }

    @Transactional
    public Created create(String slug, String actorId, boolean replace) {
        requireActiveLockedUser(actorId);
        Namespace namespace = lock(slug);
        requireManager(namespace, actorId);
        requireActiveTeam(namespace);
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        NamespaceInvitation invitation = invitations.findByNamespaceId(namespace.getId()).orElse(null);
        boolean replaced = invitation != null && invitation.getConsumedAt() == null
                && invitation.getExpiresAt().isAfter(now);
        if (replaced && !replace) {
            throw new DomainConflictException("error.namespace.invite.replaceConfirmationRequired");
        }
        if (invitation != null) {
            NamespaceInvitationHistory previous = history(invitation);
            previous.end("REPLACED", now);
            histories.save(previous);
        }
        NamespaceInvitationHistory history = histories.save(new NamespaceInvitationHistory(namespace.getId(), actorId,
                users.findById(actorId).orElseThrow(this::invalid).getDisplayName(), now, now.plusSeconds(86400)));
        if (invitation == null) {
            invitation = new NamespaceInvitation(namespace.getId(), hash(token), actorId, now, now.plusSeconds(86400));
        } else {
            invitation.replace(hash(token), actorId, now);
        }
        invitation.setHistoryId(history.getId());
        invitations.save(invitation);
        record(actorId, replaced ? "NAMESPACE_INVITE_REPLACED" : "NAMESPACE_INVITE_CREATED", namespace.getId(), history.getId());
        return new Created(token, invitation.getExpiresAt(), replaced);
    }

    @Transactional
    public void revoke(String slug, String actorId) {
        requireActiveLockedUser(actorId);
        Namespace namespace = lock(slug);
        requireManager(namespace, actorId);
        requireActiveTeam(namespace);
        NamespaceInvitation invitation = invitations.findByNamespaceId(namespace.getId()).orElseThrow(this::invalid);
        NamespaceInvitationHistory history = history(invitation);
        history.end("REVOKED", clock.instant());
        histories.save(history);
        invitations.deleteByNamespaceId(namespace.getId());
        record(actorId, "NAMESPACE_INVITE_REVOKED", namespace.getId(), history.getId());
    }

    @Transactional(readOnly = true)
    public boolean hasActiveInvitation(String slug, String actorId) {
        Namespace namespace = namespaces.findBySlug(slug)
                .orElseThrow(() -> new DomainNotFoundException("error.namespace.invitation.invalid"));
        requireManager(namespace, actorId);
        return invitations.findByNamespaceId(namespace.getId())
                .filter(invite -> invite.getConsumedAt() == null && invite.getExpiresAt().isAfter(clock.instant()))
                .isPresent();
    }

    @Transactional(readOnly = true)
    public Preview preview(String token, String userId) {
        NamespaceInvitation invitation = current(token);
        Namespace namespace = namespaces.findById(invitation.getNamespaceId()).orElseThrow(this::invalid);
        boolean member = members.findByNamespaceIdAndUserId(namespace.getId(), userId).isPresent();
        if (!member) {
            requireActiveUser(userId);
            requireValid(namespace, invitation);
        }
        return new Preview(namespace.getSlug(), namespace.getDisplayName(), namespace.getAvatarUrl(),
                member ? PreviewOutcome.ALREADY_MEMBER : PreviewOutcome.CONFIRM_REQUIRED, invitation.getExpiresAt());
    }

    @Transactional
    public Accepted accept(String token, String userId) {
        // Membership writers and account merge lock users before the Namespace root.
        // The FK check on namespace_member would otherwise invert that order.
        requireActiveLockedUser(userId);
        Long namespaceId = locateNamespaceId(token);
        Namespace namespace = namespaces.findLockedById(namespaceId).orElseThrow(this::invalid);
        NamespaceInvitation invitation = invitations.findByNamespaceId(namespace.getId()).orElseThrow(this::invalid);
        if (!MessageDigest.isEqual(invitation.getTokenHash().getBytes(StandardCharsets.US_ASCII),
                hash(token).getBytes(StandardCharsets.US_ASCII))) {
            throw invalid();
        }
        if (members.findByNamespaceIdAndUserId(namespace.getId(), userId).isPresent()) {
            return new Accepted(namespace.getSlug(), AcceptOutcome.ALREADY_MEMBER);
        }
        requireActiveUser(userId);
        requireValid(namespace, invitation);
        members.save(new NamespaceMember(namespace.getId(), userId, NamespaceRole.MEMBER));
        Instant acceptedAt = clock.instant();
        invitation.consume(userId, acceptedAt);
        NamespaceInvitationHistory history = history(invitation);
        history.consume(userId, users.findById(userId).orElseThrow(this::invalid).getDisplayName(), acceptedAt);
        histories.save(history);
        invitations.save(invitation);
        record(userId, "NAMESPACE_INVITE_ACCEPTED", namespace.getId(), history.getId());
        return new Accepted(namespace.getSlug(), AcceptOutcome.JOINED);
    }

    @Transactional(readOnly = true)
    public Page<NamespaceInvitationHistory> history(String slug, String actorId, int page, int size) {
        if (page < 0 || size < 1 || size > 50) {
            throw new DomainBadRequestException("error.pagination.invalid", 50);
        }
        Namespace namespace = namespaces.findBySlug(slug).orElseThrow(this::invalid);
        requireManager(namespace, actorId);
        return histories.findByNamespaceId(namespace.getId(), PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "issuedAt", "id")));
    }

    public Instant currentTime() { return clock.instant(); }

    private NamespaceInvitationHistory history(NamespaceInvitation invitation) {
        return histories.findById(invitation.getHistoryId()).orElseThrow(() ->
                new IllegalStateException("Current invitation history is missing"));
    }

    private Namespace lock(String slug) {
        Long namespaceId = namespaces.findIdBySlug(slug).orElseThrow(this::invalid);
        return namespaces.findLockedById(namespaceId).orElseThrow(this::invalid);
    }

    private NamespaceInvitation current(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        return invitations.findByTokenHash(hash(token)).orElseThrow(this::invalid);
    }

    private Long locateNamespaceId(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        return invitations.findNamespaceIdByTokenHash(hash(token)).orElseThrow(this::invalid);
    }

    private void requireManager(Namespace namespace, String userId) {
        NamespaceRole role = members.findByNamespaceIdAndUserId(namespace.getId(), userId)
                .map(NamespaceMember::getRole).orElse(null);
        if (role != NamespaceRole.OWNER && role != NamespaceRole.ADMIN) throw invalid();
        requireActiveUser(userId);
    }

    private void requireActiveUser(String userId) {
        if (users.findById(userId).filter(user -> user.getStatus() == UserStatus.ACTIVE).isEmpty()) throw invalid();
    }

    private void requireActiveLockedUser(String userId) {
        if (users.findLockedById(userId).filter(user -> user.getStatus() == UserStatus.ACTIVE).isEmpty()) throw invalid();
    }

    private void requireActiveTeam(Namespace namespace) {
        if (namespace.getStatus() != NamespaceStatus.ACTIVE || namespace.getType() != NamespaceType.TEAM) throw invalid();
    }

    private void requireValid(Namespace namespace, NamespaceInvitation invitation) {
        requireActiveTeam(namespace);
        if (invitation.getConsumedAt() != null || !invitation.getExpiresAt().isAfter(clock.instant())) throw invalid();
    }

    private DomainNotFoundException invalid() { return new DomainNotFoundException("error.namespace.invitation.invalid"); }

    private String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void record(String actorId, String action, Long namespaceId, String invitationId) {
        audit.record(actorId, action, "NAMESPACE", namespaceId, null, null, null, "{\"invitationId\":\"" + invitationId + "\"}");
    }
}
