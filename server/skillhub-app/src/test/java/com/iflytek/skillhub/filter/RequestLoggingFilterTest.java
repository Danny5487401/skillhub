package com.iflytek.skillhub.filter;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.ContentCachingResponseWrapper;

class RequestLoggingFilterTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
    private ListAppender<ILoggingEvent> appender;

    @AfterEach
    void tearDown() {
        if (appender != null) {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void doFilterInternal_omitsRequestAndResponseBodies()
            throws ServletException, IOException {
        RequestLoggingFilter filter = new RequestLoggingFilter();
        String requestBody = "{\"username\":\"alice\",\"password\":\"super-secret\"}";
        String responseBody = "x".repeat(5_000);
        attachAppender();

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/test");
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        request.setContentType("application/json");
        request.setContent(requestBody.getBytes(StandardCharsets.UTF_8));

        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        FilterChain filterChain = (req, res) -> {
            req.getReader().lines().count();
            res.setContentType("application/json");
            res.getWriter().write(responseBody);
        };

        filter.doFilter(request, response, filterChain);

        List<String> loggedMessages = loggedMessages();
        assertThat(loggedMessages).anyMatch(message -> message.contains("POST /api/test"));
        assertThat(loggedMessages).noneMatch(message -> message.contains("Body:"));
        assertThat(loggedMessages).noneMatch(message -> message.contains("super-secret"));
        assertThat(loggedMessages).noneMatch(message -> message.contains("Response Body:"));
        assertThat(response.getContentAsString()).isEqualTo(responseBody);
    }

    @Test
    void doFilterInternal_skipsActuatorEndpoints()
            throws ServletException, IOException {
        RequestLoggingFilter filter = new RequestLoggingFilter();
        attachAppender();

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain filterChain = (req, res) -> {};

        filter.doFilter(request, response, filterChain);

        assertThat(loggedMessages()).noneMatch(message -> message.contains("/actuator/health"));
    }

    @Test
    void doFilterInternal_logsCoreSummaryFields()
            throws ServletException, IOException {
        RequestLoggingFilter filter = new RequestLoggingFilter();
        attachAppender();

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/skills");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain filterChain = (req, res) -> {};

        filter.doFilter(request, response, filterChain);

        assertThat(loggedMessages()).anySatisfy(message -> {
            assertThat(message).contains("GET /api/v1/skills");
            assertThat(message).contains("200");
            assertThat(message).contains("127.0.0.1");
            assertThat(message).contains("ms");
        });
        assertThat(loggedMessages()).noneMatch(message -> message.contains("Headers: {"));
    }

    @Test
    void doFilterInternal_omitsOAuthCallbackQueryParameters() throws Exception {
        RequestLoggingFilter filter = new RequestLoggingFilter();
        attachAppender();

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/feishu");
        request.setQueryString("code=authorization-code&state=csrf-state&scope=contact:user.base:readonly");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {});

        String message = loggedMessages().stream()
                .filter(entry -> entry.contains("GET /login/oauth2/code/feishu"))
                .findFirst()
                .orElseThrow();
        assertThat(message).doesNotContain("?", "code=", "state=", "scope=",
                "authorization-code", "csrf-state");
    }

    @Test
    void doFilterInternal_skipsGithubCredentialRoute() throws Exception {
        RequestLoggingFilter filter = new RequestLoggingFilter();
        attachAppender();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/admin/github-sync/credentials");
        request.addHeader("Authorization", "Bearer header-secret");
        request.setQueryString("token=credential-value&github_token=another-value&api_key=third-value"
                + "&authorization=query-secret&service_token=machine-secret&page=1");

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});

        assertThat(loggedMessages()).isEmpty();
    }

    @Test
    void doFilterInternal_omitsUntrustedQueryAndUserAgent() throws Exception {
        RequestLoggingFilter filter = new RequestLoggingFilter();
        attachAppender();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/skills");
        request.addHeader("Authorization", "Bearer synthetic-auth-secret");
        request.addHeader("User-Agent", "SKILL.md full-content-marker synthetic-agent-secret");
        request.addHeader("Content-Type", "application/json; synthetic-content-secret");
        request.setQueryString("custom=synthetic-query-secret&synthetic-key-secret=value&page=1");

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});

        String message = loggedMessages().getFirst();
        assertThat(message).contains("GET /api/v1/skills");
        assertThat(message).doesNotContain("?", "synthetic-auth-secret",
                "synthetic-agent-secret", "synthetic-content-secret", "synthetic-query-secret",
                "synthetic-key-secret",
                "full-content-marker", "page=1");
    }

    @Test
    void doFilterInternal_shouldKeepCachingWrapperForRegularApiResponses() throws Exception {
        RequestLoggingFilter filter = new RequestLoggingFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/web/notifications/unread-count");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletResponse> responseSeenByChain = new AtomicReference<>();
        FilterChain chain = (servletRequest, servletResponse) -> {
            responseSeenByChain.set(servletResponse);
            servletResponse.getWriter().write("{\"count\":1}");
        };

        filter.doFilter(request, response, chain);

        assertThat(responseSeenByChain.get()).isInstanceOf(ContentCachingResponseWrapper.class);
        assertThat(response.getContentAsString()).isEqualTo("{\"count\":1}");
    }

    private void attachAppender() {
        logger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    private List<String> loggedMessages() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
