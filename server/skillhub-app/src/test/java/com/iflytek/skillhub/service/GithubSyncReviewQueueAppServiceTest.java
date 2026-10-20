package com.iflytek.skillhub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.dto.PageResponse;
import com.iflytek.skillhub.dto.ReviewTaskResponse;
import com.iflytek.skillhub.repository.GithubSyncReviewSourceQueryRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GithubSyncReviewQueueAppServiceTest {
    @Mock private ReviewPortalAppService reviews;
    @Mock private GithubSyncReviewSourceQueryRepository sources;

    @Test
    void addsSourceMetadataOnlyToAuthorizedReviewPage() {
        ReviewTaskResponse review = review(17L);
        PageResponse<ReviewTaskResponse> visible = new PageResponse<>(List.of(review), 1, 0, 20);
        UUID ingestionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Map<Long, NamespaceRole> roles = Map.of(42L, NamespaceRole.ADMIN);
        when(reviews.listSyncReviews("PENDING", 42L, 0, 20, "DESC", "reviewer", roles))
                .thenReturn(visible);
        when(sources.findQueueSources(List.of(17L))).thenReturn(Map.of(17L,
                new GithubSyncReviewSourceQueryRepository.QueueSource(ingestionId, runId,
                        981L, "owner", "repository", "skills/example", "a".repeat(40))));

        var page = new GithubSyncReviewQueueAppService(reviews, sources).list(
                "PENDING", 42L, 0, 20, "DESC", "reviewer", roles);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().review()).isSameAs(review);
        assertThat(page.items().getFirst().runId()).isEqualTo(runId);
        assertThat(page.items().getFirst().skillPath()).isEqualTo("skills/example");
        verify(sources).findQueueSources(List.of(17L));
    }

    @Test
    void doesNotQuerySourceMetadataForEmptyPage() {
        when(reviews.listSyncReviews("PENDING", null, 0, 20, "DESC", "reviewer", Map.of()))
                .thenReturn(new PageResponse<>(List.of(), 0, 0, 20));

        var page = new GithubSyncReviewQueueAppService(reviews, sources).list(
                "PENDING", null, 0, 20, "DESC", "reviewer", Map.of());

        assertThat(page.items()).isEmpty();
        verify(sources).findQueueSources(List.of());
    }

    @Test
    void rejectsReviewWithoutItsIngestionSource() {
        when(reviews.listSyncReviews("PENDING", null, 0, 20, "DESC", "reviewer", Map.of()))
                .thenReturn(new PageResponse<>(List.of(review(17L)), 1, 0, 20));
        when(sources.findQueueSources(List.of(17L))).thenReturn(Map.of());

        assertThatThrownBy(() -> new GithubSyncReviewQueueAppService(reviews, sources).list(
                "PENDING", null, 0, 20, "DESC", "reviewer", Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    private ReviewTaskResponse review(long id) {
        return new ReviewTaskResponse(id, 9L, "team", "example", "1.0.0", "PENDING",
                "github-sync-publisher", null, null, null, null,
                Instant.parse("2026-09-29T00:00:00Z"), null,
                "SKILL_VERSION", 8L, 9L, "example", "GITHUB_SYNC");
    }
}
