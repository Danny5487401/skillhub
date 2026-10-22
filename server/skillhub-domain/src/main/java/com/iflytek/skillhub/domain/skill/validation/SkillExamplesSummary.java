package com.iflytek.skillhub.domain.skill.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Non-blocking recognition of the immutable, authored examples file. Never rewrites bytes. */
public record SkillExamplesSummary(Status status, Integer count) {
    public enum Status { ready, none, invalid }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ID = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final Pattern NON_WHITESPACE = Pattern.compile(
            "[^\\u0009-\\u000D\\u0020\\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]");

    public static SkillExamplesSummary recognize(List<PackageEntry> entries) {
        return entries.stream().filter(entry -> SkillPackagePolicy.EXAMPLES_PATH.equals(entry.path()))
                .findFirst().map(entry -> recognize(entry.content()))
                .orElseGet(() -> new SkillExamplesSummary(Status.none, 0));
    }

    public static SkillExamplesSummary recognize(byte[] bytes) {
        if (bytes.length > 65_536) return invalid();
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            JsonNode root;
            try (var parser = JSON.createParser(text)) {
                root = JSON.readTree(parser);
                if (parser.nextToken() != null) return invalid();
            }
            if (!fields(root, Set.of("schemaVersion", "defaultLocale", "examples"))
                    || !root.get("schemaVersion").isNumber()
                    || root.get("schemaVersion").decimalValue().compareTo(java.math.BigDecimal.ONE) != 0) return invalid();
            JsonNode defaultLocale = root.get("defaultLocale");
            JsonNode examples = root.get("examples");
            if (!defaultLocale.isTextual() || !locale(defaultLocale.textValue())
                    || !examples.isObject() || examples.size() < 1 || examples.size() > 10) return invalid();
            Set<String> locales = new HashSet<>();
            int total = 0;
            var languages = examples.fields();
            while (languages.hasNext()) {
                var language = languages.next();
                if (!locale(language.getKey())
                        || !locales.add(language.getKey().toLowerCase(Locale.ROOT))) return invalid();
                JsonNode items = language.getValue();
                if (!items.isArray() || items.size() > 20) return invalid();
                Set<String> ids = new HashSet<>();
                for (JsonNode item : items) {
                    if (!fields(item, Set.of("id", "title", "prompt"))) return invalid();
                    JsonNode id = item.get("id");
                    if (!id.isTextual() || id.textValue().length() > 64
                            || !ID.matcher(id.textValue()).matches() || !ids.add(id.textValue())
                            || !text(item.get("title"), 80) || !text(item.get("prompt"), 4000)) return invalid();
                }
                total += items.size();
            }
            if (!locales.contains(defaultLocale.textValue().toLowerCase(Locale.ROOT))) return invalid();
            return new SkillExamplesSummary(total == 0 ? Status.none : Status.ready, total);
        } catch (java.io.IOException | IllegalArgumentException exception) {
            return invalid();
        }
    }

    private static boolean locale(String value) {
        String[] subtags = value.split("-", -1);
        if (subtags[0].length() < 2 || subtags[0].length() > 3
                || !subtags[0].chars().allMatch(code -> code >= 'a' && code <= 'z')) return false;
        for (int index = 1; index < subtags.length; index++) {
            String subtag = subtags[index];
            if (subtag.length() < 2 || subtag.length() > 8 || !subtag.chars().allMatch(code ->
                    code >= 'a' && code <= 'z' || code >= 'A' && code <= 'Z' || code >= '0' && code <= '9')) return false;
        }
        return true;
    }

    private static boolean fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject() || node.size() != expected.size()) return false;
        var fields = node.fieldNames();
        while (fields.hasNext()) if (!expected.contains(fields.next())) return false;
        return true;
    }

    private static boolean text(JsonNode node, int max) {
        return node.isTextual() && node.textValue().codePointCount(0, node.textValue().length()) <= max
                && NON_WHITESPACE.matcher(node.textValue()).find();
    }

    private static SkillExamplesSummary invalid() {
        return new SkillExamplesSummary(Status.invalid, null);
    }
}
