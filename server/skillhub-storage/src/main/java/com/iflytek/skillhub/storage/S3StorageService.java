package com.iflytek.skillhub.storage;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

import java.io.InputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * S3-compatible object storage implementation used for persisted skill packages and generated
 * download URLs.
 */
@Service
@ConditionalOnProperty(name = "skillhub.storage.provider", havingValue = "s3")
public class S3StorageService implements ObjectStorageService {
    private static final Logger log = LoggerFactory.getLogger(S3StorageService.class);
    private final S3StorageProperties properties;
    private final Object bucketPreparationLock = new Object();
    private S3Client s3Client;
    private S3Presigner s3Presigner;
    private S3Presigner s3UploadPresigner;
    private volatile boolean bucketPrepared;

    public S3StorageService(S3StorageProperties properties) { this.properties = properties; }

    @PostConstruct
    void init() {
        ApacheHttpClient.Builder httpClientBuilder = ApacheHttpClient.builder()
                .maxConnections(properties.getMaxConnections())
                .connectionAcquisitionTimeout(properties.getConnectionAcquisitionTimeout());
        this.s3Client = buildS3Client(httpClientBuilder);
        this.s3Presigner = buildPresigner();
        this.s3UploadPresigner = buildUploadPresigner();
        String authMode = isStaticCredentials() ? "static credentials" : "IAM credentials (default provider chain)";
        if (properties.isAutoCreateBucket()) {
            log.info("Initialized S3 storage client for bucket '{}' using {} (bucket auto-creation is deferred until first write)",
                    properties.getBucket(), authMode);
        } else {
            log.info("Initialized S3 storage client for bucket '{}' using {}", properties.getBucket(), authMode);
        }
    }

    private boolean isStaticCredentials() {
        return properties.getAccessKey() != null && !properties.getAccessKey().isBlank()
                && properties.getSecretKey() != null && !properties.getSecretKey().isBlank();
    }

