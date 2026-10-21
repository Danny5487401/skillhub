package com.iflytek.skillhub.storage;

import java.util.ArrayList;
import java.util.UUID;
import java.io.ByteArrayInputStream;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;

import java.util.concurrent.TimeUnit;

/** Explicit opt-in: an enabled run with missing configuration fails rather than skips. */
@EnabledIfSystemProperty(named = "skillhub.storage.contract", matches = "true")
class S3StorageContractTest {
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void configuredBackendMustSatisfySharedContract() throws Exception {
        var service = configuredService();
        try {
            service.init();
            new StorageContractProbe(service).verify();
        } finally {
            service.close();
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "skillhub.storage.contract.full", matches = "true")
    @Timeout(value = 240, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void realBackendMustTraverseMultiplePagesAndDeleteMoreThanOneBatch() {
        var service = configuredService();
        var prefix = "skillhub-diagnostics/" + UUID.randomUUID() + "/pagination/";
        var keys = new ArrayList<String>();
        System.out.println("Pagination diagnostic object prefix: " + prefix);
        Throwable failure = null;
        try {
            service.init();
            for (int i = 0; i < 1001; i++) {
                String key = prefix + i;
                keys.add(key);
                service.putObject(key, new ByteArrayInputStream(new byte[]{42}), 1, "application/octet-stream");
            }
            assertThat(service.listPrefixUsage(prefix, 2000))
                    .contains(new StoragePrefixUsage(1001, 1001, true));
            assertThat(service.listPrefixUsage(prefix, 1000))
                    .contains(new StoragePrefixUsage(1000, 1000, false));
            // A real service may report transient per-object failures under a full batch.
            // Keep these visible, then exercise caller-side idempotent recovery with a bound.
            for (int attempt = 1; ; attempt++) {
                try {
                    service.deleteObjects(keys);
                    break;
                } catch (StorageAccessException exception) {
                    if (attempt == 3) throw exception;
                    Throwable cause = exception.getCause();
                    String detail = cause instanceof IllegalStateException
                            && cause.getMessage() != null
                            && cause.getMessage().startsWith("S3 batch deletion reported ")
                            ? cause.getMessage() : cause == null ? "unknown" : cause.getClass().getSimpleName();
                    System.out.println("Batch deletion reported failure; caller recovery attempt "
                            + attempt + "; " + detail);
                }
            }
            assertThat(service.listPrefixUsage(prefix, 2000))
                    .contains(new StoragePrefixUsage(0, 0, true));
            keys.clear(); // Absence was verified; do not issue a redundant cleanup load burst.
        } catch (RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            try {
                service.deleteObjects(keys);
            } catch (RuntimeException exception) {
                if (failure != null) failure.addSuppressed(exception);
                else throw exception;
            } finally {
                service.close();
            }
        }
    }

    private S3StorageService configuredService() {
        var properties = Binder.get(new StandardEnvironment())
                .bind("skillhub.storage.s3", S3StorageProperties.class).orElseThrow(
                        () -> new IllegalStateException("S3 diagnostic configuration is missing"));
        if (properties.getEndpoint() == null || properties.getEndpoint().isBlank()
                || properties.getBucket() == null || properties.getBucket().isBlank()) {
            throw new IllegalStateException("S3 endpoint and prepared bucket are required");
        }
        properties.setAutoCreateBucket(false);
        // Use the same per-call timeout defaults as the application, including batch operations.
        return new S3StorageService(properties);
    }
}
