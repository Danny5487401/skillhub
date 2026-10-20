package com.iflytek.skillhub.metrics;

import com.iflytek.skillhub.config.SkillPublishProperties;
import com.iflytek.skillhub.storage.ObjectStorageService;
import com.iflytek.skillhub.storage.StorageProperties;
import com.iflytek.skillhub.storage.StoragePrefixUsage;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/** Samples upload reservations and provider-specific staging capacity without trusting client sizes. */
@Component
public class GithubSyncStagingCapacityMetrics {
    private static final Logger log = LoggerFactory.getLogger(GithubSyncStagingCapacityMetrics.class);
    private static final String STAGING_PREFIX = "github-sync/staging/";
    private static final int S3_MAX_OBJECTS_PER_SAMPLE = 10_000;
    private final JdbcTemplate jdbc;
    private final StorageProperties storage;
    private final ObjectStorageService objects;
    private final SkillPublishProperties publishPolicy;
    private final long s3CapacityLimitBytes;
    private final AtomicLong reservedBytes = new AtomicLong();
    private final AtomicLong localAvailableBytes = new AtomicLong(-1);
    private final AtomicLong localCapacityWarning = new AtomicLong();
    private final AtomicLong s3UsageBytes = new AtomicLong(-1);
    private final AtomicLong s3ObjectCount = new AtomicLong(-1);
    private final AtomicLong s3ScanComplete = new AtomicLong();
    private final AtomicLong s3CapacityWarning = new AtomicLong();

    public GithubSyncStagingCapacityMetrics(JdbcTemplate jdbc, StorageProperties storage,
            ObjectStorageService objects, SkillPublishProperties publishPolicy, MeterRegistry registry,
            @Value("${skillhub.github-sync.staging-capacity-limit-bytes:0}") long s3CapacityLimitBytes) {
        if (s3CapacityLimitBytes < 0) {
            throw new IllegalArgumentException("GitHub sync staging capacity limit cannot be negative");
        }
        this.jdbc = jdbc;
        this.storage = storage;
        this.objects = objects;
        this.publishPolicy = publishPolicy;
        this.s3CapacityLimitBytes = s3CapacityLimitBytes;
        Gauge.builder("skillhub.github.sync.staging.reserved.bytes", reservedBytes, AtomicLong::get)
                .description("Declared bytes for staged uploads not yet cleaned; actual S3 object size may differ")
                .register(registry);
        Gauge.builder("skillhub.github.sync.staging.local.available.bytes", localAvailableBytes,
                        AtomicLong::get)
                .description("Usable bytes on the local staging filesystem, or -1 for S3")
                .register(registry);
        Gauge.builder("skillhub.github.sync.staging.local.capacity.warning", localCapacityWarning,
                        AtomicLong::get)
                .description("1 when local staging cannot fit one maximum-size package")
                .register(registry);
        Gauge.builder("skillhub.github.sync.staging.s3.used.bytes", s3UsageBytes, AtomicLong::get)
                .description("Actual bytes in the GitHub sync S3 staging prefix, or -1 if unavailable")
                .register(registry);
        Gauge.builder("skillhub.github.sync.staging.s3.objects", s3ObjectCount, AtomicLong::get)
                .description("Actual S3 staging object count, or -1 if unavailable")
                .register(registry);
        Gauge.builder("skillhub.github.sync.staging.s3.scan.complete", s3ScanComplete, AtomicLong::get)
                .description("1 when the bounded S3 staging prefix scan covered every object")
                .register(registry);
        Gauge.builder("skillhub.github.sync.staging.s3.capacity.warning", s3CapacityWarning,
                        AtomicLong::get)
                .description("1 when S3 staging exceeds its configured byte budget or cannot be measured")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${skillhub.github-sync.upload-cleanup-interval-ms:300000}")
    public void sample() {
        Long reserved = jdbc.queryForObject("""
                SELECT COALESCE(sum(upload.size_bytes), 0)::bigint
                FROM github_sync_staged_upload upload
                LEFT JOIN github_sync_package_ingestion ingestion ON ingestion.upload_id = upload.id
                WHERE (ingestion.id IS NULL AND upload.status <> 'CLEANED')
                   OR (ingestion.id IS NOT NULL AND ingestion.object_cleaned_at IS NULL)
                """, Long.class);
        reservedBytes.set(reserved == null ? 0 : reserved);
        if (!"local".equals(storage.getProvider())) {
            localAvailableBytes.set(-1);
            localCapacityWarning.set(0);
            return;
        }
        try {
            Path path = Path.of(storage.getLocal().getBasePath()).toAbsolutePath();
            while (!Files.exists(path)) {
                path = path.getParent();
                if (path == null) {
                    throw new IOException("local staging filesystem does not exist");
                }
            }
            long available = Files.getFileStore(path).getUsableSpace();
            localAvailableBytes.set(available);
            boolean low = available < publishPolicy.getMaxPackageSize();
            localCapacityWarning.set(low ? 1 : 0);
            if (low) {
                log.warn("GitHub sync local staging capacity is below one maximum-size package: "
                        + "availableBytes={}, packageLimitBytes={}",
                        available, publishPolicy.getMaxPackageSize());
            }
        } catch (IOException exception) {
            localAvailableBytes.set(-1);
            localCapacityWarning.set(1);
            log.warn("Could not read GitHub sync local staging capacity: {}",
                    exception.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${skillhub.github-sync.staging-capacity-sample-interval-ms:3600000}")
    public void sampleS3() {
        if (!"s3".equals(storage.getProvider())) {
            return;
        }
        try {
            StoragePrefixUsage usage = objects.listPrefixUsage(STAGING_PREFIX, S3_MAX_OBJECTS_PER_SAMPLE)
                    .orElseThrow(() -> new IllegalStateException("S3 prefix usage is unavailable"));
            s3ObjectCount.set(usage.objectCount());
            s3ScanComplete.set(usage.complete() ? 1 : 0);
            s3UsageBytes.set(usage.complete() ? usage.bytes() : -1);
            boolean warning = !usage.complete()
                    || (s3CapacityLimitBytes == 0 && usage.objectCount() > 0)
                    || (s3CapacityLimitBytes > 0 && usage.bytes() >= s3CapacityLimitBytes);
            s3CapacityWarning.set(warning ? 1 : 0);
            if (warning) {
                log.warn("GitHub sync S3 staging capacity needs attention: objects={}, "
                        + "scanComplete={}, usedBytes={}, configuredLimitBytes={}",
                        usage.objectCount(), usage.complete(), usage.bytes(), s3CapacityLimitBytes);
            }
        } catch (RuntimeException exception) {
            s3UsageBytes.set(-1);
            s3ObjectCount.set(-1);
            s3ScanComplete.set(0);
            s3CapacityWarning.set(1);
            log.warn("Could not measure GitHub sync S3 staging capacity");
        }
    }
}
