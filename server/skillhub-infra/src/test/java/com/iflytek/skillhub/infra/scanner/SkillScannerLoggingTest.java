package com.iflytek.skillhub.infra.scanner;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.iflytek.skillhub.domain.security.SecurityScanRequest;
import com.iflytek.skillhub.infra.http.HttpClient;
import com.iflytek.skillhub.infra.http.HttpClientException;
import com.iflytek.skillhub.infra.http.WebClientHttpClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillScannerLoggingTest {

    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(SkillScannerService.class);
    private final Logger adapterLogger = (Logger) LoggerFactory.getLogger(SkillScannerAdapter.class);
    private ListAppender<ILoggingEvent> appender;

    @AfterEach
    void tearDown() {
        if (appender != null) {
            serviceLogger.detachAppender(appender);
            adapterLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void scanUpload_doesNotPropagateScannerErrorBody() {
        String marker = "sensitive-body-".repeat(100);
        HttpClient httpClient = new ThrowingHttpClient(new HttpClientException(500, marker));
        SkillScannerService service = new SkillScannerService(
                httpClient,
                "http://scanner.test",
                "/scan-upload",
                "/health"
        );
        attachAppender(serviceLogger);

        assertThatThrownBy(() -> service.scanUpload(Path.of("/tmp/demo.zip"), ScanOptions.disabled()))
                .isInstanceOf(HttpClientException.class)
                .hasMessageNotContaining(marker);

        List<String> messages = loggedMessages();
        assertThat(messages).anyMatch(message -> message.contains("Scanner API error: status=500"));
        assertThat(messages).noneMatch(message -> message.contains("sensitive-body-"));
    }

    @Test
    void scanUpload_doesNotPropagateNetworkCause() {
        String marker = "private-response-body";
        HttpClient httpClient = new ThrowingHttpClient(new HttpClientException(
                "Scanner connection failed", new IllegalStateException(marker)));
        SkillScannerService service = new SkillScannerService(
                httpClient, "http://scanner.test", "/scan-upload", "/health");
        attachAppender(serviceLogger);

        assertThatThrownBy(() -> service.scanUpload(Path.of("/tmp/demo.zip"), ScanOptions.disabled()))
                .isInstanceOf(HttpClientException.class)
                .hasMessageNotContaining(marker)
                .satisfies(error -> assertThat(error.getCause()).isNull());
        assertThat(loggedMessages()).noneMatch(message -> message.contains(marker));
    }

    @Test
    void scan_logsOnlyVerdictAndCountsEvenAtDebug() {
        String marker = "private-skill-body-marker";
        StubSkillScannerService service = new StubSkillScannerService();
        service.directoryResponse = new SkillScannerApiResponse(
                "scan-" + marker,
                "skill-" + marker,
                false,
                "HIGH",
                1,
                List.of(new SkillScannerApiResponse.Finding(
                        "ID-1",
                        "RULE-" + marker,
                        "HIGH",
                        "code-execution",
                        "Danger title",
                        "Very sensitive description " + marker,
                        "src/" + marker + ".py",
                        7,
                        "eval(secret)",
                        "Use safe api",
                        "static",
                        Map.of("token", "secret")
                )),
                1.0,
                "2026-03-23T00:00:00"
        );
        SkillScannerAdapter adapter = new SkillScannerAdapter(service, "local", ScanOptions.disabled());
        attachAppender(adapterLogger);
        adapterLogger.setLevel(Level.DEBUG);

        adapter.scan(new SecurityScanRequest("task-1", 1L, "/tmp/skill", Map.of()));

        List<String> messages = loggedMessages();
        assertThat(messages).anyMatch(message -> message.contains("Mapped scanner response: verdict=DANGEROUS"));
        assertThat(messages).noneMatch(message -> message.contains(marker));
        assertThat(messages).noneMatch(message -> message.contains("eval(secret)"));
    }

    @Test
    void scan_doesNotLogOrRetainScannerErrorBody() {
        String marker = "private-scanner-error-body";
        StubSkillScannerService service = new StubSkillScannerService();
        service.directoryException = new HttpClientException(503, marker);
        SkillScannerAdapter adapter = new SkillScannerAdapter(service, "local", ScanOptions.disabled());
        attachAppender(adapterLogger);

        assertThatThrownBy(() -> adapter.scan(new SecurityScanRequest(
                "task-1", 1L, "/tmp/skill", Map.of())))
                .isInstanceOf(SecurityScanException.class)
                .hasMessageNotContaining(marker)
                .satisfies(error -> assertThat(error.getCause()).hasMessageNotContaining(marker));
        assertThat(loggedMessages()).noneMatch(message -> message.contains(marker));
    }

    @Test
    void realHttpScannerFailureAndResponseStayOutOfLogs(@TempDir Path tempDir) throws Exception {
        String marker = "scanner-private-body-marker";
        AtomicBoolean fail = new AtomicBoolean(true);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/scan-upload", exchange -> {
            exchange.getRequestBody().readAllBytes();
            boolean unavailable = fail.get();
            String response = unavailable
                    ? "{\"detail\":\"" + marker + "\"}"
                    : "{\"scan_id\":\"" + marker + "\",\"skill_name\":\"" + marker
                    + "\",\"is_safe\":true,\"max_severity\":\"LOW\",\"findings_count\":0,"
                    + "\"findings\":[],\"scan_duration_seconds\":0.01}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(unavailable ? 503 : 200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.createContext("/health", exchange -> {
            byte[] bytes = marker.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> allLogs = new ListAppender<>();
        allLogs.start();
        root.addAppender(allLogs);
        try {
            Path archive = Files.writeString(tempDir.resolve("demo.zip"), "fixture");
            SkillScannerService service = new SkillScannerService(
                    new WebClientHttpClient(WebClient.builder().build()),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "/scan-upload", "/health");
            SkillScannerAdapter adapter = new SkillScannerAdapter(
                    service, "upload", ScanOptions.disabled());
            SecurityScanRequest request = new SecurityScanRequest("task-1", 1L,
                    archive.toString(), Map.of());

            assertThat(service.isHealthy()).isFalse();
            assertThatThrownBy(() -> adapter.scan(request))
                    .isInstanceOf(SecurityScanException.class)
                    .hasMessage("Security scan request failed")
                    .satisfies(error -> {
                        assertThat(error.getCause()).hasMessageNotContaining(marker);
                        assertThat(((SecurityScanException) error).isScannerUnavailable()).isTrue();
                    });
            fail.set(false);
            assertThat(adapter.scan(request).verdict().name()).isEqualTo("SAFE");

            String output = allLogs.list.stream().map(event -> event.getFormattedMessage()
                    + (event.getThrowableProxy() == null ? ""
                    : ThrowableProxyUtil.asString(event.getThrowableProxy())))
                    .collect(Collectors.joining("\n"));
            assertThat(output).doesNotContain(marker);
        } finally {
            root.detachAppender(allLogs);
            allLogs.stop();
            server.stop(0);
        }
    }

    private void attachAppender(Logger logger) {
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

    private static final class ThrowingHttpClient implements HttpClient {
        private final HttpClientException failure;

        private ThrowingHttpClient(HttpClientException failure) {
            this.failure = failure;
        }

        @Override
        public <T> T get(String uri, Class<T> responseType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T post(String uri, Object body, Class<T> responseType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T post(String uri, Object body, HttpHeaders headers, Class<T> responseType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T postMultipart(String uri, MultiValueMap<String, Object> parts, Class<T> responseType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T postMultipart(String uri,
                                   MultiValueMap<String, Object> parts,
                                   HttpHeaders headers,
                                   Class<T> responseType) {
            throw failure;
        }

        @Override
        public boolean isHealthy(String healthUri) {
            return false;
        }
    }

    private static final class StubSkillScannerService extends SkillScannerService {
        private SkillScannerApiResponse directoryResponse;
        private HttpClientException directoryException;

        private StubSkillScannerService() {
            super(new NoOpHttpClient(), "http://scanner.test", "/scan-upload", "/health");
        }

        @Override
        public SkillScannerApiResponse scanDirectory(String skillDirectory, ScanOptions options) {
            if (directoryException != null) {
                throw directoryException;
            }
            return directoryResponse;
        }
    }

    private static final class NoOpHttpClient implements HttpClient {
        @Override
        public <T> T get(String uri, Class<T> responseType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T post(String uri, Object body, Class<T> responseType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T post(String uri, Object body, HttpHeaders headers, Class<T> responseType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T postMultipart(String uri, MultiValueMap<String, Object> parts, Class<T> responseType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T postMultipart(String uri,
                                   MultiValueMap<String, Object> parts,
                                   HttpHeaders headers,
                                   Class<T> responseType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isHealthy(String healthUri) {
            return false;
        }
    }
}
