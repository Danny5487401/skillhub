package com.iflytek.skillhub.storage;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Real object probe shared by every S3-compatible deployment. Never prints signed URLs. */
final class StorageContractProbe {
    private final ObjectStorageService storage;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    StorageContractProbe(ObjectStorageService storage) {
        this.storage = storage;
    }

    void verify() throws Exception {
        String prefix = "skillhub-diagnostics/" + UUID.randomUUID() + "/";
        System.out.println("Diagnostic object prefix: " + prefix);
        List<String> keys = new ArrayList<>();
        Exception failure = null;
        try {
            byte[] content = new byte[256 * 1024 + 1];
            new Random(937).nextBytes(content);
            String key = prefix + "中文 空格+%?#/package.zip";
            keys.add(key);
            var sourceFile = java.nio.file.Files.createTempFile("skillhub-contract-", ".zip");
            try {
                java.nio.file.Files.write(sourceFile, content);
                try (var source = java.nio.file.Files.newInputStream(sourceFile)) {
                    storage.putObject(key, source, content.length, "application/zip");
                }
            } finally {
                java.nio.file.Files.deleteIfExists(sourceFile);
            }
            require(storage.exists(key), "uploaded object missing");
            try (var input = storage.getObject(key)) {
                require(Arrays.equals(input.readAllBytes(), content), "object bytes differ");
            }
            ObjectMetadata metadata = storage.getMetadata(key);
            require(metadata.size() == content.length, "object size differs");
            require("application/zip".equals(metadata.contentType()), "content type differs");
            String signedGet = storage.generatePresignedUrl(key, Duration.ofMinutes(1), "中文 package.zip");
            var download = send(HttpRequest.newBuilder(URI.create(signedGet)).GET());
            require(download.statusCode() == 200 && Arrays.equals(download.body(), content), "signed GET failed");
            require(download.headers().firstValue("Content-Disposition").orElse("").contains("filename*="),
                    "download filename missing");
            var tampered = send(HttpRequest.newBuilder(tamperSignature(signedGet)).GET());
            require(tampered.statusCode() == 403, "tampered signature was not rejected");
            var anonymous = send(HttpRequest.newBuilder(URI.create(signedGet.split("\\?")[0])).GET());
            require(anonymous.statusCode() == 403, "anonymous private object read was not rejected");
            String expiringGet = storage.generatePresignedUrl(key, Duration.ofSeconds(1), null);
            TimeUnit.SECONDS.sleep(3);
            require(send(HttpRequest.newBuilder(URI.create(expiringGet)).GET()).statusCode() == 403,
                    "expired signature was not rejected");
            String putKey = prefix + "staging.zip";
            keys.add(putKey);
            PresignedUpload upload = storage.presignPut(putKey, Duration.ofMinutes(1)).orElseThrow();
            String signedHeaderNames = Arrays.stream(URI.create(upload.url()).getRawQuery().split("&"))
                    .filter(field -> field.startsWith("X-Amz-SignedHeaders="))
                    .map(field -> URLDecoder.decode(field.substring(field.indexOf('=') + 1),
                            StandardCharsets.UTF_8))
                    .findFirst().orElseThrow();
            System.out.println("PUT signature-bound header names: " + signedHeaderNames);
            var request = HttpRequest.newBuilder(URI.create(upload.url()));
            upload.headers().forEach(request::header);
            // SDK request headers (e.g. Content-Type) need not all be bound into SigV4.
            // Only require rejection for omitted headers listed in X-Amz-SignedHeaders.
            if (Arrays.stream(signedHeaderNames.split(";")).anyMatch(name -> !name.equalsIgnoreCase("host"))) {
                var missingHeaders = send(HttpRequest.newBuilder(URI.create(upload.url()))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(content)));
                require(missingHeaders.statusCode() == 400 || missingHeaders.statusCode() == 403,
                        "missing signature-bound PUT headers were not rejected");
                require(!storage.exists(putKey), "rejected PUT wrote an object");
                System.out.println("Missing-header PUT rejected with HTTP " + missingHeaders.statusCode());
            }
            var badPutRequest = HttpRequest.newBuilder(tamperSignature(upload.url()));
            upload.headers().forEach(badPutRequest::header);
            var badPut = send(badPutRequest.PUT(HttpRequest.BodyPublishers.ofByteArray(content)));
            require(badPut.statusCode() == 403, "tampered PUT signature was not rejected");
            require(!storage.exists(putKey), "tampered PUT wrote an object");
            var uploaded = send(request.PUT(HttpRequest.BodyPublishers.ofByteArray(content)));
            require(uploaded.statusCode() >= 200 && uploaded.statusCode() < 300, "signed PUT failed");
            try (var input = storage.getObject(putKey)) {
                require(Arrays.equals(input.readAllBytes(), content), "signed PUT bytes differ");
            }
            require("application/zip".equals(storage.getMetadata(putKey).contentType()),
                    "signed PUT content type differs");
            var usage = storage.listPrefixUsage(prefix, 10).orElseThrow();
            require(usage.complete() && usage.objectCount() == 2 && usage.bytes() == content.length * 2L,
                    "prefix usage differs");
            var limited = storage.listPrefixUsage(prefix, 1).orElseThrow();
            require(!limited.complete() && limited.objectCount() == 1, "truncated usage reported complete");
            storage.deleteObject(putKey);
            require(!storage.exists(putKey), "single delete failed");
            storage.deleteObjects(keys);
            require(!storage.exists(key), "batch delete failed");
        } catch (Exception exception) {
            failure = exception;
            throw exception;
        } finally {
            // Delete only keys created by this invocation, even when a write timed out.
            Exception cleanupFailure = null;
            for (String key : keys) {
                try {
                    storage.deleteObject(key);
                } catch (Exception exception) {
                    if (cleanupFailure == null) cleanupFailure = new IOException("Diagnostic cleanup failed for " + prefix);
                    cleanupFailure.addSuppressed(exception);
                }
            }
            if (cleanupFailure != null) {
                if (failure != null) failure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
    }

    static URI tamperSignature(String signedUrl) {
        String marker = "X-Amz-Signature=";
        int start = signedUrl.indexOf(marker);
        if (start < 0) throw new IllegalArgumentException("Expected a SigV4 query signature");
        start += marker.length();
        // Change the digest while retaining valid signature syntax and length.
        char changed = signedUrl.charAt(start) == '0' ? '1' : '0';
        return URI.create(signedUrl.substring(0, start) + changed + signedUrl.substring(start + 1));
    }

    private HttpResponse<byte[]> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
