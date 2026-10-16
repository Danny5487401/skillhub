package com.iflytek.skillhub.search.event;

import com.iflytek.skillhub.domain.event.SkillPublishedEvent;
import com.iflytek.skillhub.domain.event.SkillVersionYankedEvent;
import com.iflytek.skillhub.domain.event.SkillStatusChangedEvent;
import com.iflytek.skillhub.domain.skill.SkillStatus;
import com.iflytek.skillhub.search.SearchIndexService;
import com.iflytek.skillhub.search.SearchRebuildService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class SearchIndexEventListenerTest {

    @Test
    void visibilityChangeRebuildsExistingSkill() {
        SearchRebuildService rebuild = mock(SearchRebuildService.class);
        SearchIndexEventListener listener = new SearchIndexEventListener(rebuild, mock(SearchIndexService.class));
        listener.onSkillVisibilityChanged(new com.iflytek.skillhub.domain.event.SkillVisibilityChangedEvent(42L,
                com.iflytek.skillhub.domain.skill.SkillVisibility.PUBLIC, com.iflytek.skillhub.domain.skill.SkillVisibility.PRIVATE));
        verify(rebuild).rebuildBySkill(42L);
    }

    @Test
    void skillPublishedEventShouldTriggerSkillRebuild() {
        SearchRebuildService searchRebuildService = mock(SearchRebuildService.class);
        SearchIndexService searchIndexService = mock(SearchIndexService.class);
        SearchIndexEventListener listener = new SearchIndexEventListener(searchRebuildService, searchIndexService);

        listener.onSkillPublished(new SkillPublishedEvent(42L, 100L, "reviewer-1"));

        verify(searchRebuildService).rebuildBySkill(42L);
    }

    @Test
    void yankedVersionShouldRebuildOnlyAfterCommit() {
        assertYankTransactionOutcome(TransactionSynchronization.STATUS_COMMITTED);
    }

    @Test
    void rolledBackYankShouldNotRebuildSearchDocument() {
        assertYankTransactionOutcome(TransactionSynchronization.STATUS_ROLLED_BACK);
    }

    private void assertYankTransactionOutcome(int completionStatus) {
        SearchRebuildService rebuildService = mock(SearchRebuildService.class);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(TransactionalEventListenerFactory.class);
            context.registerBean(SearchIndexEventListener.class,
                    () -> new SearchIndexEventListener(rebuildService, mock(SearchIndexService.class)));
            context.refresh();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.initSynchronization();
            try {
                context.publishEvent(new SkillVersionYankedEvent(42L, 100L, "owner-1", true));
                verifyNoInteractions(rebuildService);
                TransactionSynchronizationUtils.triggerAfterCompletion(completionStatus);
                if (completionStatus == TransactionSynchronization.STATUS_COMMITTED) {
                    verify(rebuildService).rebuildBySkill(42L);
                } else {
                    verifyNoInteractions(rebuildService);
                }
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }
        }
    }

    @Test
    void archivedStatusShouldRemoveSearchDocument() {
        SearchRebuildService searchRebuildService = mock(SearchRebuildService.class);
        SearchIndexService searchIndexService = mock(SearchIndexService.class);
        SearchIndexEventListener listener = new SearchIndexEventListener(searchRebuildService, searchIndexService);

        listener.onSkillStatusChanged(new SkillStatusChangedEvent(42L, SkillStatus.ACTIVE, SkillStatus.ARCHIVED));

        verify(searchIndexService).remove(42L);
    }
}
