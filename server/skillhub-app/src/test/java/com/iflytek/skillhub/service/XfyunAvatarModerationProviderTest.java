package com.iflytek.skillhub.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class XfyunAvatarModerationProviderTest {
    private final XfyunAvatarModerationProvider provider =
            new XfyunAvatarModerationProvider("app", "key", "secret", new ObjectMapper());

    @Test
    void acceptsOnlyDocumentedPassAndBlockVerdicts() throws Exception {
        assertThat(provider.parse("{\"code\":\"000000\",\"data\":{\"result\":{\"suggest\":\"pass\"}}}"))
                .isEqualTo(AvatarModerationProvider.Verdict.PASS);
        assertThat(provider.parse("{\"code\":\"000000\",\"data\":{\"result\":{\"suggest\":\"block\"}}}"))
                .isEqualTo(AvatarModerationProvider.Verdict.BLOCK);
        assertThat(provider.parse("{\"code\":\"000000\",\"data\":{\"result\":{\"suggest\":\"review\"}}}"))
                .isEqualTo(AvatarModerationProvider.Verdict.ERROR);
        assertThat(provider.parse("{\"code\":\"100001\"}"))
                .isEqualTo(AvatarModerationProvider.Verdict.ERROR);
    }
}
