package com.iflytek.skillhub.domain.skill.validation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SkillExamplesSummaryTest {
    private SkillExamplesSummary parse(String text) { return SkillExamplesSummary.recognize(text.getBytes(StandardCharsets.UTF_8)); }
    private String document(String examples) { return "{\"schemaVersion\":1,\"defaultLocale\":\"zh-CN\",\"examples\":" + examples + "}"; }
    private String item(String title) { return "{\"id\":\"summary\",\"title\":\"" + title + "\",\"prompt\":\"Use {{skill}}\"}"; }

    @Test
    void recognizesNormativeFixturesWithoutChangingBytes() throws Exception {
        Path root = Path.of("../../openspec/changes/add-dsh-native-plugin/contracts");
        byte[] valid = Files.readAllBytes(root.resolve("skill-examples.valid.json"));
        byte[] before = valid.clone();
        assertThat(SkillExamplesSummary.recognize(valid).status()).isEqualTo(SkillExamplesSummary.Status.ready);
        assertThat(valid).containsExactly(before);
        assertThat(SkillExamplesSummary.recognize(Files.readAllBytes(root.resolve("skill-examples.empty.json"))))
                .isEqualTo(new SkillExamplesSummary(SkillExamplesSummary.Status.none, 0));
    }

    @Test
    void countsAllLanguageEntriesIncludingTranslationsAndEmptyDefaultLanguage() {
        assertThat(parse(document("{\"zh-CN\":[],\"en\":[" + item("English") + "]}")))
                .isEqualTo(new SkillExamplesSummary(SkillExamplesSummary.Status.ready, 1));
        assertThat(parse(document("{\"zh-CN\":[" + item("中文") + "],\"en\":[" + item("English") + "]}")).count()).isEqualTo(2);
        assertThat(SkillExamplesSummary.recognize(List.of()).count()).isZero();
    }

    @Test
    void acceptsOneBomAndUsesCodePointsForTextBounds() {
        assertThat(parse("\uFEFF" + document("{\"zh-CN\":[" + item("😀".repeat(80)) + "]}")).status()).isEqualTo(SkillExamplesSummary.Status.ready);
        assertInvalid(document("{\"zh-CN\":[" + item("😀".repeat(81)) + "]}"));
        assertInvalid("\uFEFF\uFEFF" + document("{\"zh-CN\":[]}"));
    }

    @Test
    void rejectsUnsupportedStructureAndSemanticAmbiguityWithoutCount() {
        for (String value : List.of("null", "{}", "[]", "not json",
                document("{\"zh-CN\":[\"old string\"]}"), document("{\"zh-CN\":[],\"zh-cn\":[]}"), document("{\"en\":[]}"),
                document("{\"zh-CN\":[" + item("one") + "," + item("two") + "]}"),
                document("{\"zh-CN\":[" + item("\u0085\uFEFF") + "]}"),
                document("{\"zh-CN\":[" + item("one").replace("\"prompt\":", "\"extra\":0,\"prompt\":") + "]}"),
                document("{\"zh-CN\":[]}").replace("schemaVersion\":1", "schemaVersion\":2"),
                document("{\"zh-CN\":[]}").replace("schemaVersion\":1", "schemaVersion\":1e9999"), document("{\"zh-CN\":[]}") + " {}")) assertInvalid(value);
        assertThat(SkillExamplesSummary.recognize(new byte[] {(byte) 0xFF}).status()).isEqualTo(SkillExamplesSummary.Status.invalid);
        assertThat(SkillExamplesSummary.recognize(new byte[65_537]).status()).isEqualTo(SkillExamplesSummary.Status.invalid);
    }
    @Test
    void longButBoundedLocaleTagsDoNotOverflowTheParserStack() {
        String locale = "en" + "-aa".repeat(5000);
        String content = "{\"schemaVersion\":1,\"defaultLocale\":\"" + locale
                + "\",\"examples\":{\"" + locale + "\":[]}}";
        assertThat(parse(content)).isEqualTo(new SkillExamplesSummary(SkillExamplesSummary.Status.none, 0));
    }
    private void assertInvalid(String value) { assertThat(parse(value)).isEqualTo(new SkillExamplesSummary(SkillExamplesSummary.Status.invalid, null)); }
}
