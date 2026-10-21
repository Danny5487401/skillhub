package com.iflytek.skillhub.storage;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class S3StorageStagingTest {
    @Test
    void stagedBodyRemainsOwnerOnlyDuringCopyAndUpload() throws Exception {
        Path probe = Files.createTempFile("skillhub-s3-permission-probe-", ".tmp");
        try {
            assumeTrue(Files.getFileStore(probe).supportsFileAttributeView("posix"),
                    "Owner-only POSIX permissions require a POSIX file system");
        } finally {
            Files.deleteIfExists(probe);
        }
        var staged = new AtomicReference<Path>();
        S3Client client = mock(S3Client.class);
        S3StorageService service = service(client);
        try (var files = trackStagedBody(staged)) {
            when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(invocation -> {
                assertOwnerOnly(staged.get());
                return PutObjectResponse.builder().build();
            });
            InputStream input = new InputStream() {
                private boolean consumed;

                @Override
                public int read() throws IOException {
                    assertOwnerOnly(staged.get());
                    if (consumed) return -1;
                    consumed = true;
                    return 42;
                }
            };
            service.putObject("private/package.zip", input, 1, "application/zip");
            assertThat(staged.get()).doesNotExist();
        } finally {
            if (staged.get() != null) Files.deleteIfExists(staged.get());
        }
    }

    @Test
    void stagingIOExceptionRemovesPartialFile() throws Exception {
        assertStagingFailureCleaned(new IOException("read failed"));
    }

    @Test
    void stagingRuntimeExceptionRemovesPartialFile() throws Exception {
        assertStagingFailureCleaned(new IllegalStateException("read failed"));
    }

    @Test
    void stagingErrorRemovesPartialFile() throws Exception {
        assertStagingFailureCleaned(new AssertionError("read failed"));
    }

    @Test
    void stagingCleanupFailureDoesNotReplaceReadFailure() throws Exception {
        assertCleanupFailureSuppressed(new IOException("read failed"), new IOException("delete failed"));
    }

    @Test
    void stagingCleanupFailureDoesNotReplaceRuntimeFailure() throws Exception {
        assertCleanupFailureSuppressed(new IllegalStateException("read failed"), new IOException("delete failed"));
    }

    @Test
    void stagingRuntimeCleanupFailureDoesNotReplaceReadFailure() throws Exception {
        assertCleanupFailureSuppressed(new IOException("read failed"), new IllegalStateException("delete failed"));
    }

    private void assertCleanupFailureSuppressed(Exception primary, Exception cleanup) throws Exception {
        var staged = new AtomicReference<Path>();
        S3Client client = mock(S3Client.class);
        S3StorageService service = service(client);
        try (var files = trackStagedBody(staged)) {
            InputStream input = new InputStream() {
                @Override
                public int read() throws IOException {
                    files.when(() -> Files.deleteIfExists(staged.get())).thenThrow(cleanup);
                    if (primary instanceof IOException io) throw io;
                    throw (RuntimeException) primary;
                }
            };
            assertThatThrownBy(() -> service.putObject("private/package.zip", input, 1, "application/zip"))
                    .isInstanceOf(StorageAccessException.class).hasCause(primary);
            assertThat(primary.getSuppressed()).containsExactly(cleanup);
            verifyNoInteractions(client);
        } finally {
            if (staged.get() != null) Files.deleteIfExists(staged.get());
        }
    }

    @Test
    void s3FailureRemovesStagedFile() throws Exception {
        var staged = new AtomicReference<Path>();
        S3Client client = mock(S3Client.class);
        S3StorageService service = service(client);
        RuntimeException failure = new IllegalStateException("S3 unavailable");
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(failure);
        try (var files = trackStagedBody(staged)) {
            assertThatThrownBy(() -> service.putObject("private/package.zip",
                    new ByteArrayInputStream(new byte[]{42}), 1, "application/zip"))
                    .isInstanceOf(StorageAccessException.class).hasCause(failure);
            assertThat(staged.get()).doesNotExist();
        }
    }

    @Test
    void sizeMismatchRemovesStagedFileBeforeS3Call() throws Exception {
        var staged = new AtomicReference<Path>();
        S3Client client = mock(S3Client.class);
        S3StorageService service = service(client);
        try (var files = trackStagedBody(staged)) {
            assertThatThrownBy(() -> service.putObject("private/package.zip",
                    new ByteArrayInputStream(new byte[]{42}), 2, "application/zip"))
                    .isInstanceOf(StorageAccessException.class).hasCauseInstanceOf(IllegalArgumentException.class);
            assertThat(staged.get()).doesNotExist();
            verifyNoInteractions(client);
        }
    }

    private void assertStagingFailureCleaned(Throwable failure) throws Exception {
        var staged = new AtomicReference<Path>();
        S3Client client = mock(S3Client.class);
        S3StorageService service = service(client);
        InputStream input = new InputStream() {
            private boolean partialWritten;

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                if (!partialWritten) {
                    partialWritten = true;
                    buffer[offset] = 42;
                    return 1;
                }
                if (failure instanceof IOException io) throw io;
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw (Error) failure;
            }

            @Override
            public int read() {
                throw new AssertionError("bulk read expected");
            }
        };
        try (var files = trackStagedBody(staged)) {
            if (failure instanceof Error) {
                assertThatThrownBy(() -> service.putObject("private/package.zip", input, 2, "application/zip"))
                        .isSameAs(failure);
            } else {
                assertThatThrownBy(() -> service.putObject("private/package.zip", input, 2, "application/zip"))
                        .isInstanceOf(StorageAccessException.class).hasCause(failure);
            }
            assertThat(staged.get()).doesNotExist();
            verifyNoInteractions(client);
        } finally {
            if (staged.get() != null) Files.deleteIfExists(staged.get());
        }
    }

    private MockedStatic<Files> trackStagedBody(AtomicReference<Path> staged) throws IOException {
        MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS);
        files.when(() -> Files.createTempFile("skillhub-s3-upload-", ".tmp")).thenAnswer(invocation -> {
            Path path = (Path) invocation.callRealMethod();
            staged.set(path);
            return path;
        });
        return files;
    }

    private void assertOwnerOnly(Path path) throws IOException {
        assertThat(Files.getPosixFilePermissions(path)).containsExactlyInAnyOrder(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    private S3StorageService service(S3Client client) {
        var properties = new S3StorageProperties();
        properties.setBucket("private-test-bucket");
        properties.setAutoCreateBucket(false);
        var presigner = mock(S3Presigner.class);
        var service = new S3StorageService(properties) {
            @Override
            protected S3Client buildS3Client(ApacheHttpClient.Builder builder) {
                return client;
            }

            @Override
            S3Presigner buildPresigner() {
                return presigner;
            }
        };
        service.init();
        return service;
    }
}
