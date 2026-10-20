package com.iflytek.skillhub.githubsync;

import java.util.UUID;

public interface GithubCredentialCipher {
    boolean configured();
    boolean supports(String keyVersion);
    EncryptedValue encrypt(UUID credentialId, String token);
    String decrypt(UUID credentialId, String keyVersion, byte[] nonce, byte[] ciphertext);

    record EncryptedValue(String keyVersion, byte[] nonce, byte[] ciphertext) {}
}
