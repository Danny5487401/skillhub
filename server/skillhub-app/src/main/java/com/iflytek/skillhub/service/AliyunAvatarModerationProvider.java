package com.iflytek.skillhub.service;

import com.aliyun.green20220302.Client;
import com.aliyun.green20220302.models.DescribeUploadTokenResponse;
import com.aliyun.green20220302.models.DescribeUploadTokenResponseBody.DescribeUploadTokenResponseBodyData;
import com.aliyun.green20220302.models.ImageModerationRequest;
import com.aliyun.green20220302.models.ImageModerationResponse;
import com.aliyun.green20220302.models.ImageModerationResponseBody;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.PutObjectRequest;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Uses Alibaba Cloud's short-lived private upload token; never exposes a public candidate URL. */
@Component
public class AliyunAvatarModerationProvider implements AvatarModerationProvider {
    private static final String ENDPOINT = "green-cip.cn-shanghai.aliyuncs.com";
    private final String accessKeyId;
    private final String accessKeySecret;
    private final ObjectMapper mapper;

    public AliyunAvatarModerationProvider(
            @Value("${skillhub.avatar.moderation.aliyun.access-key-id:}") String accessKeyId,
            @Value("${skillhub.avatar.moderation.aliyun.access-key-secret:}") String accessKeySecret,
            ObjectMapper mapper) {
        this.accessKeyId = accessKeyId;
        this.accessKeySecret = accessKeySecret;
        this.mapper = mapper;
    }

    @Override public String code() { return "aliyun"; }

    @Override public boolean configured() { return !accessKeyId.isBlank() && !accessKeySecret.isBlank(); }

    @Override public Verdict moderate(byte[] normalizedPng) {
        if (!configured()) {
            return Verdict.ERROR;
        }
        OSS oss = null;
        try {
            Config config = new Config();
            config.setAccessKeyId(accessKeyId);
            config.setAccessKeySecret(accessKeySecret);
            config.setEndpoint(ENDPOINT);
            Client client = new Client(config);
            DescribeUploadTokenResponse tokenResponse = client.describeUploadToken();
            if (tokenResponse == null || tokenResponse.getBody() == null
                    || !Objects.equals(tokenResponse.getBody().getCode(), 200)) {
                return Verdict.ERROR;
            }
            DescribeUploadTokenResponseBodyData token = tokenResponse.getBody().getData();
            if (token == null || token.getBucketName() == null || token.getFileNamePrefix() == null
                    || token.getOssInternetEndPoint() == null || token.getSecurityToken() == null) {
                return Verdict.ERROR;
            }
            oss = new OSSClientBuilder().build(token.getOssInternetEndPoint(), token.getAccessKeyId(),
                    token.getAccessKeySecret(), token.getSecurityToken());
            String objectName = token.getFileNamePrefix() + UUID.randomUUID() + ".png";
            oss.putObject(new PutObjectRequest(token.getBucketName(), objectName,
                    new ByteArrayInputStream(normalizedPng)));

            ImageModerationRequest request = new ImageModerationRequest();
            request.setService("profilePhotoCheck");
            request.setServiceParameters(mapper.writeValueAsString(Map.of(
                    "ossBucketName", token.getBucketName(),
                    "ossObjectName", objectName,
                    "dataId", UUID.randomUUID().toString())));
            ImageModerationResponse response = client.imageModerationWithOptions(request, new RuntimeOptions());
            return classify(response);
        } catch (Exception failure) {
            // SDK exception text may include credential or temporary upload details.
            return Verdict.ERROR;
        } finally {
            if (oss != null) {
                oss.shutdown();
            }
        }
    }

    Verdict classify(ImageModerationResponse response) {
        if (response == null || !Objects.equals(response.getStatusCode(), 200)) {
            return Verdict.ERROR;
        }
        ImageModerationResponseBody body = response.getBody();
        if (body == null || !Objects.equals(body.getCode(), 200) || body.getData() == null
                || body.getData().getResult() == null || body.getData().getResult().isEmpty()) {
            return Verdict.ERROR;
        }
        if (body.getData().getRiskLevel() == null) {
            return Verdict.ERROR;
        }
        // Official guidance treats medium as requiring human review. BLOCK keeps the image
        // private and lets the owner request the platform's exceptional manual review.
        return switch (body.getData().getRiskLevel()) {
            case "none", "low" -> Verdict.PASS;
            case "medium", "high" -> Verdict.BLOCK;
            default -> Verdict.ERROR;
        };
    }
}
