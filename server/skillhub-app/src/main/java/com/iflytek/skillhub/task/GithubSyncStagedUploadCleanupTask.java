package com.iflytek.skillhub.task;

import com.iflytek.skillhub.githubsync.GithubSyncUploadRepository;
import com.iflytek.skillhub.githubsync.GithubSyncIngestionRepository;
import com.iflytek.skillhub.storage.ObjectStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class GithubSyncStagedUploadCleanupTask {
    private static final Logger log = LoggerFactory.getLogger(GithubSyncStagedUploadCleanupTask.class);
    private final GithubSyncUploadRepository uploads;
    private final GithubSyncIngestionRepository ingestions;
    private final ObjectStorageService storage;
    private final Duration signedUrlGrace;

    public GithubSyncStagedUploadCleanupTask(GithubSyncUploadRepository uploads,
            GithubSyncIngestionRepository ingestions, ObjectStorageService storage,
            @Value("${skillhub.github-sync.signed-upload-cleanup-grace:PT1H}") Duration signedUrlGrace) {
        if (signedUrlGrace.isNegative() || signedUrlGrace.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("GitHub sync signed upload cleanup grace is invalid");
        }
        this.uploads = uploads;
        this.ingestions = ingestions;
        this.storage = storage;
        this.signedUrlGrace = signedUrlGrace;
    }

    @Scheduled(fixedDelayString = "${skillhub.github-sync.upload-cleanup-interval-ms:300000}")
    public void cleanup() {
        for (GithubSyncUploadRepository.CleanupUpload upload :
                uploads.claimCleanup(Instant.now(), signedUrlGrace, 100)) {
            try {
                storage.deleteObject(upload.objectKey());
                uploads.markCleaned(upload.uploadId(), Instant.now());
            } catch (RuntimeException exception) {
                log.warn("Could not clean up GitHub sync staged upload {}: {}",
                        upload.uploadId(), exception.getClass().getSimpleName());
            }
        }
        for (GithubSyncIngestionRepository.CleanupIngestion ingestion :
                ingestions.claimCleanup(Instant.now(), signedUrlGrace, 100)) {
            try {
                storage.deleteObject(ingestion.objectKey());
                ingestions.markCleaned(ingestion.id(), Instant.now());
            } catch (RuntimeException exception) {
                log.warn("Could not clean up terminal GitHub sync ingestion {}: {}",
                        ingestion.id(), exception.getClass().getSimpleName());
            }
        }
    }
}
