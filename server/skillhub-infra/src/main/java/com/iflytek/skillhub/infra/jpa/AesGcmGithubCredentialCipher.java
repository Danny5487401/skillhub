package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.githubsync.GithubCredentialCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Key material is supplied by the deployment secret, never persisted alongside ciphertext. */
@Component
public class AesGcmGithubCredentialCipher implements GithubCredentialCipher {
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final Map<String, SecretKeySpec> keys;
    private final String activeVersion;
    private final SecureRandom random = new SecureRandom();

    public AesGcmGithubCredentialCipher(
            @Value("${skillhub.github-sync.credential-keys:}") String configuredKeys,
            @Value("${skillhub.github-sync.active-credential-key-version:}") String activeVersion) {
        Map<String, SecretKeySpec> parsed = new HashMap<>();
        if (!configuredKeys.isBlank()) {
            for (String item : configuredKeys.split(",", -1)) {
                int separator = item.indexOf('=');
                if (separator < 1 || separator == item.length() - 1) {
                    throw new IllegalArgumentException("Invalid GitHub credential key configuration");
                }
                String version = item.substring(0, separator).trim();
                if (!version.matches("[A-Za-z0-9._-]{1,40}") || parsed.containsKey(version)) {
                    throw new IllegalArgumentException("Invalid GitHub credential key version");
                }
                byte[] decoded;
                try {
                    decoded = Base64.getDecoder().decode(item.substring(separator + 1).trim());
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("Invalid GitHub credential key encoding");
                }
                if (decoded.length != 32) {
                    throw new IllegalArgumentException("GitHub credential keys must be 32 bytes");
                }
                parsed.put(version, new SecretKeySpec(decoded, "AES"));
                Arrays.fill(decoded, (byte) 0);
            }
        }
        this.activeVersion = activeVersion.trim();
        if (parsed.isEmpty() != this.activeVersion.isEmpty()
                || !parsed.isEmpty() && !parsed.containsKey(this.activeVersion)) {
            throw new IllegalArgumentException("Active GitHub credential key version is missing");
        }
        this.keys = Map.copyOf(parsed);
    }

    @Override
    public boolean configured() {
        return !keys.isEmpty();
    }

    @Override
    public boolean supports(String keyVersion) {
        return keys.containsKey(keyVersion);
    }

    @Override
    public EncryptedValue encrypt(UUID credentialId, String token) {
        if (!configured()) {
            throw new IllegalStateException("GitHub credential encryption key is not configured");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = init(Cipher.ENCRYPT_MODE, credentialId, activeVersion, nonce);
            byte[] plaintext = token.getBytes(StandardCharsets.UTF_8);
            try {
                return new EncryptedValue(activeVersion, nonce, cipher.doFinal(plaintext));
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not encrypt GitHub credential", exception);
        }
    }

    @Override
    public String decrypt(UUID credentialId, String keyVersion, byte[] nonce, byte[] ciphertext) {
        if (!keys.containsKey(keyVersion)) {
            throw new IllegalStateException("GitHub credential key version is unavailable: " + keyVersion);
        }
        if (nonce == null || nonce.length != NONCE_BYTES || ciphertext == null) {
            throw new IllegalStateException("GitHub credential ciphertext is malformed");
        }
        try {
            Cipher cipher = init(Cipher.DECRYPT_MODE, credentialId, keyVersion, nonce);
            byte[] plaintext = cipher.doFinal(ciphertext);
            try {
                return new String(plaintext, StandardCharsets.UTF_8);
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not decrypt GitHub credential", exception);
        }
    }

    private Cipher init(int mode, UUID credentialId, String version, byte[] nonce)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, keys.get(version), new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD((credentialId + ":" + version).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
