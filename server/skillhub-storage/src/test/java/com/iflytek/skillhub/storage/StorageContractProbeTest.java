package com.iflytek.skillhub.storage;

import java.io.ByteArrayInputStream;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StorageContractProbeTest {
    @Test
    void signatureTamperingChangesOnlyDigestContentNotItsFormat() {
        String signature = "a".repeat(64);
        String url = "https://storage.example.test/bucket/key?X-Amz-Signature=" + signature + "&X-Amz-Expires=60";
        String tampered = StorageContractProbe.tamperSignature(url).toString();

        assertThat(tampered.length()).isEqualTo(url.length());
        assertThat(tampered).startsWith("https://storage.example.test/bucket/key?X-Amz-Signature=")
                .endsWith("&X-Amz-Expires=60");
        assertThat(IntStream.range(0, url.length())
                .filter(i -> url.charAt(i) != tampered.charAt(i)).count()).isEqualTo(1);
    }

    @Test
    void writeFailureStillCleansOnlyTheAttemptedObject() {
        var storage = mock(ObjectStorageService.class);
        var writeFailure = new StorageAccessException("putObject", "object", new IllegalStateException());
        doThrow(writeFailure).when(storage).putObject(anyString(), any(InputStream.class), anyLong(), anyString());

        assertThatThrownBy(() -> new StorageContractProbe(storage).verify()).isSameAs(writeFailure);

        var key = ArgumentCaptor.forClass(String.class);
        verify(storage).deleteObject(key.capture());
        assertThat(key.getValue()).startsWith("skillhub-diagnostics/").endsWith("package.zip");
    }

    @Test
    void cleanupFailureIsNotLostWhenPrimaryOperationAlsoFails() {
        var storage = mock(ObjectStorageService.class);
        var writeFailure = new IllegalStateException("write failed");
        doThrow(writeFailure).when(storage).putObject(anyString(), any(InputStream.class), anyLong(), anyString());
        doThrow(new IllegalStateException("delete failed")).when(storage).deleteObject(anyString());

        assertThatThrownBy(() -> new StorageContractProbe(storage).verify()).isSameAs(writeFailure);
        assertThat(writeFailure.getSuppressed()).hasSize(1);
        assertThat(writeFailure.getSuppressed()[0].getMessage()).startsWith("Diagnostic cleanup failed for skillhub-diagnostics/");
    }

    @Test
    void probeDetectsCorruptedObjectBytes() {
        var storage = mock(ObjectStorageService.class);
        when(storage.exists(anyString())).thenReturn(true);
        when(storage.getObject(anyString())).thenReturn(new ByteArrayInputStream(new byte[]{0}));

        assertThatThrownBy(() -> new StorageContractProbe(storage).verify())
                .isInstanceOf(IllegalStateException.class).hasMessage("object bytes differ");
        verify(storage).deleteObject(anyString());
    }

    @Test
    void probeDetectsFalseWriteSuccess() {
        var storage = mock(ObjectStorageService.class);
        when(storage.exists(anyString())).thenReturn(false);

        assertThatThrownBy(() -> new StorageContractProbe(storage).verify())
                .isInstanceOf(IllegalStateException.class).hasMessage("uploaded object missing");
        verify(storage).deleteObject(anyString());
    }
}
