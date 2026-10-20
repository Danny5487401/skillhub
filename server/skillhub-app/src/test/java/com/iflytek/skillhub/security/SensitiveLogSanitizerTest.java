package com.iflytek.skillhub.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class SensitiveLogSanitizerTest {

    private final SensitiveLogSanitizer sanitizer = new SensitiveLogSanitizer();

    @Test
    void shouldOmitAllQueryParametersFromLoggedRequestTarget() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/skills");
        request.setQueryString("returnTo=%2Fdashboard&token=abc123&custom=skill-content-marker");

        assertThat(sanitizer.sanitizeRequestTarget(request)).isEqualTo("/api/v1/skills");
    }
}
