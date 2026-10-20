package com.iflytek.skillhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.config.SkillPublishProperties;
import com.iflytek.skillhub.domain.skill.metadata.SkillMetadataParser;
import com.iflytek.skillhub.domain.skill.validation.SkillPackageValidator;
import com.iflytek.skillhub.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GithubSyncRootEvidenceValidationTest {
    @Test
    void rootLicenseAndNoticeAreRehashedAndRecordedWithRootScope() throws Exception {
        byte[] license = "Root license evidence".getBytes(StandardCharsets.UTF_8);
        byte[] notice = "Root notice evidence".getBytes(StandardCharsets.UTF_8);
        var result = validate(List.of(evidence("LICENSE", license, true), evidence("NOTICE", notice, true)),
                Map.of("LICENSE", license, "NOTICE", notice));
        assertThat(result.evidenceJson()).contains("REPOSITORY_ROOT", "LICENSE", "NOTICE", "PRESENT_UNCLASSIFIED");
        var changed = validate(List.of(evidence("LICENSE", notice, true)), Map.of("LICENSE", notice));
        assertThat(changed.contentSha256()).isNotEqualTo(result.contentSha256());
    }

    @Test
    void tamperedDigestAndUndeclaredRootFileAreRejected() throws Exception {
        byte[] original = "Original".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> validate(List.of(evidence("LICENSE", original, true)),
                Map.of("LICENSE", "Tampered".getBytes(StandardCharsets.UTF_8))))
                .hasMessage("LICENSE_EVIDENCE_INVALID");
        assertThatThrownBy(() -> validate(List.of(), Map.of("NOTICE", original)))
                .hasMessage("LICENSE_EVIDENCE_INVALID");
    }

    @Test
    void sixteenRootDocumentsAreAllowedAndSeventeenthIsRejected() throws Exception {
        var records = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 16; i++) records.add(evidence("LICENSE-" + i, new byte[65537], false));
        assertThat(validate(records, Map.of()).evidenceJson()).contains("PRESENT_UNPREVIEWABLE");
        records.add(evidence("LICENSE-16", new byte[65537], false));
        assertThatThrownBy(() -> validate(records, Map.of())).hasMessage("LICENSE_EVIDENCE_INVALID");
    }

    @Test
    void singleRootFileBoundaryRequiresCorrectBundlingDecision() throws Exception {
        byte[] exact = new byte[65536];
        Arrays.fill(exact, (byte) 'x');
        assertThat(validate(List.of(evidence("LICENSE", exact, true)), Map.of("LICENSE", exact)).evidenceJson())
                .contains("PRESENT_UNCLASSIFIED");
        byte[] over = Arrays.copyOf(exact, 65537);
        assertThat(validate(List.of(evidence("LICENSE", over, false)), Map.of()).evidenceJson())
                .contains("PRESENT_UNPREVIEWABLE");
        assertThatThrownBy(() -> validate(List.of(evidence("LICENSE", over, true)), Map.of("LICENSE", over)))
                .isInstanceOf(GithubSyncArchiveValidator.ValidationFailure.class);
        assertThatThrownBy(() -> validate(List.of(evidence("LICENSE", exact, false)), Map.of()))
                .hasMessage("LICENSE_EVIDENCE_INVALID");
    }

    @Test
    void totalRootEvidenceBoundaryIsIndependentOfSkillPackageAllowance() throws Exception {
        byte[] content = new byte[65536];
        var records = new ArrayList<Map<String, Object>>();
        var files = new LinkedHashMap<String, byte[]>();
        for (int i = 0; i < 4; i++) {
            records.add(evidence("LICENSE-" + i, content, true));
            files.put("LICENSE-" + i, content);
        }
        assertThat(validate(records, files).evidenceJson()).contains("PRESENT_UNCLASSIFIED");
        records.add(evidence("NOTICE", new byte[]{1}, false));
        assertThat(validate(records, files).evidenceJson()).contains("PRESENT_UNPREVIEWABLE");
        records.set(4, evidence("NOTICE", new byte[]{1}, true));
        files.put("NOTICE", new byte[]{1});
        assertThatThrownBy(() -> validate(records, files)).hasMessage("LICENSE_EVIDENCE_LIMIT");
    }

    private Map<String, Object> evidence(String name, byte[] bytes, boolean bundled) throws Exception {
        var digest = MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
        return Map.of("path", name, "gitSha", HexFormat.of().formatHex(digest.digest(bytes)),
                "size", bytes.length, "bundled", bundled);
    }

    private GithubSyncArchiveValidator.ArchiveValidation validate(List<Map<String, Object>> records,
                                                                 Map<String, byte[]> files) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            entry(zip, "SKILL.md", "---\nname: root-evidence\ndescription: Root evidence validation fixture\n---\n# Fixture\n".getBytes(StandardCharsets.UTF_8));
            entry(zip, "__skillhub_sync_evidence__.json", new ObjectMapper().writeValueAsBytes(
                    Map.of("formatVersion", 1, "rootFiles", records)));
            for (var file : files.entrySet()) entry(zip, "__skillhub_sync_evidence__/" + file.getKey(), file.getValue());
        }
        byte[] archive = output.toByteArray();
        var storage = mock(ObjectStorageService.class);
        when(storage.getObject("fixture")).thenAnswer(ignored -> new ByteArrayInputStream(archive));
        var policy = new SkillPublishProperties();
        var validator = new GithubSyncArchiveValidator(storage, policy,
                new SkillPackageValidator(new SkillMetadataParser(), policy.getMaxFileCount(),
                        policy.getMaxSingleFileSize(), policy.getMaxPackageSize(), policy.getAllowedFileExtensions()));
        return validator.validate("fixture", archive.length,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive)), "skills/root-evidence");
    }

    private void entry(ZipOutputStream zip, String path, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(bytes);
        zip.closeEntry();
    }
}
