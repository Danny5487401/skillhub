package com.iflytek.skillhub.repository;

import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceInvitationService;
import com.iflytek.skillhub.domain.namespace.NamespaceMember;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.domain.user.UserAccount;
import com.iflytek.skillhub.infra.jpa.NamespaceInvitationJpaRepository;
import com.iflytek.skillhub.infra.jpa.NamespaceJpaRepository;
import com.iflytek.skillhub.infra.jpa.NamespaceMemberJpaRepository;
import com.iflytek.skillhub.infra.jpa.UserAccountJpaRepository;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
@Import({NamespaceInvitationService.class, AuditLogService.class, NamespaceInvitationPostgresTest.ClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NamespaceInvitationPostgresTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean Clock clock() { return Clock.systemUTC(); }
    }

    @Autowired private NamespaceInvitationService invitations;
    @Autowired private NamespaceInvitationJpaRepository invitationRows;
    @Autowired private NamespaceJpaRepository namespaces;
    @Autowired private NamespaceMemberJpaRepository members;
    @Autowired private UserAccountJpaRepository users;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void twoConcurrentAcceptsCreateOnlyOneMemberAndOneConsumption() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String owner = "inv-owner-" + suffix;
        String first = "inv-a-" + suffix;
        String second = "inv-b-" + suffix;
        Long namespaceId = new TransactionTemplate(transactionManager).execute(status -> {
            users.saveAndFlush(new UserAccount(owner, owner, null, null));
            users.saveAndFlush(new UserAccount(first, first, null, null));
            users.saveAndFlush(new UserAccount(second, second, null, null));
            Namespace namespace = namespaces.saveAndFlush(new Namespace("inv-" + suffix, "Invitation test", owner));
            members.saveAndFlush(new NamespaceMember(namespace.getId(), owner, NamespaceRole.OWNER));
            return namespace.getId();
        });
        String token = invitations.create("inv-" + suffix, owner).token();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> attempt(start, token, first));
            var two = pool.submit(() -> attempt(start, token, second));
            start.countDown();
            assertThat(one.get(20, TimeUnit.SECONDS) + two.get(20, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(members.findByNamespaceId(namespaceId, org.springframework.data.domain.Pageable.unpaged())
                .getContent().stream().filter(member -> member.getRole() == NamespaceRole.MEMBER)).hasSize(1);
        assertThat(invitationRows.findByNamespaceId(namespaceId).orElseThrow().getConsumedBy()).isIn(first, second);
    }

    @Test
    void revokeAndReplaceSerializeWithAcceptance() throws Exception {
        for (boolean replace : new boolean[] {false, true}) {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            String owner = "inv-owner-" + suffix;
            String guest = "inv-guest-" + suffix;
            String slug = "inv-" + suffix;
            Long namespaceId = new TransactionTemplate(transactionManager).execute(status -> {
                users.saveAndFlush(new UserAccount(owner, owner, null, null));
                users.saveAndFlush(new UserAccount(guest, guest, null, null));
                Namespace namespace = namespaces.saveAndFlush(new Namespace(slug, "Invitation race", owner));
                members.saveAndFlush(new NamespaceMember(namespace.getId(), owner, NamespaceRole.OWNER));
                return namespace.getId();
            });
            String oldToken = invitations.create(slug, owner).token();
            CountDownLatch start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var accept = pool.submit(() -> attempt(start, oldToken, guest));
                var change = pool.submit(() -> {
                    start.await();
                    if (replace) invitations.create(slug, owner, true);
                    else invitations.revoke(slug, owner);
                    return true;
                });
                start.countDown();
                int accepted = accept.get(20, TimeUnit.SECONDS);
                assertThat(change.get(20, TimeUnit.SECONDS)).isTrue();
                assertThat(members.findByNamespaceIdAndUserId(namespaceId, guest).isPresent())
                        .isEqualTo(accepted == 1);
                if (replace) {
                    assertThat(invitationRows.findByNamespaceId(namespaceId)).isPresent();
                    assertThat(invitationRows.findByNamespaceId(namespaceId).orElseThrow().getConsumedAt()).isNull();
                } else {
                    assertThat(invitationRows.findByNamespaceId(namespaceId)).isEmpty();
                }
                assertThat(attempt(new CountDownLatch(0), oldToken, guest)).isZero();
            }
        }
    }

    private int attempt(CountDownLatch start, String token, String userId) throws InterruptedException {
        start.await();
        try {
            invitations.accept(token, userId);
            return 1;
        } catch (DomainNotFoundException expected) {
            return 0;
        }
    }
}
