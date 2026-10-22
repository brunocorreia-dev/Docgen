package com.docgen.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
@AutoConfigureMockMvc
class DocGenApiApplicationTest {
    private static final Path AUTHORIZED_REPOSITORY = createRepository(false);
    private static final Path BROKEN_REPOSITORY = createRepository(true);

    @Autowired
    MockMvc mockMvc;

    @DynamicPropertySource
    static void repositories(DynamicPropertyRegistry registry) {
        registry.add("docgen.repositories[authorized-repo]", AUTHORIZED_REPOSITORY::toString);
        registry.add("docgen.repositories[broken-repo]", BROKEN_REPOSITORY::toString);
    }

    @Test
    void applicationContextStarts() {
    }

    @Test
    void healthIsPublicAndSerializesTheApiDto() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("available"));
    }

    @Test
    void listsStaticProviderMetadataWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers[*].id", containsInAnyOrder("groq", "ollama")))
                .andExpect(jsonPath("$.providers[?(@.id == 'groq')].remote").value(true))
                .andExpect(jsonPath("$.providers[?(@.id == 'ollama')].remote").value(false));
    }

    @Test
    void analysisRequiresValidBasicAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/repositories/authorized-repo/analysis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Basic realm=\"docgen-local\""))
                .andExpect(jsonPath("$.code").value("authentication_required"));

        mockMvc.perform(post("/api/v1/repositories/authorized-repo/analysis")
                        .with(httpBasic("test-analyst", "wrong-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void analysisRequiresTheAnalystRole() throws Exception {
        mockMvc.perform(post("/api/v1/repositories/authorized-repo/analysis")
                        .with(user("viewer").roles("VIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("access_denied"));
    }

    @Test
    void analyzesOnlyARegisteredRepositoryThroughDocumentationService() throws Exception {
        String response = mockMvc.perform(post("/api/v1/repositories/authorized-repo/analysis")
                        .with(httpBasic("test-analyst", "test-password-change-me"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"includeExtensions\":[\"java\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositoryId").value("authorized-repo"))
                .andExpect(jsonPath("$.includedFileCount").value(1))
                .andExpect(jsonPath("$.totalCharacters").value(12))
                .andExpect(jsonPath("$.truncatedFileCount").value(0))
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertFalse(response.contains(AUTHORIZED_REPOSITORY.toString()));
        org.junit.jupiter.api.Assertions.assertFalse(response.contains("App.java"));
        org.junit.jupiter.api.Assertions.assertFalse(response.contains("class App"));
    }

    @Test
    void returnsNotFoundForAnUnregisteredOpaqueId() throws Exception {
        mockMvc.perform(post("/api/v1/repositories/missing-repo/analysis")
                        .with(httpBasic("test-analyst", "test-password-change-me"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("repository_not_found"));
    }

    @Test
    void rejectsMaliciousPathFieldsAndExtensionValues() throws Exception {
        mockMvc.perform(post("/api/v1/repositories/authorized-repo/analysis")
                        .with(httpBasic("test-analyst", "test-password-change-me"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"/etc\",\"includeExtensions\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));

        mockMvc.perform(post("/api/v1/repositories/authorized-repo/analysis")
                        .with(httpBasic("test-analyst", "test-password-change-me"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"includeExtensions\":[\"../pem\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
    }

    @Test
    void rejectsOversizedBodiesAfterAuthentication() throws Exception {
        String oversized = "{\"padding\":\"" + "x".repeat(RequestPolicyFilter.MAX_ANALYSIS_REQUEST_BYTES) + "\"}";

        mockMvc.perform(post("/api/v1/repositories/authorized-repo/analysis")
                        .with(httpBasic("test-analyst", "test-password-change-me"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("request_too_large"));
    }

    @Test
    void sanitizesInternalAnalysisFailures() throws Exception {
        String response = mockMvc.perform(post("/api/v1/repositories/broken-repo/analysis")
                        .with(httpBasic("test-analyst", "test-password-change-me"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("internal_error"))
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertFalse(response.contains(BROKEN_REPOSITORY.toString()));
        org.junit.jupiter.api.Assertions.assertFalse(response.contains("broken.txt"));
        org.junit.jupiter.api.Assertions.assertFalse(response.contains("CharacterCodingException"));
    }

    private static Path createRepository(boolean malformedUtf8) {
        try {
            Path repository = Files.createTempDirectory("docgen-api-test-");
            if (malformedUtf8) {
                Files.write(repository.resolve("broken.txt"), new byte[]{(byte) 0xC3, (byte) 0x28});
            } else {
                Files.writeString(repository.resolve("App.java"), "class App {}");
                Files.writeString(repository.resolve("notes.md"), "notes");
            }
            return repository.toAbsolutePath().normalize();
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
