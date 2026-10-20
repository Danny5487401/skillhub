package com.iflytek.skillhub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.config.SkillPublishProperties;
import com.iflytek.skillhub.domain.skill.validation.PackageEntry;
import com.iflytek.skillhub.domain.skill.validation.SkillPackagePolicy;
import com.iflytek.skillhub.domain.skill.validation.SkillPackageValidator;
import com.iflytek.skillhub.domain.skill.validation.ValidationResult;
import com.iflytek.skillhub.githubsync.GithubSkillPathPolicy;
import com.iflytek.skillhub.service.archive.ZipArchiveSafety;
import com.iflytek.skillhub.storage.ObjectStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/** Revalidates one untrusted staged ZIP within the active publish limits. */
@Service
public class GithubSyncArchiveValidator {
    private static final Logger log = LoggerFactory.getLogger(GithubSyncArchiveValidator.class);
    private static final int BUFFER_SIZE = 8192;
    private static final String EVIDENCE_MANIFEST = "__skillhub_sync_evidence__.json";
    private static final String EVIDENCE_PREFIX = "__skillhub_sync_evidence__/";
    private static final int MAX_MANIFEST_BYTES = 16 * 1024;
    private static final int MAX_EVIDENCE_FILES = 16;
    private static final int MAX_EVIDENCE_FILE_BYTES = 64 * 1024;
    private static final int MAX_EVIDENCE_TOTAL_BYTES = 256 * 1024;
    private static final Pattern EVIDENCE_NAME = Pattern.compile(
            "(?i)^(license|licence|copying|notice|copyright)(?:[-_.][a-z0-9][a-z0-9._-]*)?$");
    private static final Pattern LICENSE_NAME = Pattern.compile(
            "(?i)^(license|licence|copying)(?:[-_.][a-z0-9][a-z0-9._-]*)?$");

    private final ObjectStorageService storage;
    private final SkillPublishProperties policy;
    private final SkillPackageValidator packageValidator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GithubSyncArchiveValidator(ObjectStorageService storage, SkillPublishProperties policy,
                                      SkillPackageValidator packageValidator) {
        this.storage = storage;
        this.policy = policy;
        this.packageValidator = packageValidator;
    }

    public ArchiveValidation validate(String objectKey, long expectedSize, String expectedSha256,
                                      String skillPath)
            throws IOException {
        return withValidatedEntries(objectKey, expectedSize, expectedSha256, skillPath,
                null, (result, entries) -> result);
    }

    /** Keeps the bounded extracted files available only while the caller processes the package. */
    public <T> T withValidatedEntries(String objectKey, long expectedSize, String expectedSha256,
                                      String skillPath, String expectedContentSha256,
                                      BiFunction<ArchiveValidation, List<PackageEntry>, T> consumer)
            throws IOException {
        long maxPackageSize = policy.getMaxPackageSize();
        if (expectedSize <= 0 || expectedSize > maxPackageSize || maxPackageSize <= 0
                || policy.getMaxSingleFileSize() <= 0 || policy.getMaxFileCount() <= 0) {
            throw invalid("PACKAGE_LIMIT");
        }
        if (skillPath == null || skillPath.isBlank() || ignoredOrInvalid(skillPath)) {
            throw invalid("SKILL_PATH_IGNORED");
        }
        Path tempDir = Files.createTempDirectory("skillhub-github-ingestion-");
        List<Path> files = new ArrayList<>();
        Path archivePath = tempDir.resolve("package.zip");
        try {
            long requiredFree = Math.addExact(expectedSize, maxPackageSize);
            if (Files.getFileStore(tempDir).getUsableSpace() < requiredFree) {
                throw new InsufficientLocalCapacityException();
            }
            copyAndVerifyArchive(objectKey, archivePath, expectedSize, expectedSha256, maxPackageSize);
            try {
                long maxEntries = Math.addExact(Math.multiplyExact((long) policy.getMaxFileCount(), 2L), 100L);
                ZipArchiveSafety.rejectSymbolicLinks(archivePath, (int) Math.min(maxEntries, Integer.MAX_VALUE),
                        Math.min(maxEntries * 4096L, maxPackageSize));
            } catch (IllegalArgumentException exception) {
                throw invalid("INVALID_ARCHIVE");
            }
            try {
                return inspectZip(archivePath, tempDir, files, maxPackageSize,
                        expectedContentSha256, consumer);
            } catch (ZipException exception) {
                throw invalid("INVALID_ARCHIVE");
            }
        } finally {
            for (Path file : files) {
                clean(file);
            }
            clean(archivePath);
            clean(tempDir);
        }
    }

