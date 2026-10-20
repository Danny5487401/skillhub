package com.iflytek.skillhub.task;

import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.githubsync.GithubSyncPublicationRepository;
import com.iflytek.skillhub.service.GithubSyncArchiveValidator;
import com.iflytek.skillhub.service.GithubSyncPublicationAppService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
public class GithubSyncPublicationTask {
    private static final Logger log = LoggerFactory.getLogger(GithubSyncPublicationTask.class);

    private final GithubSyncPublicationRepository publications;
    private final GithubSyncPublicationAppService service;
    private final Duration lease;

    public GithubSyncPublicationTask(GithubSyncPublicationRepository publications,
                                     GithubSyncPublicationAppService service,
                                     @Value("${skillhub.github-sync.publication-lease:PT4H}") Duration lease) {
        if (lease.isNegative() || lease.isZero() || lease.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("GitHub publication lease is invalid");
        }
        this.publications = publications;
        this.service = service;
        this.lease = lease;
    }

    @Scheduled(fixedDelayString = "${skillhub.github-sync.publication-interval-ms:5000}")
    public void publishValidated() {
        for (int processed = 0; processed < 4; processed++) {
            Instant now = Instant.now();
            Optional<GithubSyncPublicationRepository.ClaimedPublication> claim =
                    publications.claim(UUID.randomUUID(), now, now.plus(lease));
            if (claim.isEmpty()) {
                return;
            }
            publishOne(claim.get());
        }
    }

    private void publishOne(GithubSyncPublicationRepository.ClaimedPublication claim) {
        try {
            service.process(claim);
        } catch (GithubSyncArchiveValidator.ValidationFailure exception) {
            settleBlocked(claim, exception.code());
        } catch (DomainBadRequestException exception) {
            if ("error.skill.publish.githubSync.slugConflict".equals(exception.messageCode())
                    && exception.messageArgs().length > 0
                    && exception.messageArgs()[0] instanceof String slug) {
                if (!publications.blockedSlugConflict(claim, slug, Instant.now())) {
                    log.debug("GitHub publication lease became stale: {}", claim.id());
                }
            } else {
                settleBlocked(claim, "PUBLISH_POLICY_BLOCKED");
            }
            log.info("GitHub publication {} blocked by {}", claim.id(), exception.messageCode());
        } catch (GithubSyncArchiveValidator.InsufficientLocalCapacityException exception) {
            settleRetry(claim, "LOCAL_CAPACITY");
        } catch (IOException exception) {
            settleRetry(claim, "PUBLICATION_IO");
        } catch (RuntimeException exception) {
            log.warn("GitHub publication {} failed with {}", claim.id(),
                    exception.getClass().getSimpleName());
            settleRetry(claim, "PUBLICATION_INTERNAL");
        }
    }

    private void settleBlocked(GithubSyncPublicationRepository.ClaimedPublication claim, String code) {
        if (!publications.blocked(claim, code, Instant.now())) {
            log.debug("GitHub publication lease became stale: {}", claim.id());
        }
    }

    private void settleRetry(GithubSyncPublicationRepository.ClaimedPublication claim, String code) {
        Duration delay = claim.attempt() == 1 ? Duration.ofMinutes(15)
                : claim.attempt() == 2 ? Duration.ofHours(1)
                : claim.attempt() == 3 ? Duration.ofHours(6) : Duration.ofDays(1);
        if (!publications.retry(claim, code, Instant.now().plus(delay), Instant.now())) {
            log.debug("GitHub publication lease became stale: {}", claim.id());
        }
    }
}
