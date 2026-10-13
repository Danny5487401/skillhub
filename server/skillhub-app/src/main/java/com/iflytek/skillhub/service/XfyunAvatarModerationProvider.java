package com.iflytek.skillhub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Xfyun image compliance API v2. Its documented query contains a secret: never log URI or exceptions. */
@Component
public class XfyunAvatarModerationProvider implements AvatarModerationProvider {
    private static final String ENDPOINT = "https://audit.iflyaisol.com/audit/v2/image";
    private static final DateTimeFormatter UTC_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");

    private final String appId;
    private final String accessKeyId;
    private final String accessKeySecret;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public XfyunAvatarModerationProvider(
            @Value("${skillhub.avatar.moderation.xfyun.app-id:}") String appId,
            @Value("${skillhub.avatar.moderation.xfyun.access-key-id:}") String accessKeyId,
            @Value("${skillhub.avatar.moderation.xfyun.access-key-secret:}") String accessKeySecret,
            ObjectMapper mapper) {
        this.appId = appId;
        this.accessKeyId = accessKeyId;
        this.accessKeySecret = accessKeySecret;
        this.mapper = mapper;
    }

    @Override public Context context() {
        return new Context("image", "v2", "xfyun-image/1:code=000000;pass=PASS;block=BLOCK;other=ERROR", null);
    }

    @Override public String code() { return "xfyun"; }

    @Override public boolean configured() {
        return !appId.isBlank() && !accessKeyId.isBlank() && !accessKeySecret.isBlank();
    }

    @Override public Verdict moderate(byte[] normalizedPng) {
        if (!configured()) {
            return Verdict.ERROR;
        }
        try {
            TreeMap<String, String> params = new TreeMap<>(Map.of(
                    "accessKeyId", accessKeyId,
                    "accessKeySecret", accessKeySecret,
                    "appId", appId,
                    "modeType", "base64",
                    "utc", OffsetDateTime.now(ZoneOffset.UTC).format(UTC_FORMAT),
                    "uuid", UUID.randomUUID().toString()));
            String base = encode(params);
            params.put("signature", sign(base));
            String payload = mapper.writeValueAsString(Map.of("content", Base64.getEncoder().encodeToString(normalizedPng)));
            HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT + "?" + encode(params)))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? parse(response.body()) : Verdict.ERROR;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Verdict.ERROR;
        } catch (IOException | IllegalArgumentException | InvalidKeyException | NoSuchAlgorithmException failure) {
            // The request URI includes accessKeySecret. Do not log or propagate exception text.
            return Verdict.ERROR;
        }
    }

    Verdict parse(String response) throws IOException {
        JsonNode body = mapper.readTree(response);
        if (!"000000".equals(body.path("code").asText())) {
            return Verdict.ERROR;
        }
        return switch (body.path("data").path("result").path("suggest").asText()) {
            case "pass" -> Verdict.PASS;
            case "block" -> Verdict.BLOCK;
            default -> Verdict.ERROR;
        };
    }

    private String sign(String base) throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(accessKeySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return Base64.getEncoder().encodeToString(mac.doFinal(base.getBytes(StandardCharsets.UTF_8)));
    }

    private String encode(Map<String, String> params) {
        StringBuilder query = new StringBuilder();
        params.forEach((key, value) -> {
            if (value != null && !value.isEmpty()) {
                if (!query.isEmpty()) query.append('&');
                query.append(key).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
            }
        });
        return query.toString();
    }
}
