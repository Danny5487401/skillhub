package com.iflytek.skillhub.domain.namespace;

import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.domain.user.UserAccount;
import com.iflytek.skillhub.domain.user.UserAccountRepository;
import com.iflytek.skillhub.domain.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NamespaceInvitationServiceTest {
    private final NamespaceRepository namespaces = mock(NamespaceRepository.class);
    private final NamespaceInvitationRepository invitations = mock(NamespaceInvitationRepository.class);
    private final NamespaceMemberRepository members = mock(NamespaceMemberRepository.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final NamespaceInvitationHistoryRepository histories = mock(NamespaceInvitationHistoryRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), ZoneOffset.UTC);
    private final NamespaceInvitationService service = new NamespaceInvitationService(namespaces, invitations, members, users, audit, clock, histories);

    @BeforeEach
    void setup() {
        lenient().when(histories.save(any())).thenAnswer(call -> call.getArgument(0));
        lenient().when(histories.findById(org.mockito.ArgumentMatchers.nullable(String.class))).thenAnswer(call ->
                Optional.of(new NamespaceInvitationHistory(7L, "owner", "Owner", clock.instant(), clock.instant().plusSeconds(86400))));
        Namespace namespace = new Namespace("team", "Team", "owner");
        // Entity ID is database-generated; mock the locked lookup with the same instance.
        try {
            var id = Namespace.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(namespace, 7L);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        when(namespaces.findBySlug("team")).thenReturn(Optional.of(namespace));
        when(namespaces.findIdBySlug("team")).thenReturn(Optional.of(7L));
        when(namespaces.findLockedById(7L)).thenReturn(Optional.of(namespace));
        when(namespaces.findById(7L)).thenReturn(Optional.of(namespace));
        when(members.findByNamespaceIdAndUserId(7L, "owner"))
                .thenReturn(Optional.of(new NamespaceMember(7L, "owner", NamespaceRole.OWNER)));
        UserAccount owner = mock(UserAccount.class);
        when(owner.getStatus()).thenReturn(com.iflytek.skillhub.domain.user.UserStatus.ACTIVE);
        when(users.findById("owner")).thenReturn(Optional.of(owner));
        when(users.findById("guest")).thenReturn(Optional.of(owner));
        lenient().when(users.findLockedById("owner")).thenReturn(Optional.of(owner));
        lenient().when(users.findLockedById("guest")).thenReturn(Optional.of(owner));
    }

    @Test
    void createsSingleUseTokenWithoutPersistingRawValue() {
        NamespaceInvitationService.Created created = service.create("team", "owner");
        ArgumentCaptor<NamespaceInvitation> saved = ArgumentCaptor.forClass(NamespaceInvitation.class);
        verify(invitations).save(saved.capture());
        assertThat(created.token()).matches("[A-Za-z0-9_-]{43}");
        assertThat(saved.getValue().getTokenHash()).matches("[a-f0-9]{64}").doesNotContain(created.token());
        assertThat(created.expiresAt()).isEqualTo(Instant.parse("2026-10-13T00:00:00Z"));
        assertThat(created.replaced()).isFalse();
    }

    @Test
    void namespaceAdminCanCreateInvitation() {
        Optional<UserAccount> active = users.findById("owner");
        when(members.findByNamespaceIdAndUserId(7L, "admin"))
                .thenReturn(Optional.of(new NamespaceMember(7L, "admin", NamespaceRole.ADMIN)));
        when(users.findLockedById("admin")).thenReturn(active);
        when(users.findById("admin")).thenReturn(active);

        assertThat(service.create("team", "admin").token()).matches("[A-Za-z0-9_-]{43}");
        verify(invitations).save(any());
    }

    @Test
    void namespaceMemberCannotCreateInvitation() {
        Optional<UserAccount> active = users.findById("owner");
        when(members.findByNamespaceIdAndUserId(7L, "guest"))
                .thenReturn(Optional.of(new NamespaceMember(7L, "guest", NamespaceRole.MEMBER)));
        when(users.findLockedById("guest")).thenReturn(active);

        assertThatThrownBy(() -> service.create("team", "guest"))
                .isInstanceOf(DomainNotFoundException.class);
        verify(invitations, never()).save(any());
    }

    @Test
    void ownerCannotCreateInvitationForGlobalFrozenOrArchivedNamespace() {
        Namespace namespace = namespaces.findById(7L).orElseThrow();
        namespace.setType(NamespaceType.GLOBAL);
        assertThatThrownBy(() -> service.create("team", "owner"))
                .isInstanceOf(DomainNotFoundException.class);

        namespace.setType(NamespaceType.TEAM);
        for (NamespaceStatus status : new NamespaceStatus[] {NamespaceStatus.FROZEN, NamespaceStatus.ARCHIVED}) {
            namespace.setStatus(status);
            assertThatThrownBy(() -> service.create("team", "owner"))
                    .as("Namespace status %s", status)
                    .isInstanceOf(DomainNotFoundException.class);
        }
        verify(invitations, never()).save(any());
    }

    @Test
    void platformAdminWithoutNamespaceMembershipCannotCreateInvitation() {
        Optional<UserAccount> active = users.findById("owner");
        when(users.findLockedById("platform-admin")).thenReturn(active);

        assertThatThrownBy(() -> service.create("team", "platform-admin"))
                .isInstanceOf(DomainNotFoundException.class);
        verify(invitations, never()).save(any());
    }

    @Test
    void ownerCanCreateInvitationForPersonalNamespaceRepresentedAsTeam() {
        Namespace personal = new Namespace("personal", "Personal", "owner");
        try {
            var id = Namespace.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(personal, 8L);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        assertThat(personal.getType()).isEqualTo(NamespaceType.TEAM);
        when(namespaces.findIdBySlug("personal")).thenReturn(Optional.of(8L));
        when(namespaces.findLockedById(8L)).thenReturn(Optional.of(personal));
        when(members.findByNamespaceIdAndUserId(8L, "owner"))
                .thenReturn(Optional.of(new NamespaceMember(8L, "owner", NamespaceRole.OWNER)));

        assertThat(service.create("personal", "owner").token()).matches("[A-Za-z0-9_-]{43}");
        verify(invitations).save(any());
    }

    @Test
    void revokedTokenCannotBePreviewed() {
        assertThatThrownBy(() -> service.preview("A".repeat(43), "guest"))
                .isInstanceOf(DomainNotFoundException.class)
                .hasMessage("error.namespace.invitation.invalid");
        verify(invitations).findByTokenHash(any());
    }

    @Test
    void acceptCreatesMemberAndConsumesExactlyTheCurrentInvitation() {
        NamespaceInvitationService.Created created = service.create("team", "owner");
        ArgumentCaptor<NamespaceInvitation> saved = ArgumentCaptor.forClass(NamespaceInvitation.class);
        verify(invitations).save(saved.capture());
        NamespaceInvitation invitation = saved.getValue();
        when(invitations.findByTokenHash(invitation.getTokenHash())).thenReturn(Optional.of(invitation));
        when(invitations.findNamespaceIdByTokenHash(invitation.getTokenHash())).thenReturn(Optional.of(7L));
        when(invitations.findByNamespaceId(7L)).thenReturn(Optional.of(invitation));
        namespaces.findById(7L).orElseThrow().setAvatarUrl("https://example.com/team.png");

        NamespaceInvitationService.Preview preview = service.preview(created.token(), "guest");
        assertThat(preview.namespaceSlug()).isEqualTo("team");
        assertThat(preview.namespaceDisplayName()).isEqualTo("Team");
        assertThat(preview.namespaceAvatarUrl()).isEqualTo("https://example.com/team.png");
        assertThat(preview.outcome()).isEqualTo(NamespaceInvitationService.PreviewOutcome.CONFIRM_REQUIRED);
        assertThat(preview.expiresAt()).isEqualTo(created.expiresAt());
        assertThat(service.accept(created.token(), "guest"))
                .isEqualTo(new NamespaceInvitationService.Accepted("team", NamespaceInvitationService.AcceptOutcome.JOINED));

        verify(members).save(argThat(member -> member.getRole() == NamespaceRole.MEMBER
                && member.getNamespaceId().equals(7L) && member.getUserId().equals("guest")));
        assertThat(invitation.getConsumedBy()).isEqualTo("guest");
        assertThat(invitation.getConsumedAt()).isEqualTo(clock.instant());
        Optional<UserAccount> guest = users.findById("guest");
        when(users.findLockedById("another")).thenReturn(guest);
        when(users.findById("another")).thenReturn(guest);
        assertThatThrownBy(() -> service.accept(created.token(), "another"))
                .isInstanceOf(DomainNotFoundException.class);
    }

    @Test
    void nonActiveUserCannotPreviewOrAcceptAndDoesNotConsumeInvitation() {
        NamespaceInvitationService.Created created = service.create("team", "owner");
        ArgumentCaptor<NamespaceInvitation> saved = ArgumentCaptor.forClass(NamespaceInvitation.class);
        verify(invitations).save(saved.capture());
        NamespaceInvitation invitation = saved.getValue();
        when(invitations.findByTokenHash(invitation.getTokenHash())).thenReturn(Optional.of(invitation));
        when(invitations.findNamespaceIdByTokenHash(invitation.getTokenHash())).thenReturn(Optional.of(7L));
        when(invitations.findByNamespaceId(7L)).thenReturn(Optional.of(invitation));
        UserAccount pending = mock(UserAccount.class);
        when(pending.getStatus()).thenReturn(UserStatus.PENDING);
        when(users.findById("guest")).thenReturn(Optional.of(pending));
        when(users.findLockedById("guest")).thenReturn(Optional.of(pending));
        clearInvocations(invitations, members, audit);

        assertThatThrownBy(() -> service.preview(created.token(), "guest"))
                .isInstanceOf(DomainNotFoundException.class);
        assertThatThrownBy(() -> service.accept(created.token(), "guest"))
                .isInstanceOf(DomainNotFoundException.class);
        verify(members, never()).save(any());
        verify(invitations, never()).save(any());
        assertThat(invitation.getConsumedAt()).isNull();
    }

    @Test
    void existingMemberCanOpenCurrentConsumedInvitationWithoutReconsuming() {
        NamespaceInvitationService.Created created = service.create("team", "owner");
        ArgumentCaptor<NamespaceInvitation> saved = ArgumentCaptor.forClass(NamespaceInvitation.class);
        verify(invitations).save(saved.capture());
        NamespaceInvitation invitation = saved.getValue();
        invitation.consume("someone", clock.instant());
        when(invitations.findByTokenHash(invitation.getTokenHash())).thenReturn(Optional.of(invitation));
        when(invitations.findNamespaceIdByTokenHash(invitation.getTokenHash())).thenReturn(Optional.of(7L));
        when(invitations.findByNamespaceId(7L)).thenReturn(Optional.of(invitation));
        when(members.findByNamespaceIdAndUserId(7L, "guest"))
                .thenReturn(Optional.of(new NamespaceMember(7L, "guest", NamespaceRole.MEMBER)));

        assertThat(service.preview(created.token(), "guest").outcome())
                .isEqualTo(NamespaceInvitationService.PreviewOutcome.ALREADY_MEMBER);
        assertThat(service.accept(created.token(), "guest").outcome())
                .isEqualTo(NamespaceInvitationService.AcceptOutcome.ALREADY_MEMBER);
        verify(members, never()).save(any());
    }

    @Test
    void replacingCurrentInvitationMakesOldTokenInvalidAfterPreview() {
        NamespaceInvitationService.Created first = service.create("team", "owner");
        ArgumentCaptor<NamespaceInvitation> saved = ArgumentCaptor.forClass(NamespaceInvitation.class);
        verify(invitations).save(saved.capture());
        NamespaceInvitation invitation = saved.getValue();
        when(invitations.findByTokenHash(invitation.getTokenHash())).thenReturn(Optional.of(invitation));
        when(invitations.findNamespaceIdByTokenHash(invitation.getTokenHash())).thenReturn(Optional.of(7L));
        when(invitations.findByNamespaceId(7L)).thenReturn(Optional.of(invitation));
        service.preview(first.token(), "guest");

        assertThat(service.create("team", "owner", true).replaced()).isTrue();
        assertThatThrownBy(() -> service.accept(first.token(), "guest"))
                .isInstanceOf(DomainNotFoundException.class);
        verify(members, never()).save(any());
    }

    @Test
    void revokeDeletesCurrentInvitationWithoutRemovingMembers() {
        when(invitations.findByNamespaceId(7L)).thenReturn(Optional.of(new NamespaceInvitation(
                7L, "a".repeat(64), "owner", clock.instant(), clock.instant().plusSeconds(86400))));
        service.revoke("team", "owner");
        verify(invitations).deleteByNamespaceId(7L);
        verify(members, never()).deleteByNamespaceId(any());
    }

    @Test
    void renewingConsumedInvitationDoesNotClaimToReplaceUsableLink() {
        NamespaceInvitationService.Created first = service.create("team", "owner");
        ArgumentCaptor<NamespaceInvitation> saved = ArgumentCaptor.forClass(NamespaceInvitation.class);
        verify(invitations).save(saved.capture());
        NamespaceInvitation old = saved.getValue();
        old.consume("guest", clock.instant());
        when(invitations.findByNamespaceId(7L)).thenReturn(Optional.of(old));

        NamespaceInvitationService.Created renewed = service.create("team", "owner", false);

        assertThat(renewed.replaced()).isFalse();
        assertThat(renewed.token()).isNotEqualTo(first.token());
    }

    @Test
    void nonMemberCannotPreviewOrAcceptAtExactExpiryInstant() {
        NamespaceInvitationService.Created created = service.create("team", "owner");
        NamespaceInvitation expired = invitationExpiringNow();
        when(invitations.findByTokenHash(expired.getTokenHash())).thenReturn(Optional.of(expired));
        when(invitations.findNamespaceIdByTokenHash(expired.getTokenHash())).thenReturn(Optional.of(7L));
        when(invitations.findByNamespaceId(7L)).thenReturn(Optional.of(expired));

        assertThat(service.hasActiveInvitation("team", "owner")).isFalse();
        assertThatThrownBy(() -> service.preview(created.token(), "guest"))
                .isInstanceOf(DomainNotFoundException.class);
        assertThatThrownBy(() -> service.accept(created.token(), "guest"))
                .isInstanceOf(DomainNotFoundException.class);
        verify(members, never()).save(any());
        assertThat(expired.getConsumedAt()).isNull();
    }

    @Test
    void existingMemberCanPreviewAndAcceptCurrentExpiredInvitationWhileNamespaceFrozen() {
        NamespaceInvitationService.Created created = service.create("team", "owner");
        NamespaceInvitation expired = invitationExpiringNow();
        when(invitations.findByTokenHash(expired.getTokenHash())).thenReturn(Optional.of(expired));
        when(invitations.findNamespaceIdByTokenHash(expired.getTokenHash())).thenReturn(Optional.of(7L));
        when(invitations.findByNamespaceId(7L)).thenReturn(Optional.of(expired));
        when(members.findByNamespaceIdAndUserId(7L, "guest"))
                .thenReturn(Optional.of(new NamespaceMember(7L, "guest", NamespaceRole.MEMBER)));
        namespaces.findById(7L).orElseThrow().setStatus(NamespaceStatus.FROZEN);
        clearInvocations(invitations, members, audit);

        assertThat(service.preview(created.token(), "guest").outcome())
                .isEqualTo(NamespaceInvitationService.PreviewOutcome.ALREADY_MEMBER);
        assertThat(service.accept(created.token(), "guest").outcome())
                .isEqualTo(NamespaceInvitationService.AcceptOutcome.ALREADY_MEMBER);
        verify(members, never()).save(any());
        verify(invitations, never()).save(any());
        assertThat(expired.getConsumedAt()).isNull();
    }

    @Test
    void renewingAtExactExpiryDoesNotClaimToReplaceUsableLink() {
        NamespaceInvitationService.Created first = service.create("team", "owner");
        NamespaceInvitation expired = invitationExpiringNow();
        when(invitations.findByNamespaceId(7L)).thenReturn(Optional.of(expired));

        NamespaceInvitationService.Created renewed = service.create("team", "owner", false);

        assertThat(renewed.replaced()).isFalse();
        assertThat(renewed.token()).isNotEqualTo(first.token());
    }

    @Test
    void historyStatusPreservesTerminalFactsAndUsesExactExpiryBoundary() {
        var row = new NamespaceInvitationHistory(7L, "owner", "Owner", clock.instant().minusSeconds(86400), clock.instant());
        row.end("REPLACED", clock.instant());
        assertThat(row.status(clock.instant())).isEqualTo("EXPIRED");
        var used = new NamespaceInvitationHistory(7L, "owner", "Owner", clock.instant(), clock.instant().plusSeconds(86400));
        used.consume("guest", "Guest", clock.instant());
        used.end("REVOKED", clock.instant());
        assertThat(used.status(clock.instant().plusSeconds(100000))).isEqualTo("USED");
    }

    @Test
    void historyRejectsMembersAndPlatformAdminsWithoutNamespaceManagementRole() {
        assertThatThrownBy(() -> service.history("team", "guest", 0, 10)).isInstanceOf(DomainNotFoundException.class);
        assertThatThrownBy(() -> service.history("team", "platform-admin", 0, 10)).isInstanceOf(DomainNotFoundException.class);
        verify(histories, never()).findByNamespaceId(any(), any());
    }

    @Test
    void inactiveManagerCannotReadHistoryAndFrozenNamespaceDoesNotRemoveReadPermission() {
        var namespace = namespaces.findById(7L).orElseThrow();
        when(histories.findByNamespaceId(eq(7L), any())).thenReturn(org.springframework.data.domain.Page.empty());
        namespace.setStatus(NamespaceStatus.FROZEN);
        assertThat(service.history("team", "owner", 0, 10)).isEmpty();
        namespace.setStatus(NamespaceStatus.ARCHIVED);
        assertThat(service.history("team", "owner", 0, 10)).isEmpty();
        UserAccount inactive = mock(UserAccount.class);
        when(inactive.getStatus()).thenReturn(UserStatus.MERGED);
        when(users.findById("owner")).thenReturn(Optional.of(inactive));
        assertThatThrownBy(() -> service.history("team", "owner", 0, 10)).isInstanceOf(DomainNotFoundException.class);
    }

    private NamespaceInvitation invitationExpiringNow() {
        ArgumentCaptor<NamespaceInvitation> saved = ArgumentCaptor.forClass(NamespaceInvitation.class);
        verify(invitations).save(saved.capture());
        return new NamespaceInvitation(7L, saved.getValue().getTokenHash(), "owner",
                clock.instant().minusSeconds(86400), clock.instant());
    }
}
