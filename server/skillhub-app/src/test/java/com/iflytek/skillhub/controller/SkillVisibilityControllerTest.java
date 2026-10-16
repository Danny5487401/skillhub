package com.iflytek.skillhub.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.service.SkillVisibilityAppService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class SkillVisibilityControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockBean SkillVisibilityAppService visibility;

    @Test
    void changeReturnsCurrentAndPendingScopesWithoutReplacingThePackage() throws Exception {
        when(visibility.change(eq("team"), eq("foo"), eq(SkillVisibility.PUBLIC), eq("owner"), anyMap(), any()))
                .thenReturn(new SkillVisibilityAppService.Result(SkillVisibility.PRIVATE, SkillVisibility.PUBLIC, 42L));
        mvc.perform(post("/api/web/skills/team/foo/visibility").contentType("application/json")
                        .content("{\"visibility\":\"PUBLIC\"}").requestAttr("userId", "owner").requestAttr("userNsRoles", Map.of()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.data.requestedVisibility").value("PUBLIC"))
                .andExpect(jsonPath("$.data.reviewTaskId").value(42));
    }

    @Test
    void invalidOrMissingVisibilityIsRejected() throws Exception {
        for (String content : new String[] {"{}", "{\"visibility\":\"INVALID\"}"}) {
            mvc.perform(post("/api/web/skills/team/foo/visibility").contentType("application/json")
                            .content(content).requestAttr("userId", "owner").requestAttr("userNsRoles", Map.of()))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void openApiDescribesVisibilityChangeAndReviewFields() throws Exception {
        String json = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var schema = mapper.readTree(json);
        assertThat(schema.path("paths").has("/api/web/skills/{namespace}/{slug}/visibility")).isTrue();
        assertThat(schema.path("components").path("schemas").path("ReviewTaskResponse")
                .path("properties").has("requestedVisibility")).isTrue();
        assertThat(schema.path("components").path("schemas").path("SkillSummaryResponse")
                .path("properties").has("managementState")).isTrue();
        assertThat(schema.path("components").path("schemas").path("ManagementState")
                .path("properties").has("requestedVisibility")).isTrue();
        String output = System.getProperty("skillhub.openapi.output");
        if (output != null && !output.isBlank()) { Files.writeString(Path.of(output), json); }
    }
}
