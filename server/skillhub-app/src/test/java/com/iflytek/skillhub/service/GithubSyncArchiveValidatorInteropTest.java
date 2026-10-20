package com.iflytek.skillhub.service;

import com.iflytek.skillhub.config.SkillPublishProperties;
import com.iflytek.skillhub.domain.skill.metadata.SkillMetadataParser;
import com.iflytek.skillhub.domain.skill.validation.SkillPackageValidator;
import com.iflytek.skillhub.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GithubSyncArchiveValidatorInteropTest {

    @Test
    void validatesArchiveProducedByGoAtFixedPublicCommit() throws IOException, NoSuchAlgorithmException {
        String fixturePath = System.getenv("SKILLHUB_GITHUB_SYNC_ARCHIVE_FIXTURE");
        Assumptions.assumeTrue(fixturePath != null && !fixturePath.isBlank(),
                "requires an explicit Go-produced archive path");
        Path archive = Path.of(fixturePath);
        assertThat(archive).isRegularFile();
        byte[] bytes = Files.readAllBytes(archive);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));

        ObjectStorageService storage = mock(ObjectStorageService.class);
        when(storage.getObject("go-produced-archive"))
                .thenAnswer(invocation -> Files.newInputStream(archive));
        SkillPublishProperties policy = new SkillPublishProperties();
        var validator = new GithubSyncArchiveValidator(storage, policy,
                new SkillPackageValidator(new SkillMetadataParser(), policy.getMaxFileCount(),
                        policy.getMaxSingleFileSize(), policy.getMaxPackageSize(),
                        policy.getAllowedFileExtensions()));

        var first = validator.validate("go-produced-archive", bytes.length, digest,
                "skills/.system/skill-creator");
        var second = validator.validate("go-produced-archive", bytes.length, digest,
                "skills/.system/skill-creator");

        assertThat(first.contentSha256()).matches("[0-9a-f]{64}")
                .isEqualTo(second.contentSha256());
        assertThat(first.evidenceJson()).contains("LICENSE.txt", "PRESENT_UNCLASSIFIED");

        AtomicBoolean published = new AtomicBoolean();
        assertThatThrownBy(() -> validator.withValidatedEntries("go-produced-archive", bytes.length,
                digest, "skills/.system/skill-creator", "0".repeat(64),
                (validation, entries) -> {
                    published.set(true);
                    return null;
                }))
                .isInstanceOf(GithubSyncArchiveValidator.ValidationFailure.class)
                .hasMessage("PACKAGE_CONTENT_CHANGED");
        assertThat(published).isFalse();
        assertThatThrownBy(() -> validator.validate("go-produced-archive", bytes.length,
                "0".repeat(64), "skills/.system/skill-creator"))
                .isInstanceOf(GithubSyncArchiveValidator.ValidationFailure.class)
                .hasMessage("ARCHIVE_DIGEST_MISMATCH");
    }
}
