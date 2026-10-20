package com.iflytek.skillhub.task;

import com.iflytek.skillhub.githubsync.GithubSyncIngestionRepository;
import com.iflytek.skillhub.githubsync.GithubSourceIgnoreRepository;
import com.iflytek.skillhub.service.GithubSyncArchiveValidator;
import com.iflytek.skillhub.storage.ObjectStorageService;
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
public class GithubSyncIngestionTask {
    private static final Logger log = LoggerFactory.getLogger(GithubSyncIngestionTask.class);
    private final GithubSyncIngestionRepository ingestions;
    private final GithubSyncArchiveValidator validator;
    private final ObjectStorageService storage;
    private final GithubSourceIgnoreRepository ignores;
    private final Duration validationLease;

    public GithubSyncIngestionTask(GithubSyncIngestionRepository ingestions,
            GithubSyncArchiveValidator validator, ObjectStorageService storage,
            GithubSourceIgnoreRepository ignores,
            @Value("${skillhub.github-sync.ingestion-validation-lease:PT30M}") Duration validationLease) {
        if (validationLease.isNegative() || validationLease.isZero()
                || validationLease.compareTo(Duration.ofHours(4)) > 0) {
            throw new IllegalArgumentException("GitHub sync ingestion validation lease is invalid");
        }
        this.ingestions = ingestions;
        this.validator = validator;
        this.storage = storage;
        this.ignores = ignores;
        this.validationLease = validationLease;
    }

    @Scheduled(fixedDelayString = "${skillhub.github-sync.ingestion-validation-interval-ms:5000}")
    public void validatePending() {
        for (int processed = 0; processed < 8; processed++) {
            Instant now = Instant.now();
            Optional<GithubSyncIngestionRepository.ClaimedIngestion> claim =
                    ingestions.claim(UUID.randomUUID(), now, now.plus(validationLease));
            if (claim.isEmpty()) {
                return;
            }
            validateOne(claim.get());
        }
    }

    private void validateOne(GithubSyncIngestionRepository.ClaimedIngestion ingestion) {
        try {
            if (ingestion.sourceId() != null
                    && ignores.ignoredForSource(ingestion.sourceId(), ingestion.skillPath())) {
                fail(ingestion, "SKILL_PATH_IGNORED", null, "IGNORED");
                return;
            }
            if (!storage.exists(ingestion.objectKey())) {
                if (ingestion.attempt() >= 3) {
                    fail(ingestion, "OBJECT_MISSING", null, "RETRYABLE_FAILURE");
                } else {
                    fail(ingestion, "OBJECT_MISSING", nextRetry(ingestion.attempt()), null);
                }
                return;
            }
            long actualSize = storage.getMetadata(ingestion.objectKey()).size();
            if (actualSize != ingestion.sizeBytes()) {
                fail(ingestion, "ARCHIVE_SIZE_MISMATCH", null, "RETRYABLE_FAILURE");
                return;
            }
            GithubSyncArchiveValidator.ArchiveValidation result = validator.validate(
                    ingestion.objectKey(), ingestion.sizeBytes(), ingestion.sha256(), ingestion.skillPath());
            if (!ingestions.validated(ingestion.id(), ingestion.validationToken(),
                    result.contentSha256(), result.warningCount(), result.evidenceJson(),
                    Instant.now())) {
                log.debug("GitHub sync ingestion validation lease became stale: {}", ingestion.id());
            }
        } catch (GithubSyncArchiveValidator.ValidationFailure exception) {
            String pathOutcome = switch (exception.code()) {
                case "ARCHIVE_SIZE_MISMATCH", "ARCHIVE_DIGEST_MISMATCH" -> "RETRYABLE_FAILURE";
                default -> "TERMINAL_FAILURE";
            };
            fail(ingestion, exception.code(), null, pathOutcome);
        } catch (GithubSyncArchiveValidator.InsufficientLocalCapacityException exception) {
            fail(ingestion, "LOCAL_CAPACITY", Instant.now().plus(Duration.ofHours(1)), null);
        } catch (IOException exception) {
            fail(ingestion, "INGESTION_IO", nextRetry(ingestion.attempt()), null);
        } catch (RuntimeException exception) {
            log.warn("GitHub sync ingestion {} failed with {}", ingestion.id(),
                    exception.getClass().getSimpleName());
            fail(ingestion, "INGESTION_INTERNAL", nextRetry(ingestion.attempt()), null);
        }
    }

    private void fail(GithubSyncIngestionRepository.ClaimedIngestion ingestion,
            String code, Instant retryAt, String pathOutcome) {
        if (!ingestions.failed(ingestion.id(), ingestion.validationToken(),
                code, retryAt, pathOutcome, Instant.now())) {
            log.debug("GitHub sync ingestion failure arrived after lease changed: {}", ingestion.id());
        }
    }

    private Instant nextRetry(int attempt) {
        Duration delay = attempt <= 1 ? Duration.ofMinutes(15)
                : attempt == 2 ? Duration.ofHours(1)
                : attempt == 3 ? Duration.ofHours(6) : Duration.ofDays(1);
        return Instant.now().plus(delay);
    }
}
