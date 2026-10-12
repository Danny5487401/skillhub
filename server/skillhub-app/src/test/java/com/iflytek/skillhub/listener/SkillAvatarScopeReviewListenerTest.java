package com.iflytek.skillhub.listener;

import com.iflytek.skillhub.domain.event.SkillPublishedEvent;
import com.iflytek.skillhub.service.SkillAvatarAppService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.after;

@SpringBootTest
@ActiveProfiles("test")
class SkillAvatarScopeReviewListenerTest {
    @Autowired private ApplicationEventPublisher events;
    @Autowired private PlatformTransactionManager transactions;
    @MockBean private SkillAvatarAppService avatars;

    @Test
    void committedPublicationTriggersScopeReview() {
        new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
            events.publishEvent(new SkillPublishedEvent(912345L, 1L, "publisher"));
            verify(avatars, never()).prepareScopeReview(912345L, "publisher");
        });

        verify(avatars, timeout(5000)).prepareScopeReview(912345L, "publisher");
    }

    @Test
    void rolledBackPublicationDoesNotTriggerScopeReview() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            events.publishEvent(new SkillPublishedEvent(912346L, 1L, "publisher"));
            status.setRollbackOnly();
        });

        verify(avatars, after(1000).never()).prepareScopeReview(912346L, "publisher");
    }
}
