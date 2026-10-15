package com.iflytek.skillhub.service;

import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.skill.metadata.SkillMetadataParser;
import com.iflytek.skillhub.domain.skill.validation.PackageEntry;
import com.iflytek.skillhub.domain.skill.validation.SkillPackageValidator;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillPackagePreviewServiceTest {
    private final SkillMetadataParser parser = new SkillMetadataParser();
    private final SkillPackagePreviewService service = new SkillPackagePreviewService(new SkillPackageValidator(parser), parser);

    @Test
    void missingDisplayNameUsesPackageNameAndDoesNotInventVersion() {
        var response = service.preview(entries(""));
        assertThat(response.name()).isEqualTo("example-skill");
        assertThat(response.displayName()).isEqualTo("example-skill");
        assertThat(response.version()).isNull();
    }

    @Test
    void previewUsesSameStrictMetadataRulesAsPublish() {
        for (String displayName : List.of("123", "中".repeat(201))) {
            assertThatThrownBy(() -> service.preview(entries("metadata:\n  display-name: " + displayName + "\n")))
                    .isInstanceOf(DomainBadRequestException.class);
        }
    }

    @Test
    void missingSkillMdCannotBePreviewed() {
        assertThatThrownBy(() -> service.preview(List.of()))
                .isInstanceOf(DomainBadRequestException.class);
    }

    private List<PackageEntry> entries(String extra) {
        byte[] bytes = ("---\nname: example-skill\ndescription: Summary\n" + extra + "---\nBody").getBytes(StandardCharsets.UTF_8);
        return List.of(new PackageEntry("SKILL.md", bytes, bytes.length, "text/markdown"));
    }
}
