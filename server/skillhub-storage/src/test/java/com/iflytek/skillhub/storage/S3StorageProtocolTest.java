package com.iflytek.skillhub.storage;

import org.springframework.test.util.ReflectionTestUtils;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3StorageProtocolTest {
    @Test
    void usageFollowsContinuationTokensAndSumsEveryPage() {
        var client = mock(S3Client.class);
        when(client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().isTruncated(true).nextContinuationToken("page-2")
                        .contents(S3Object.builder().key("prefix/a").size(7L).build()).build())
                .thenReturn(ListObjectsV2Response.builder().isTruncated(false)
                        .contents(S3Object.builder().key("prefix/b").size(11L).build()).build());
        var service = service(client);

        assertThat(service.listPrefixUsage("prefix/", 10)).contains(new StoragePrefixUsage(18, 2, true));
        var requests = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(client, times(2)).listObjectsV2(requests.capture());
        assertThat(requests.getAllValues().get(0).continuationToken()).isNull();
        assertThat(requests.getAllValues().get(1).continuationToken()).isEqualTo("page-2");
        assertThat(requests.getAllValues().get(1).prefix()).isEqualTo("prefix/");
    }

    @Test
    void truncatedResponseWithoutContinuationDoesNotClaimCompleteUsage() {
        var client = mock(S3Client.class);
        when(client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().isTruncated(true)
                        .contents(S3Object.builder().size(7L).build()).build());

        assertThat(service(client).listPrefixUsage("prefix/", 10)).contains(new StoragePrefixUsage(7, 1, false));
    }

    @Test
    void permissionFailureIsNotMisreportedAsMissingObject() {
        var client = mock(S3Client.class);
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(403).build());

        assertThatThrownBy(() -> service(client).exists("private/key"))
                .isInstanceOf(StorageAccessException.class).extracting("operation").isEqualTo("exists");
    }

    private S3StorageService service(S3Client client) {
        var service = new S3StorageService(new S3StorageProperties());
        ReflectionTestUtils.setField(service, "s3Client", client);
        return service;
    }
}
