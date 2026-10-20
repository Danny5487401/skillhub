package com.iflytek.skillhub.service;

import com.iflytek.skillhub.domain.audit.AuditDetail;
import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.dto.GithubCredentialResponse;
import com.iflytek.skillhub.dto.GithubCredentialUpsertRequest;
import com.iflytek.skillhub.githubsync.GithubCredentialCipher;
import com.iflytek.skillhub.githubsync.GithubSystemCredential;
import com.iflytek.skillhub.githubsync.GithubSystemCredentialRepository;
import com.iflytek.skillhub.observability.RequestIdAccessor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class GithubCredentialAdminAppService {
    private final GithubSystemCredentialRepository credentials;
    private final GithubCredentialCipher cipher;
    private final AuditLogService auditLogService;
    private final RequestIdAccessor requestIdAccessor;

    public GithubCredentialAdminAppService(GithubSystemCredentialRepository credentials,
            GithubCredentialCipher cipher, AuditLogService auditLogService,
            RequestIdAccessor requestIdAccessor) {
        this.credentials = credentials;
        this.cipher = cipher;
        this.auditLogService = auditLogService;
        this.requestIdAccessor = requestIdAccessor;
    }

    public List<GithubCredentialResponse> list() {
        return credentials.list().stream().map(this::toResponse).toList();
    }

    @Transactional
    public GithubCredentialResponse create(GithubCredentialUpsertRequest body, String actorId,
                                           AuditRequestContext request) {
        requireKey();
        String label = label(body.label());
        String token = token(body.token());
        UUID id = UUID.randomUUID();
        var encrypted = cipher.encrypt(id, token);
        String fingerprint = fingerprint(token);
        GithubSystemCredential credential = new GithubSystemCredential(id, label, fingerprint,
                encrypted.keyVersion(), encrypted.nonce(), encrypted.ciphertext(), "ACTIVE",
                null, null, Instant.now(), Instant.now());
        try {
            credentials.create(credential);
        } catch (DataIntegrityViolationException exception) {
            throw new DomainBadRequestException("error.githubSync.credential.duplicate");
        }
        audit("CREATE_GITHUB_CREDENTIAL", id, fingerprint, actorId, request);
        return toResponse(credentials.find(id).orElseThrow());
    }

    @Transactional
    public GithubCredentialResponse replace(UUID id, GithubCredentialUpsertRequest body,
                                            String actorId, AuditRequestContext request) {
        requireKey();
        require(id);
        String label = label(body.label());
        String token = token(body.token());
        var encrypted = cipher.encrypt(id, token);
        String fingerprint = fingerprint(token);
        try {
            credentials.replace(id, label, fingerprint, encrypted.keyVersion(),
                    encrypted.nonce(), encrypted.ciphertext());
        } catch (DataIntegrityViolationException exception) {
            throw new DomainBadRequestException("error.githubSync.credential.duplicate");
        }
        audit("REPLACE_GITHUB_CREDENTIAL", id, fingerprint, actorId, request);
        return toResponse(require(id));
    }

    @Transactional
    public GithubCredentialResponse setEnabled(UUID id, boolean enabled,
                                               String actorId, AuditRequestContext request) {
        GithubSystemCredential previous = require(id);
        if (enabled && "INVALID".equals(previous.status())) {
            throw new DomainBadRequestException("error.githubSync.credential.replaceRequired");
        }
        if (enabled && !cipher.supports(previous.keyVersion())) {
            throw new DomainBadRequestException("error.githubSync.credential.keyUnavailable");
        }
        String nextStatus = enabled ? "ACTIVE" : "DISABLED";
        if (!nextStatus.equals(previous.status())) {
            if (!credentials.setEnabled(id, enabled)) {
                throw new DomainBadRequestException("error.githubSync.credential.replaceRequired");
            }
            audit(enabled ? "ENABLE_GITHUB_CREDENTIAL" : "DISABLE_GITHUB_CREDENTIAL",
                    id, previous.fingerprint(), actorId, request);
        }
        return toResponse(require(id));
    }

    private GithubCredentialResponse toResponse(GithubSystemCredential credential) {
        return GithubCredentialResponse.from(credential, cipher, credentials.quotas(credential.id()));
    }

    private GithubSystemCredential require(UUID id) {
        return credentials.find(id)
                .orElseThrow(() -> new DomainNotFoundException("error.githubSync.credential.notFound"));
    }

    private void requireKey() {
        if (!cipher.configured()) {
            throw new DomainBadRequestException("error.githubSync.credential.keyUnavailable");
        }
    }

    private String label(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 100) {
            throw new DomainBadRequestException("error.githubSync.credential.label");
        }
        return value.trim();
    }

    private String token(String value) {
        if (value == null || value.length() < 20 || value.length() > 512
                || value.chars().anyMatch(character -> character < 33 || character > 126)) {
            throw new DomainBadRequestException("error.githubSync.credential.token");
        }
        return value;
    }

    private String fingerprint(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void audit(String action, UUID id, String fingerprint, String actorId,
                       AuditRequestContext request) {
        auditLogService.record(actorId, action, "GITHUB_CREDENTIAL", null,
                requestIdAccessor.current(), request.clientIp(), request.userAgent(),
                AuditDetail.builder().put("credentialId", id).put("fingerprint", fingerprint).build());
    }
}
