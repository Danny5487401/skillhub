package com.iflytek.skillhub.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.dto.GithubCredentialResponse;
import com.iflytek.skillhub.githubsync.GithubSystemCredential;
import com.iflytek.skillhub.infra.jpa.AesGcmGithubCredentialCipher;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmGithubCredentialCipherTest {
    @Test
    void missingKeyRejectsEncryptionAndDecryption() {
        var cipher = new AesGcmGithubCredentialCipher("", "");
        UUID credentialId = UUID.randomUUID();

        assertThat(cipher.configured()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt(credentialId, "example-token"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(credentialId, "v1", new byte[12], new byte[17]))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void newActiveKeyStillReadsCiphertextWrittenWithOldVersion() {
        String oldKey = Base64.getEncoder().encodeToString(new byte[32]);
        byte[] nextBytes = new byte[32];
        java.util.Arrays.fill(nextBytes, (byte) 1);
        String nextKey = Base64.getEncoder().encodeToString(nextBytes);
        UUID credentialId = UUID.randomUUID();
        var oldCipher = new AesGcmGithubCredentialCipher("v1=" + oldKey, "v1");
        var encrypted = oldCipher.encrypt(credentialId, "example-token");
        var rotatedCipher = new AesGcmGithubCredentialCipher(
                "v1=" + oldKey + ",v2=" + nextKey, "v2");

        assertThat(encrypted.keyVersion()).isEqualTo("v1");
        assertThat(rotatedCipher.decrypt(credentialId, encrypted.keyVersion(),
                encrypted.nonce(), encrypted.ciphertext())).isEqualTo("example-token");
        assertThat(rotatedCipher.encrypt(credentialId, "next-token").keyVersion()).isEqualTo("v2");
        assertThatThrownBy(() -> rotatedCipher.decrypt(UUID.randomUUID(), encrypted.keyVersion(),
                encrypted.nonce(), encrypted.ciphertext())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AesGcmGithubCredentialCipher("v2=" + nextKey, "v2")
                .decrypt(credentialId, encrypted.keyVersion(), encrypted.nonce(), encrypted.ciphertext()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void administratorResponseExposesOnlyCredentialMetadata() throws Exception {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        var cipher = new AesGcmGithubCredentialCipher("v1=" + key, "v1");
        UUID credentialId = UUID.randomUUID();
        var encrypted = cipher.encrypt(credentialId, "example-token");
        var credential = new GithubSystemCredential(credentialId, "Public reader",
                "a".repeat(16), encrypted.keyVersion(), encrypted.nonce(),
                encrypted.ciphertext(), "ACTIVE", null, null, Instant.now(), Instant.now());

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(
                GithubCredentialResponse.from(credential, cipher, List.of()));

        assertThat(json).contains("fingerprint", "keyAvailable");
        assertThat(json).doesNotContain("example-token", "ciphertext", "nonce", "\"token\"");
    }
}
