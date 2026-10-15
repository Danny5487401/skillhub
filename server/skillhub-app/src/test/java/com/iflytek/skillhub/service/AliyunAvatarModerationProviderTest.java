package com.iflytek.skillhub.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.aliyun.green20220302.models.ImageModerationResponse;
import com.aliyun.green20220302.models.ImageModerationResponseBody;
import com.aliyun.green20220302.models.ImageModerationResponseBody.ImageModerationResponseBodyData;
import com.aliyun.green20220302.models.ImageModerationResponseBody.ImageModerationResponseBodyDataResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class AliyunAvatarModerationProviderTest {
    private final AliyunAvatarModerationProvider provider =
            new AliyunAvatarModerationProvider("key", "secret", new ObjectMapper());

    @Test
    void recordsActualServiceAndApiWithoutInventingModelVersion() {
        assertThat(provider.context().serviceId()).isEqualTo("profilePhotoCheck");
        assertThat(provider.context().apiVersion()).isEqualTo("2022-03-02");
        assertThat(provider.context().policyRevision()).contains("PASS", "BLOCK", "ERROR");
        assertThat(provider.context().modelVersion()).isNull();
    }

    @Test
    void classifiesDocumentedRiskLevelsAndFailsClosedOnUnknown() {
        assertThat(provider.classify(response("none"))).isEqualTo(AvatarModerationProvider.Verdict.PASS);
        assertThat(provider.classify(response("low"))).isEqualTo(AvatarModerationProvider.Verdict.PASS);
        assertThat(provider.classify(response("medium"))).isEqualTo(AvatarModerationProvider.Verdict.BLOCK);
        assertThat(provider.classify(response("high"))).isEqualTo(AvatarModerationProvider.Verdict.BLOCK);
        assertThat(provider.classify(response(null))).isEqualTo(AvatarModerationProvider.Verdict.ERROR);
        assertThat(provider.classify(response("unknown"))).isEqualTo(AvatarModerationProvider.Verdict.ERROR);
        assertThat(provider.classify(response("none").setStatusCode(500)))
                .isEqualTo(AvatarModerationProvider.Verdict.ERROR);
        assertThat(provider.classify(response("none").setBody(
                new ImageModerationResponseBody().setCode(500))))
                .isEqualTo(AvatarModerationProvider.Verdict.ERROR);
        assertThat(provider.classify(response("none").setBody(
                new ImageModerationResponseBody().setCode(200).setData(
                        new ImageModerationResponseBodyData().setRiskLevel("none").setResult(List.of())))))
                .isEqualTo(AvatarModerationProvider.Verdict.ERROR);
    }

    private ImageModerationResponse response(String level) {
        ImageModerationResponseBodyData data = new ImageModerationResponseBodyData()
                .setRiskLevel(level)
                .setResult(List.of(new ImageModerationResponseBodyDataResult().setLabel("nonLabel")));
        return new ImageModerationResponse().setStatusCode(200)
                .setBody(new ImageModerationResponseBody().setCode(200).setData(data));
    }
}