    private boolean ignoredOrInvalid(String skillPath) {
        try {
            return GithubSkillPathPolicy.ignored(skillPath, List.of());
        } catch (IllegalArgumentException exception) {
            return true;
        }
    }

    private void copyAndVerifyArchive(String objectKey, Path archivePath, long expectedSize,
                                      String expectedSha256, long maximum) throws IOException {
        MessageDigest digest = newDigest();
        long copied = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = storage.getObject(objectKey);
             OutputStream output = Files.newOutputStream(archivePath)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                copied += count;
                if (copied > maximum || copied > expectedSize) {
                    throw invalid("ARCHIVE_SIZE_MISMATCH");
                }
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
            }
        }
        if (copied != expectedSize || !HexFormat.of().formatHex(digest.digest()).equals(expectedSha256)) {
            throw invalid("ARCHIVE_DIGEST_MISMATCH");
        }
    }

    private <T> T inspectZip(Path archivePath, Path tempDir, List<Path> files,
                             long maxExpandedSize, String expectedContentSha256,
                             BiFunction<ArchiveValidation, List<PackageEntry>, T> consumer)
            throws IOException {
        List<PackageEntry> packageEntries = new ArrayList<>();
        List<EntryDigest> digests = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        Map<String, byte[]> evidenceContents = new LinkedHashMap<>();
        byte[] evidenceManifest = null;
        long expanded = 0;
        long evidenceExpanded = 0;
        int allEntries = 0;
        try (ZipFile zip = new ZipFile(archivePath.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                allEntries++;
                if (allEntries > Math.multiplyExact(policy.getMaxFileCount(), 2) + 100) {
                    throw invalid("PACKAGE_FILE_LIMIT");
                }
                String rawPath = entry.getName();
                if (rawPath == null || rawPath.length() > 1024 || rawPath.indexOf('\\') >= 0) {
                    throw invalid("PACKAGE_PATH_INVALID");
                }
                String path;
                try {
                    path = SkillPackagePolicy.normalizeEntryPath(
                            entry.isDirectory() ? rawPath.replaceFirst("/+$", "") : rawPath);
                } catch (IllegalArgumentException exception) {
                    throw invalid("PACKAGE_PATH_INVALID");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                if (!paths.add(path)) {
                    throw invalid("PACKAGE_STRUCTURE_INVALID");
                }
                if (path.equals(EVIDENCE_MANIFEST)) {
                    evidenceManifest = boundedEntry(zip, entry, MAX_MANIFEST_BYTES);
                    continue;
                }
                if (path.startsWith(EVIDENCE_PREFIX)) {
                    String evidenceName = path.substring(EVIDENCE_PREFIX.length());
                    if (!EVIDENCE_NAME.matcher(evidenceName).matches()
                            || evidenceContents.size() >= MAX_EVIDENCE_FILES) {
                        throw invalid("LICENSE_EVIDENCE_INVALID");
                    }
                    byte[] content = boundedEntry(zip, entry, MAX_EVIDENCE_FILE_BYTES);
                    evidenceExpanded += content.length;
                    if (evidenceExpanded > MAX_EVIDENCE_TOTAL_BYTES) {
                        throw invalid("LICENSE_EVIDENCE_LIMIT");
                    }
                    evidenceContents.put(evidenceName, content);
                    continue;
                }
                if (path.endsWith("/SKILL.md") ||
                        packageEntries.size() >= policy.getMaxFileCount()) {
                    throw invalid("PACKAGE_STRUCTURE_INVALID");
                }
                Path localPath = tempDir.resolve("entry-" + packageEntries.size());
                files.add(localPath);
                MessageDigest fileDigest = newDigest();
                long fileSize = 0;
                byte[] buffer = new byte[BUFFER_SIZE];
                try (InputStream input = zip.getInputStream(entry);
                     OutputStream output = Files.newOutputStream(localPath)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        fileSize += count;
                        expanded += count;
                        if (fileSize > policy.getMaxSingleFileSize() || expanded > maxExpandedSize) {
                            throw invalid("PACKAGE_SIZE_LIMIT");
                        }
                        fileDigest.update(buffer, 0, count);
                        output.write(buffer, 0, count);
                    }
                }
                long finalFileSize = fileSize;
                packageEntries.add(PackageEntry.streaming(path, fileSize, "application/octet-stream",
                        () -> Files.newInputStream(localPath)));
                digests.add(new EntryDigest(path, finalFileSize,
                        HexFormat.of().formatHex(fileDigest.digest())));
            }
        }
        ValidationResult validation = packageValidator.validate(packageEntries);
        if (!validation.passed()) {
            throw invalid("PACKAGE_POLICY_INVALID");
        }
        List<RootEvidence> rootEvidence = parseRootEvidence(evidenceManifest, evidenceContents);
        String evidenceJson = evidenceSnapshot(rootEvidence, packageEntries, digests);
        boolean evidenceWarning = rootEvidence.stream().anyMatch(evidence -> !evidence.bundled())
                || !hasLicenseEvidence(rootEvidence, packageEntries);
        ArchiveValidation result = new ArchiveValidation(
                contentFingerprint(digests, rootEvidence),
                validation.warnings().size() + (evidenceWarning ? 1 : 0), evidenceJson);
        if (expectedContentSha256 != null
                && !expectedContentSha256.equals(result.contentSha256())) {
            throw invalid("PACKAGE_CONTENT_CHANGED");
        }
        return consumer.apply(result, List.copyOf(packageEntries));
    }

    private byte[] boundedEntry(ZipFile zip, ZipEntry entry, int limit) throws IOException {
        if (entry.getSize() > limit) {
            throw invalid("LICENSE_EVIDENCE_LIMIT");
        }
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] content = input.readNBytes(limit + 1);
            if (content.length > limit) {
                throw invalid("LICENSE_EVIDENCE_LIMIT");
            }
            return content;
        }
    }

    private List<RootEvidence> parseRootEvidence(byte[] manifest,
                                                 Map<String, byte[]> contents) {
        if (manifest == null) {
            if (!contents.isEmpty()) {
                throw invalid("LICENSE_EVIDENCE_INVALID");
            }
            return List.of();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(manifest);
        } catch (IOException exception) {
            throw invalid("LICENSE_EVIDENCE_INVALID");
        }
        JsonNode files = root == null ? null : root.path("rootFiles");
        if (root == null || !root.path("formatVersion").canConvertToInt()
                || root.path("formatVersion").asInt() != 1 || files == null
                || !files.isArray() || files.size() > MAX_EVIDENCE_FILES) {
            throw invalid("LICENSE_EVIDENCE_INVALID");
        }
        List<RootEvidence> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        long bundledBytes = 0;
        for (JsonNode file : files) {
            JsonNode pathNode = file.path("path");
            JsonNode shaNode = file.path("gitSha");
            JsonNode sizeNode = file.path("size");
            JsonNode bundledNode = file.path("bundled");
            if (!pathNode.isTextual() || !shaNode.isTextual() || !sizeNode.isIntegralNumber()
                    || !bundledNode.isBoolean() || !sizeNode.canConvertToLong()) {
                throw invalid("LICENSE_EVIDENCE_INVALID");
            }
            String path = pathNode.asText();
            String gitSha = shaNode.asText();
            long size = sizeNode.asLong();
            boolean bundled = bundledNode.asBoolean();
            if (!EVIDENCE_NAME.matcher(path).matches() || !seen.add(path)
                    || !gitSha.matches("[0-9a-f]{40}") || size < 0) {
                throw invalid("LICENSE_EVIDENCE_INVALID");
            }
            boolean shouldBundle = size <= MAX_EVIDENCE_FILE_BYTES
                    && size <= MAX_EVIDENCE_TOTAL_BYTES - bundledBytes;
            if (bundled != shouldBundle) {
                throw invalid("LICENSE_EVIDENCE_INVALID");
            }
            byte[] content = contents.get(path);
            if (bundled) {
                if (content == null || content.length != size
                        || !gitSha.equals(gitBlobSha(content))) {
                    throw invalid("LICENSE_EVIDENCE_INVALID");
                }
                bundledBytes += size;
            } else if (content != null) {
                throw invalid("LICENSE_EVIDENCE_INVALID");
            }
            result.add(new RootEvidence(path, gitSha, size, bundled, content));
        }
        Set<String> bundledPaths = new HashSet<>();
        result.stream().filter(RootEvidence::bundled).map(RootEvidence::path)
                .forEach(bundledPaths::add);
        if (!bundledPaths.equals(contents.keySet())) {
            throw invalid("LICENSE_EVIDENCE_INVALID");
        }
        return List.copyOf(result);
    }

    private String evidenceSnapshot(List<RootEvidence> rootEvidence,
                                    List<PackageEntry> entries,
                                    List<EntryDigest> digests) throws IOException {
        List<Map<String, Object>> documents = new ArrayList<>();
        for (RootEvidence evidence : rootEvidence) {
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("scope", "REPOSITORY_ROOT");
            document.put("path", evidence.path());
            document.put("gitSha", evidence.gitSha());
            document.put("size", evidence.size());
            document.put("bundled", evidence.bundled());
            if (evidence.content() != null) {
                document.put("sha256", sha256(evidence.content()));
                String preview = utf8Preview(evidence.content());
                if (preview != null) {
                    document.put("text", preview);
                }
            }
            documents.add(document);
        }
        for (PackageEntry entry : entries) {
            if (!EVIDENCE_NAME.matcher(entry.path()).matches()) {
                continue;
            }
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("scope", "SKILL_ROOT");
            document.put("path", entry.path());
            document.put("size", entry.size());
            digests.stream().filter(digest -> digest.path().equals(entry.path()))
                    .findFirst().ifPresent(digest -> document.put("sha256", digest.sha256()));
            if (entry.size() <= MAX_EVIDENCE_FILE_BYTES) {
                String preview = utf8Preview(entry.content());
                if (preview != null) {
                    document.put("text", preview);
                }
            }
            documents.add(document);
        }
        String status = !hasLicenseEvidence(rootEvidence, entries) ? "MISSING"
                : rootEvidence.stream().anyMatch(evidence -> !evidence.bundled())
                    ? "PRESENT_UNPREVIEWABLE" : "PRESENT_UNCLASSIFIED";
        return objectMapper.writeValueAsString(Map.of(
                "formatVersion", 1, "status", status, "documents", documents));
    }

    private boolean hasLicenseEvidence(List<RootEvidence> rootEvidence,
                                       List<PackageEntry> entries) {
        return rootEvidence.stream().anyMatch(evidence -> LICENSE_NAME.matcher(evidence.path()).matches())
                || entries.stream().anyMatch(entry -> LICENSE_NAME.matcher(entry.path()).matches());
    }

    private String utf8Preview(byte[] content) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException exception) {
            return null;
        }
    }

    private String gitBlobSha(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + content.length + "\0").getBytes(StandardCharsets.US_ASCII));
            digest.update(content);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Git object hash is unavailable", exception);
        }
    }

    private String sha256(byte[] content) {
        return HexFormat.of().formatHex(newDigest().digest(content));
    }

    private String contentFingerprint(List<EntryDigest> entries, List<RootEvidence> rootEvidence) {
        MessageDigest digest = newDigest();
        entries.stream().sorted(Comparator.comparing(EntryDigest::path)).forEach(entry -> {
            byte[] path = entry.path().getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(4).putInt(path.length).array());
            digest.update(path);
            digest.update(ByteBuffer.allocate(8).putLong(entry.size()).array());
            digest.update(HexFormat.of().parseHex(entry.sha256()));
        });
        if (!rootEvidence.isEmpty()) {
            digest.update((byte) 0xff);
            rootEvidence.stream().sorted(Comparator.comparing(RootEvidence::path)).forEach(evidence -> {
                byte[] path = evidence.path().getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(path.length).array());
                digest.update(path);
                digest.update(ByteBuffer.allocate(8).putLong(evidence.size()).array());
                digest.update(HexFormat.of().parseHex(evidence.gitSha()));
            });
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void clean(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("Could not remove GitHub ingestion temporary file: {}",
                    exception.getClass().getSimpleName());
        }
    }

    private static ValidationFailure invalid(String code) {
        return new ValidationFailure(code);
    }

    public record ArchiveValidation(String contentSha256, int warningCount,
                                    String evidenceJson) {}
    private record EntryDigest(String path, long size, String sha256) {}
    private record RootEvidence(String path, String gitSha, long size,
                                boolean bundled, byte[] content) {}

    public static class ValidationFailure extends RuntimeException {
        private final String code;

        private ValidationFailure(String code) {
            super(code);
            this.code = code;
        }

        public String code() { return code; }
    }

    public static class InsufficientLocalCapacityException extends IOException {}
}
