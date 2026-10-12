package com.iflytek.skillhub.domain.namespace;

import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.domain.user.UserAccount;
import com.iflytek.skillhub.domain.user.UserAccountRepository;
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
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), ZoneOffset.UTC);
    private final NamespaceInvitationService service = new NamespaceInvitationService(namespaces, invitations, members, users, audit, clock);

    @BeforeEach
    void setup() {
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
    }

    @Test
    void revokedTokenCannotBePreviewed() {
        assertThatThrownBy(() -> service.preview("A".repeat(43), "guest"))
                .isInstanceOf(DomainNotFoundException.class);
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

        assertThat(service.preview(created.token(), "guest").alreadyMember()).isFalse();
        assertThat(service.accept(created.token(), "guest").alreadyMember()).isFalse();

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

        assertThat(service.preview(created.token(), "guest").alreadyMember()).isTrue();
        assertThat(service.accept(created.token(), "guest").alreadyMember()).isTrue();
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

        service.create("team", "owner", true);
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
}
