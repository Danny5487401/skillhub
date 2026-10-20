package com.iflytek.skillhub.service;

import com.iflytek.skillhub.auth.rbac.RbacService;
import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.review.ReviewService;
import com.iflytek.skillhub.domain.review.ReviewTask;
import com.iflytek.skillhub.domain.review.ReviewTaskRepository;
import com.iflytek.skillhub.domain.review.ReviewTaskStatus;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillFile;
import com.iflytek.skillhub.domain.skill.SkillVersion;
import com.iflytek.skillhub.domain.skill.SkillVersionStatus;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.skill.service.SkillDownloadService;
import com.iflytek.skillhub.domain.skill.service.SkillQueryService;
import com.iflytek.skillhub.dto.ReviewSkillDetailResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ReviewSkillDetailAppServiceTest {

    @Mock
    private ReviewTaskRepository reviewTaskRepository;
    @Mock
    private NamespaceRepository namespaceRepository;
    @Mock
    private ReviewService reviewService;
    @Mock
    private RbacService rbacService;
    @Mock
    private SkillQueryService skillQueryService;
    @Mock
    private SkillDownloadService skillDownloadService;

    @Mock private com.iflytek.skillhub.domain.skill.SkillRepository skills;
    @Mock private com.iflytek.skillhub.infra.jpa.SkillAvatarCandidateJpaRepository candidates;
    private ReviewSkillDetailAppService service;

    @BeforeEach
    void setUp() {
        service = new ReviewSkillDetailAppService(
                reviewTaskRepository,
                namespaceRepository,
                reviewService,
                rbacService,
                skillQueryService,
                skillDownloadService,
                new ComplianceSnapshotProjectionService(new com.fasterxml.jackson.databind.ObjectMapper())
        );
    }

    @Test
    void getReviewSkillDetail_returnsPendingReviewVersionPayload() {
        ReviewTask task = createReviewTask(42L, 101L, 20L, "submitter");
        Namespace namespace = createNamespace(20L, "team-a");
        Skill skill = createSkill(101L, 20L, "skill-a");
        SkillVersion pending = createVersion(101L, 101L, "1.2.0", SkillVersionStatus.PENDING_REVIEW);
        setField(pending, "githubSyncRevision", 3);
        pending.setParsedMetadataJson("""
                {"name":"skill-a","description":"待审摘要","displayNameOverride":"网页新标题","complianceSnapshot":{"schemaVersion":"1.0","items":[{"standard":"mitre-attack","version":"v19.1","controlId":"T1059","evidence":[]}],"digest":"sha256:demo"}}
                """);
        SkillVersion published = createVersion(100L, 101L, "1.1.0", SkillVersionStatus.PUBLISHED);
        SkillFile readme = createFile(1L, 101L, "README.md");

        given(reviewTaskRepository.findById(42L)).willReturn(Optional.of(task));
        given(namespaceRepository.findById(20L)).willReturn(Optional.of(namespace));
        given(rbacService.getUserRoleCodes("admin")).willReturn(Set.of("SKILL_ADMIN"));
        given(reviewService.canViewReview(task, "admin", namespace.getType(), Map.of(), Set.of("SKILL_ADMIN"))).willReturn(true);
        given(skillQueryService.getReviewSkillSnapshot(101L)).willReturn(
                new SkillQueryService.ReviewSkillSnapshotDTO(
                        skill,
                        "Owner",
                        pending,
                        published,
                        List.of(pending, published),
                        List.of(readme),
                        "README.md",
                        "# demo"
                )
        );

        ReviewSkillDetailResponse response = service.getReviewSkillDetail(42L, "admin", Map.of());

        assertThat(response.activeVersion()).isEqualTo("1.2.0");
        assertThat(response.skill().displayName()).isEqualTo("网页新标题");
        assertThat(response.skill().summary()).isEqualTo("待审摘要");
        assertThat(response.skill().ownerPreviewVersion().displayName()).isEqualTo("网页新标题");
        assertThat(response.versions().get(0).syncRevision()).isEqualTo(3);
        assertThat(response.versions().get(1).syncRevision()).isNull();

        assertThat(response.downloadUrl()).isEqualTo("/api/v1/reviews/42/download");
        assertThat(response.documentationPath()).isEqualTo("README.md");
        assertThat(response.documentationContent()).isEqualTo("# demo");
        assertThat(response.skill().namespace()).isEqualTo("team-a");
        assertThat(response.versions()).extracting("version").containsExactly("1.2.0", "1.1.0");
        assertThat(response.versions().get(0).downloadAvailable()).isTrue();
        assertThat(response.versions().get(0).complianceSnapshot().items().get(0).controlId()).isEqualTo("T1059");
    }

    @Test
    void getReviewSkillDetail_keepsReviewBoundVersionWhenPublishedVersionAlsoExists() {
        ReviewTask task = createReviewTask(42L, 101L, 20L, "submitter");
        Namespace namespace = createNamespace(20L, "team-a");
        Skill skill = createSkill(101L, 20L, "skill-a");
        SkillVersion pending = createVersion(101L, 101L, "2.0.0-rc1", SkillVersionStatus.PENDING_REVIEW);
        SkillVersion published = createVersion(100L, 101L, "1.9.0", SkillVersionStatus.PUBLISHED);

        given(reviewTaskRepository.findById(42L)).willReturn(Optional.of(task));
        given(namespaceRepository.findById(20L)).willReturn(Optional.of(namespace));
        given(rbacService.getUserRoleCodes("admin")).willReturn(Set.of("SKILL_ADMIN"));
        given(reviewService.canViewReview(task, "admin", namespace.getType(), Map.of(), Set.of("SKILL_ADMIN"))).willReturn(true);
        given(skillQueryService.getReviewSkillSnapshot(101L)).willReturn(
                new SkillQueryService.ReviewSkillSnapshotDTO(
                        skill,
                        "Owner",
                        pending,
                        published,
                        List.of(published, pending),
                        List.of(),
                        null,
                        null
                )
        );

        ReviewSkillDetailResponse response = service.getReviewSkillDetail(42L, "admin", Map.of());

        assertThat(response.activeVersion()).isEqualTo("2.0.0-rc1");
        assertThat(response.skill().headlineVersion().version()).isEqualTo("2.0.0-rc1");
        assertThat(response.skill().publishedVersion().version()).isEqualTo("1.9.0");
    }

    @Test
    void downloadReviewPackage_delegatesToReviewDownloadFlow() {
        ReviewTask task = createReviewTask(42L, 101L, 20L, "submitter");
        Namespace namespace = createNamespace(20L, "team-a");
        Skill skill = createSkill(101L, 20L, "skill-a");
        SkillVersion pending = createVersion(101L, 101L, "1.2.0", SkillVersionStatus.PENDING_REVIEW);
        setField(pending, "githubSyncRevision", 3);
        SkillDownloadService.DownloadResult result = new SkillDownloadService.DownloadResult(
                () -> new ByteArrayInputStream("zip".getBytes()),
                "skill-a-1.2.0.zip",
                3L,
                "application/zip",
                null,
                true
        );

        given(reviewTaskRepository.findById(42L)).willReturn(Optional.of(task));
        given(namespaceRepository.findById(20L)).willReturn(Optional.of(namespace));
        given(rbacService.getUserRoleCodes("admin")).willReturn(Set.of("SKILL_ADMIN"));
        given(reviewService.canViewReview(task, "admin", namespace.getType(), Map.of(), Set.of("SKILL_ADMIN"))).willReturn(true);
        given(skillQueryService.getReviewSkillSnapshot(101L, false)).willReturn(
                new SkillQueryService.ReviewSkillSnapshotDTO(
                        skill,
                        "Owner",
                        pending,
                        null,
                        List.of(pending),
                        List.of(),
                        null,
                        null
                )
        );
        given(skillDownloadService.downloadReviewVersion(skill, pending)).willReturn(result);

        SkillDownloadService.DownloadResult downloadResult = service.downloadReviewPackage(42L, "admin", Map.of());

        assertThat(downloadResult.filename()).isEqualTo("skill-a-1.2.0.zip");
        assertThat(downloadResult.contentLength()).isEqualTo(3L);
    }

    @Test
    void visibilityReviewAllowsAuthorizedReviewerToInspectTheBoundPrivatePackage() throws Exception {
        Skill skill = createSkill(101L, 20L, "skill-a");
        skill.setVisibility(SkillVisibility.PRIVATE); skill.setLatestVersionId(100L);
        ReviewTask task = ReviewTask.forVisibility(skill, SkillVisibility.PUBLIC, "owner-1");
        task.setVisibilityScanTaskId("scope-scan"); setField(task, "id", 42L);
        Namespace namespace = createNamespace(20L, "team-a");
        SkillVersion published = createVersion(100L, 101L, "1.0.0", SkillVersionStatus.PUBLISHED);
        var audits = org.mockito.Mockito.mock(com.iflytek.skillhub.domain.security.SecurityAuditRepository.class);
        var scan = new com.iflytek.skillhub.domain.security.SecurityAudit(100L,
                com.iflytek.skillhub.domain.security.ScannerType.SKILL_SCANNER, "scope-scan");
        scan.setScannedAt(Instant.now()); scan.setIsSafe(true);
        service.configureSecurityAudits(audits);
        given(audits.findLatestActiveByVersionIdAndScannerType(100L,
                com.iflytek.skillhub.domain.security.ScannerType.SKILL_SCANNER)).willReturn(Optional.of(scan));
        given(reviewTaskRepository.findById(42L)).willReturn(Optional.of(task));
        given(namespaceRepository.findById(20L)).willReturn(Optional.of(namespace));
        given(rbacService.getUserRoleCodes("admin")).willReturn(Set.of("SKILL_ADMIN"));
        given(reviewService.canViewReview(task, "admin", namespace.getType(), Map.of(), Set.of("SKILL_ADMIN"))).willReturn(true);
        var snapshot = new SkillQueryService.ReviewSkillSnapshotDTO(
                skill, "Owner", published, published, List.of(published), List.of(createFile(5L, 100L, "SKILL.md")), "SKILL.md", "private package");
        given(skillQueryService.getReviewSkillSnapshot(100L)).willReturn(snapshot);
        given(skillQueryService.getReviewSkillSnapshot(100L, false)).willReturn(snapshot);
        given(skillQueryService.getFileContentByVersionId(100L, "SKILL.md"))
                .willReturn(new ByteArrayInputStream("private package".getBytes()));
        var download = new SkillDownloadService.DownloadResult(() -> new ByteArrayInputStream(new byte[]{1}), "review.zip", 1, "application/zip", null, true);
        given(skillDownloadService.downloadReviewVersion(skill, published)).willReturn(download);
        var detail = service.getReviewSkillDetail(42L, "admin", Map.of());
        assertThat(detail.documentationContent()).isEqualTo("private package");
        assertThat(detail.files()).hasSize(1);
        assertThat(detail.visibilityChange().scanStatus()).isEqualTo("PASSED");
        assertThat(service.getReviewFileContent(42L, "SKILL.md", "admin", Map.of()).readAllBytes()).isEqualTo("private package".getBytes());
        assertThat(service.downloadReviewPackage(42L, "admin", Map.of()).filename()).isEqualTo("review.zip");
        assertThat(service.getReviewSkillDetail(42L, "admin", Map.of(), false).visibilityChange().requestedVisibility()).isEqualTo("PUBLIC");
    }

    @Test
    void getReviewSkillDetail_rejectsUnauthorizedUser() {
        ReviewTask task = createReviewTask(42L, 101L, 20L, "submitter");
        Namespace namespace = createNamespace(20L, "team-a");

        given(reviewTaskRepository.findById(42L)).willReturn(Optional.of(task));
        given(namespaceRepository.findById(20L)).willReturn(Optional.of(namespace));
        given(rbacService.getUserRoleCodes("user-9")).willReturn(Set.of());
        given(reviewService.canViewReview(task, "user-9", namespace.getType(), Map.of(), Set.of())).willReturn(false);

        assertThatThrownBy(() -> service.getReviewSkillDetail(42L, "user-9", Map.of()))
                .isInstanceOf(DomainForbiddenException.class);
    }

    @Test
    void removalReadinessBecomesFalseWhenAvatarChangesAfterSubmission() {
        service.configureAvatars(skills, candidates);
        var task = new ReviewTask(205L, 101L, 20L, "1.0.1", "owner-1");
        setField(task, "id", 42L);
        task.prepareAvatarRemoval(21L, null);
        var namespace = createNamespace(20L, "team-a");
        var skill = createSkill(101L, 20L, "skill-a");
        skill.activateAvatar(21L, SkillVisibility.PUBLIC);
        var version = createVersion(205L, 101L, "1.0.1", SkillVersionStatus.PENDING_REVIEW);
        given(reviewTaskRepository.findById(42L)).willReturn(Optional.of(task));
        given(namespaceRepository.findById(20L)).willReturn(Optional.of(namespace));
        given(rbacService.getUserRoleCodes("admin")).willReturn(Set.of("SKILL_ADMIN"));
        given(reviewService.canViewReview(task, "admin", namespace.getType(), Map.of(), Set.of("SKILL_ADMIN"))).willReturn(true);
        given(skills.findById(101L)).willReturn(Optional.of(skill));
        given(skillQueryService.getReviewSkillSnapshot(205L)).willReturn(new SkillQueryService.ReviewSkillSnapshotDTO(
                skill, "Owner", version, null, List.of(version), List.of(), null, null));
        assertThat(service.getReviewSkillDetail(42L, "admin", Map.of()).avatarChange().readyForApproval()).isTrue();
        skill.activateAvatar(22L, SkillVisibility.PUBLIC);
        assertThat(service.getReviewSkillDetail(42L, "admin", Map.of()).avatarChange().readyForApproval()).isFalse();
    }

    private ReviewTask createReviewTask(Long reviewId, Long versionId, Long namespaceId, String submittedBy) {
        ReviewTask task = new ReviewTask(versionId, namespaceId, submittedBy);
        setField(task, "id", reviewId);
        setField(task, "status", ReviewTaskStatus.PENDING);
        return task;
    }

    private Namespace createNamespace(Long id, String slug) {
        Namespace namespace = new Namespace(slug, "Team A", "owner-1");
        setField(namespace, "id", id);
        return namespace;
    }

    private Skill createSkill(Long id, Long namespaceId, String slug) {
        Skill skill = new Skill(namespaceId, slug, "owner-1", SkillVisibility.PUBLIC);
        setField(skill, "id", id);
        skill.setDisplayName("Skill A");
        skill.setSummary("Summary");
        setField(skill, "downloadCount", 8L);
        setField(skill, "starCount", 2);
        setField(skill, "ratingAvg", new BigDecimal("4.5"));
        setField(skill, "ratingCount", 3);
        return skill;
    }

    private SkillVersion createVersion(Long id, Long skillId, String version, SkillVersionStatus status) {
        SkillVersion skillVersion = new SkillVersion(skillId, version, "owner-1");
        setField(skillVersion, "id", id);
        setField(skillVersion, "status", status);
        setField(skillVersion, "publishedAt", Instant.parse("2026-03-19T00:00:00Z"));
        setField(skillVersion, "createdAt", Instant.parse("2026-03-19T00:00:00Z"));
        setField(skillVersion, "fileCount", 1);
        setField(skillVersion, "totalSize", 100L);
        return skillVersion;
    }

    private SkillFile createFile(Long id, Long versionId, String filePath) {
        SkillFile file = new SkillFile(versionId, filePath, 123L, "text/markdown", "sha", "skills/demo/" + filePath);
        setField(file, "id", id);
        return file;
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