    AwsCredentialsProvider buildCredentialsProvider() {
        if (isStaticCredentials()) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey()));
        }
        return DefaultCredentialsProvider.create();
    }

    protected S3Client buildS3Client(ApacheHttpClient.Builder httpClientBuilder) {
        var builder = S3Client.builder()
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(buildCredentialsProvider())
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.isForcePathStyle())
                        .chunkedEncodingEnabled(!properties.isDisableChunkedEncoding())
                        .build())
                .httpClientBuilder(httpClientBuilder)
                .overrideConfiguration(config -> config
                        .apiCallAttemptTimeout(properties.getApiCallAttemptTimeout())
                        .apiCallTimeout(properties.getApiCallTimeout()));
        if (properties.getEndpoint() != null && !properties.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(properties.getEndpoint()));
        }
        return builder.build();
    }

    S3Presigner buildPresigner() {
        String endpoint = properties.getPublicEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = properties.getEndpoint();
        }
        return buildPresignerAtEndpoint(endpoint);
    }

    private S3Presigner buildUploadPresigner() {
        String endpoint = properties.getEndpoint();
        if (endpoint == null || endpoint.isBlank()
                || endpoint.equals(properties.getPublicEndpoint())) {
            return s3Presigner;
        }
        return buildPresignerAtEndpoint(endpoint);
    }

    private S3Presigner buildPresignerAtEndpoint(String endpoint) {
        var presignerBuilder = S3Presigner.builder()
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(buildCredentialsProvider())
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.isForcePathStyle())
                        .build());
        if (endpoint != null && !endpoint.isBlank()) {
            presignerBuilder.endpointOverride(URI.create(endpoint));
        }
        return presignerBuilder.build();
    }

    @PreDestroy
    void close() {
        if (s3UploadPresigner != null && s3UploadPresigner != s3Presigner) {
            s3UploadPresigner.close();
        }
        if (s3Presigner != null) {
            s3Presigner.close();
        }
        if (s3Client != null) {
            s3Client.close();
        }
    }

    private void ensureBucketPrepared() {
        if (!properties.isAutoCreateBucket() || bucketPrepared) {
            return;
        }

        synchronized (bucketPreparationLock) {
            if (bucketPrepared) {
                return;
            }
            try {
                log.info("Bucket '{}' does not exist, creating...", properties.getBucket());
                s3Client.createBucket(CreateBucketRequest.builder().bucket(properties.getBucket()).build());
            } catch (BucketAlreadyExistsException | BucketAlreadyOwnedByYouException e) {
                log.debug("Bucket '{}' was created concurrently, continuing", properties.getBucket());
            }
            bucketPrepared = true;
        }
    }

    private void putObjectInternal(String key, RequestBody requestBody, long size, String contentType) {
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(properties.getBucket())
                        .key(key)
                        .contentType(contentType)
                        .contentLength(size)
                        .build(),
                requestBody);
    }

    private Path stagePutObjectBody(InputStream data) throws IOException {
        Path stagedBody = Files.createTempFile("skillhub-s3-upload-", ".tmp");
        try {
            // Write into the existing private file; replacing it can widen its permissions.
            try (var output = Files.newOutputStream(stagedBody)) {
                data.transferTo(output);
            }
            return stagedBody;
        } catch (IOException | RuntimeException | Error e) {
            try {
                Files.deleteIfExists(stagedBody);
            } catch (IOException | RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != e) {
                    e.addSuppressed(cleanupFailure);
                }
            }
            throw e;
        }
    }

    private void deleteStagedBody(Path stagedBody) {
        if (stagedBody == null) {
            return;
        }
        try {
            Files.deleteIfExists(stagedBody);
        } catch (IOException e) {
            log.warn("Failed to clean up staged S3 upload body {}", stagedBody, e);
        }
    }

    @Override public void putObject(String key, InputStream data, long size, String contentType) {
        Path stagedBody = null;
        try {
            // Signing and retries can read a request body more than once. Callers can
            // supply non-resettable file streams, so keep a replayable, disk-backed body.
            stagedBody = stagePutObjectBody(data);
            if (Files.size(stagedBody) != size) {
                throw new IllegalArgumentException("Object size does not match the supplied stream");
            }
            RequestBody requestBody = RequestBody.fromFile(stagedBody);
            if (!properties.isAutoCreateBucket() || bucketPrepared) {
                putObjectInternal(key, requestBody, size, contentType);
                return;
            }

            try {
                putObjectInternal(key, requestBody, size, contentType);
                bucketPrepared = true;
            } catch (NoSuchBucketException e) {
                ensureBucketPrepared();
                putObjectInternal(key, requestBody, size, contentType);
            }
        } catch (IOException e) {
            throw new StorageAccessException("putObject", key, e);
        } catch (RuntimeException e) {
            throw new StorageAccessException("putObject", key, e);
        } finally {
            deleteStagedBody(stagedBody);
        }
    }

    @Override public InputStream getObject(String key) {
        try {
            return s3Client.getObject(GetObjectRequest.builder().bucket(properties.getBucket()).key(key).build());
        } catch (RuntimeException e) {
            throw new StorageAccessException("getObject", key, e);
        }
    }

    @Override public void deleteObject(String key) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(properties.getBucket()).key(key).build());
        } catch (RuntimeException e) {
            throw new StorageAccessException("deleteObject", key, e);
        }
    }

    @Override public void deleteObjects(List<String> keys) {
        if (keys.isEmpty()) return;
        try {
            for (int start = 0; start < keys.size(); start += 1000) {
                List<ObjectIdentifier> ids = keys.subList(start, Math.min(start + 1000, keys.size())).stream()
                        .map(key -> ObjectIdentifier.builder().key(key).build()).toList();
                DeleteObjectsResponse response = s3Client.deleteObjects(DeleteObjectsRequest.builder()
                        .bucket(properties.getBucket())
                        .delete(Delete.builder().objects(ids).build())
                        .build());
                // S3 can return HTTP 200 with per-object failures. Earlier successful deletes
                // cannot be rolled back; report failure so callers can retry idempotently.
                if (!response.errors().isEmpty()) {
                    String codes = response.errors().stream()
                            .map(error -> error.code() == null ? "unknown" : error.code())
                            .distinct().limit(5).collect(Collectors.joining(", "));
                    throw new IllegalStateException("S3 batch deletion reported " + response.errors().size()
                            + " failures; codes=" + codes);
                }
            }
        } catch (RuntimeException e) {
            throw new StorageAccessException("deleteObjects", String.join(",", keys), e);
        }
    }

    @Override public boolean exists(String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(properties.getBucket()).key(key).build());
            return true;
        }
        catch (NoSuchKeyException e) { return false; }
        catch (S3Exception e) {
            if (e.statusCode() == 404) return false;
            throw new StorageAccessException("exists", key, e);
        }
        catch (RuntimeException e) { throw new StorageAccessException("exists", key, e); }
    }

    @Override public ObjectMetadata getMetadata(String key) {
        try {
            HeadObjectResponse resp = s3Client.headObject(HeadObjectRequest.builder().bucket(properties.getBucket()).key(key).build());
            return new ObjectMetadata(resp.contentLength(), resp.contentType(), resp.lastModified());
        } catch (RuntimeException e) {
            throw new StorageAccessException("getMetadata", key, e);
        }
    }

    @Override
    public Optional<StoragePrefixUsage> listPrefixUsage(String prefix, int maxObjects) {
        if (prefix == null || prefix.isBlank() || maxObjects < 1) {
            throw new IllegalArgumentException("A prefix and positive object limit are required");
        }
        long bytes = 0;
        long objects = 0;
        String continuationToken = null;
        int pages = 0;
        try {
            while (objects < maxObjects && pages < 100) {
                var response = s3Client.listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(properties.getBucket())
                        .prefix(prefix)
                        .continuationToken(continuationToken)
                        .maxKeys((int) Math.min(1000L, maxObjects - objects))
                        .build());
                pages++;
                for (S3Object object : response.contents()) {
                    bytes = Math.addExact(bytes, object.size());
                    objects++;
                }
                if (!response.isTruncated()) {
                    return Optional.of(new StoragePrefixUsage(bytes, objects, true));
                }
                String nextToken = response.nextContinuationToken();
                if (nextToken == null || nextToken.isBlank() || nextToken.equals(continuationToken)) {
                    return Optional.of(new StoragePrefixUsage(bytes, objects, false));
                }
                continuationToken = nextToken;
            }
            return Optional.of(new StoragePrefixUsage(bytes, objects, false));
        } catch (NoSuchBucketException exception) {
            if (properties.isAutoCreateBucket() && !bucketPrepared) {
                return Optional.of(new StoragePrefixUsage(0, 0, true));
            }
            throw new StorageAccessException("listPrefixUsage", prefix, exception);
        } catch (RuntimeException exception) {
            throw new StorageAccessException("listPrefixUsage", prefix, exception);
        }
    }

    @Override
    public String generatePresignedUrl(String key, Duration expiry, String downloadFilename) {
        Duration signatureDuration = expiry != null ? expiry : properties.getPresignExpiry();
        String contentDisposition = downloadFilename == null || downloadFilename.isBlank()
                ? "attachment"
                : "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(downloadFilename, StandardCharsets.UTF_8)
                    .replace("+", "%20");
        try {
            PresignedGetObjectRequest request = s3Presigner.presignGetObject(
                GetObjectPresignRequest.builder()
                    .signatureDuration(signatureDuration)
                    .getObjectRequest(GetObjectRequest.builder()
                        .bucket(properties.getBucket())
                        .key(key)
                        .responseContentDisposition(contentDisposition)
                        .build())
                    .build()
            );
            return request.url().toString();
        } catch (RuntimeException e) {
            throw new StorageAccessException("generatePresignedUrl", key, e);
        }
    }

    @Override
    public Optional<PresignedUpload> presignPut(String key, Duration expiry) {
        try {
            // A signed PUT is sent by the worker directly to S3, so no Java putObject call
            // can create a missing bucket before the first GitHub sync upload.
            ensureBucketPrepared();
            PresignedPutObjectRequest request = s3UploadPresigner.presignPutObject(
                    PutObjectPresignRequest.builder()
                            .signatureDuration(expiry)
                            .putObjectRequest(PutObjectRequest.builder()
                                    .bucket(properties.getBucket())
                                    .key(key)
                                    .contentType("application/zip")
                                    .build())
                            .build());
            Map<String, String> headers = request.signedHeaders().entrySet().stream()
                    .filter(entry -> !entry.getKey().equalsIgnoreCase("host"))
                    .collect(Collectors.toMap(Map.Entry::getKey,
                            entry -> String.join(",", entry.getValue())));
            return Optional.of(new PresignedUpload(request.url().toString(), headers));
        } catch (RuntimeException exception) {
            throw new StorageAccessException("presignPut", key, exception);
        }
    }
}
