package com.docgen.api;

import com.docgen.LLMProviderFactory;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkflowApiTest extends ApiTestSupport {
    private JsonNode startPlan(String token, String project, String body, int expected) throws Exception {
        return json(authed(post("/api/v1/projects/" + project + "/plans"), token)
                .contentType(MediaType.APPLICATION_JSON).content(body), expected);
    }

    private String plannedProject(String token) throws Exception {
        String project = createProject(token, "ollama");
        FakeLlm.enqueue(prompt -> validPlanJson());
        JsonNode job = awaitJob(token, startPlan(token, project, "{}", 202).path("id").asString());
        assertEquals("SUCCEEDED", job.path("status").asString(), job.toString());
        return project;
    }

    @Test
    void endToEndPlanningReviewDocumentationEditingAndExport() throws Exception {
        String token = token();
        String project = createProject(token, "ollama");
        json(authed(post("/api/v1/projects/" + project + "/attachments"), token)
                .contentType("application/pdf").header(AttachmentController.NAME_HEADER, "spec.pdf")
                .content(TestPdfs.withText("O sistema deve emitir alertas de estoque baixo.")), 201);

        FakeLlm.enqueue(prompt -> validPlanJson());
        JsonNode queued = startPlan(token, project, "{}", 202);
        assertEquals("ARCHITECTURE_PLAN", queued.path("type").asString());
        assertTrue(queued.path("status").asString().matches("QUEUED|RUNNING|SUCCEEDED"));
        JsonNode job = awaitJob(token, queued.path("id").asString());
        assertEquals("SUCCEEDED", job.path("status").asString());
        assertTrue(FakeLlm.prompts().getFirst().contains("alertas de estoque baixo"), "PDF text reaches the prompt");
        assertTrue(FakeLlm.prompts().getFirst().contains("<attached_documents>"));

        String planId = job.path("resultId").asString();
        JsonNode plan = json(authed(get("/api/v1/projects/" + project + "/plans/" + planId), token), 200);
        assertEquals("DRAFT", plan.path("status").asString());
        assertEquals("AI_GENERATED", plan.path("origin").asString());
        assertTrue(plan.path("requiresHumanReview").asBoolean());
        assertEquals(3, plan.path("plan").path("components").size());
        assertEquals("USER", plan.path("plan").path("functionalRequirements").path(0).path("source").asString());
        assertTrue(plan.path("mermaid").asString().startsWith("flowchart LR"));
        assertEquals(1, json(authed(get("/api/v1/projects/" + project + "/plans"), token), 200).path("plans").size());

        String docRequest = "{\"planId\":\"" + planId + "\"}";
        mvc.perform(authed(post("/api/v1/projects/" + project + "/documentation"), token)
                        .contentType(MediaType.APPLICATION_JSON).content(docRequest))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("plan_not_approved"));

        json(authed(post("/api/v1/projects/" + project + "/plans/" + planId + "/approval"), token)
                .contentType(MediaType.APPLICATION_JSON).content("{}"), 200);

        FakeLlm.enqueue(prompt -> "# Inventory API\n\nProjeto em planejamento.",
                prompt -> "# Arquitetura\n\nProposta em camadas.");
        JsonNode docJob = awaitJob(token, json(authed(post("/api/v1/projects/" + project + "/documentation"), token)
                .contentType(MediaType.APPLICATION_JSON).content(docRequest), 202).path("id").asString());
        assertEquals("SUCCEEDED", docJob.path("status").asString(), docJob.toString());

        JsonNode artifacts = json(authed(get("/api/v1/projects/" + project + "/artifacts"), token), 200)
                .path("artifacts");
        assertEquals(3, artifacts.size());
        Map<String, String> ids = new HashMap<>();
        artifacts.forEach(item -> ids.put(item.path("kind").asString(), item.path("id").asString()));
        assertTrue(json(authed(get("/api/v1/artifacts"), token), 200).path("artifacts").size() >= 3);

        String readmeUrl = "/api/v1/projects/" + project + "/artifacts/" + ids.get("README");
        JsonNode readme = json(authed(get(readmeUrl), token), 200);
        assertEquals("# Inventory API\n\nProjeto em planejamento.\n", readme.path("content").asString());
        assertEquals(1, readme.path("revision").asInt());

        JsonNode edited = json(authed(put(readmeUrl), token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"# Inventory API\\n\\nRevisado por uma pessoa.\",\"expectedRevision\":1}"), 200);
        assertEquals(2, edited.path("revision").asInt());
        assertEquals("AI_GENERATED_HUMAN_EDITED", edited.path("origin").asString());

        mvc.perform(authed(put(readmeUrl), token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"# x\\n\\nstale\",\"expectedRevision\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("revision_conflict"));
        mvc.perform(authed(put(readmeUrl), token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"# x\\n\\n<script>alert(1)</script>\",\"expectedRevision\":2}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("unsafe_markdown"));
        mvc.perform(authed(put("/api/v1/projects/" + project + "/artifacts/" + ids.get("DIAGRAM")), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"x\",\"expectedRevision\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("artifact_read_only"));

        mvc.perform(authed(get(readmeUrl + "/content"), token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/markdown;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("README.md")));

        MockHttpServletResponse zip = mvc.perform(authed(get("/api/v1/projects/" + project + "/export"), token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andReturn().getResponse();
        Map<String, String> entries = unzip(zip.getContentAsByteArray());
        assertEquals(java.util.Set.of("README.md", "ARCHITECTURE.md", "architecture.mmd", "architecture-plan.json"),
                entries.keySet());
        assertTrue(entries.get("README.md").contains("Revisado por uma pessoa"));
        assertTrue(entries.get("architecture.mmd").startsWith("flowchart LR"));
    }

    @Test
    void planningRunsLocallyWithoutAnyTransmissionPreviewOrConsent() throws Exception {
        String token = token();
        String project = createProject(token, "ollama");

        mvc.perform(authed(post("/api/v1/projects/" + project + "/transmission-previews"), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"operation\":\"PLAN\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(authed(post("/api/v1/projects/" + project + "/plans"), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"consentToken\":\"anything\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(authed(post("/api/v1/projects/" + project + "/plans"), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"provider\":\"remote-cloud\"}"))
                .andExpect(status().isBadRequest());
        assertTrue(FakeLlm.prompts().isEmpty());

        FakeLlm.enqueue(prompt -> validPlanJson());
        JsonNode job = awaitJob(token, startPlan(token, project, "{}", 202).path("id").asString());

        assertEquals("SUCCEEDED", job.path("status").asString(), job.toString());
        assertEquals("ollama", job.path("provider").asString());
        assertEquals(LLMProviderFactory.DEFAULT_MODEL, job.path("model").asString());
        assertEquals(java.util.Collections.singletonList(null), FakeLlm.models());
    }

    @Test
    void theRequestModelOverridesTheProjectModel() throws Exception {
        String token = token();
        String project = createProject(token, "ollama");
        FakeLlm.enqueue(prompt -> validPlanJson());

        JsonNode job = awaitJob(token, startPlan(token, project, "{\"model\":\"qwen2.5-coder:7b\"}", 202)
                .path("id").asString());

        assertEquals("qwen2.5-coder:7b", job.path("model").asString());
        assertEquals(java.util.List.of("qwen2.5-coder:7b"), FakeLlm.models());
    }

    @Test
    void invalidModelOutputFailsTheJobWithASanitizedError() throws Exception {
        String token = token();
        String project = createProject(token, "ollama");
        FakeLlm.enqueue(prompt -> "I refuse. Here is gsk_abcdefghijklmnopqrstuvwxyz123456 instead.");

        JsonNode job = awaitJob(token, startPlan(token, project, "{}", 202).path("id").asString());

        assertEquals("FAILED", job.path("status").asString());
        assertEquals("invalid_model_output", job.path("error").path("code").asString());
        assertFalse(job.toString().contains("gsk_"));
        assertEquals(0, json(authed(get("/api/v1/projects/" + project + "/plans"), token), 200).path("plans").size());
    }

    @Test
    void providerFailuresAreMappedToStableCodesWithoutEchoingDetails() throws Exception {
        String token = token();
        String project = createProject(token, "ollama");
        FakeLlm.enqueue(prompt -> {
            throw new java.io.IOException("Ollama request failed: secret prompt echo /home/user/private");
        });

        JsonNode job = awaitJob(token, startPlan(token, project, "{}", 202).path("id").asString());

        assertEquals("provider_failed", job.path("error").path("code").asString());
        assertFalse(job.toString().contains("/home/user/private"));
        assertFalse(job.toString().contains("secret prompt"));
    }

    @Test
    void modelAndContextFailuresHaveTheirOwnStableCodes() throws Exception {
        String token = token();
        String project = createProject(token, "ollama");
        FakeLlm.enqueue(prompt -> {
            throw new com.docgen.PromptTooLargeException("qwen2.5-coder:7b", 40_000, 32_768);
        }, prompt -> {
            throw new com.docgen.PromptTruncatedException(2_050, 109_951);
        }, prompt -> {
            throw new com.docgen.ModelNotInstalledException("qwen2.5-coder:7b");
        });

        JsonNode tooLarge = awaitJob(token, startPlan(token, project, "{}", 202).path("id").asString());
        JsonNode truncated = awaitJob(token, startPlan(token, project, "{}", 202).path("id").asString());
        JsonNode missing = awaitJob(token, startPlan(token, project, "{}", 202).path("id").asString());

        assertEquals("prompt_too_large", tooLarge.path("error").path("code").asString());
        assertEquals("prompt_truncated", truncated.path("error").path("code").asString());
        assertEquals("model_not_installed", missing.path("error").path("code").asString());
        assertEquals("The input does not fit the model's context window.",
                tooLarge.path("error").path("message").asString());
        assertFalse(tooLarge.toString().contains("accepts at most"), "exception text is never echoed");
    }

    @Test
    void unsafeGeneratedMarkdownIsRejected() throws Exception {
        String token = token();
        String project = plannedProject(token);
        String planId = json(authed(get("/api/v1/projects/" + project + "/plans"), token), 200)
                .path("plans").path(0).path("id").asString();
        json(authed(post("/api/v1/projects/" + project + "/plans/" + planId + "/approval"), token)
                .contentType(MediaType.APPLICATION_JSON).content("{}"), 200);
        FakeLlm.enqueue(prompt -> "# Doc\n\n<img src=x onerror=alert(1)>");

        JsonNode job = awaitJob(token, json(authed(post("/api/v1/projects/" + project + "/documentation"), token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"planId\":\"" + planId + "\"}"), 202)
                .path("id").asString());

        assertEquals("unsafe_model_output", job.path("error").path("code").asString());
        assertEquals(0, json(authed(get("/api/v1/projects/" + project + "/artifacts"), token), 200)
                .path("artifacts").size());
    }

    @Test
    void cancellingARunningJobInterruptsItAndDiscardsTheResult() throws Exception {
        String token = token();
        String project = createProject(token, "ollama");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeLlm.enqueue(FakeLlm.blocking(started, release, validPlanJson()));

        String jobId = startPlan(token, project, "{}", 202).path("id").asString();
        assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS));
        JsonNode cancelled = json(authed(post("/api/v1/jobs/" + jobId + "/cancellation"), token)
                .contentType(MediaType.APPLICATION_JSON).content("{}"), 200);
        release.countDown();

        assertEquals("CANCELLED", cancelled.path("status").asString());
        Thread.sleep(200);
        assertEquals("CANCELLED", json(authed(get("/api/v1/jobs/" + jobId), token), 200).path("status").asString());
        assertEquals(0, json(authed(get("/api/v1/projects/" + project + "/plans"), token), 200).path("plans").size());
        mvc.perform(authed(post("/api/v1/jobs/" + jobId + "/cancellation"), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("job_not_cancellable"));
    }

    @Test
    void limitsActiveJobsPerUserAndHidesJobsFromOtherUsers() throws Exception {
        String token = token();
        String project = createProject(token, "ollama");
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        FakeLlm.always(FakeLlm.blocking(started, release, validPlanJson()));
        String first = startPlan(token, project, "{}", 202).path("id").asString();
        startPlan(token, project, "{}", 202);
        startPlan(token, project, "{}", 202);
        try {
            mvc.perform(authed(post("/api/v1/projects/" + project + "/plans"), token)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("too_many_jobs"));

            String intruder = token(OTHER, OTHER_PASSWORD);
            mvc.perform(authed(get("/api/v1/jobs/" + first), intruder)).andExpect(status().isNotFound());
            mvc.perform(authed(post("/api/v1/jobs/" + first + "/cancellation"), intruder)
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
        } finally {
            release.countDown();
        }
        JsonNode jobs = json(authed(get("/api/v1/projects/" + project + "/jobs"), token), 200).path("jobs");
        for (JsonNode job : jobs) {
            awaitJob(token, job.path("id").asString());
        }
    }

    @Test
    void deletingAProjectCancelsItsJobs() throws Exception {
        String token = token();
        String project = createProject(token, "ollama");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeLlm.enqueue(FakeLlm.blocking(started, release, validPlanJson()));
        String jobId = startPlan(token, project, "{}", 202).path("id").asString();
        assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS));

        mvc.perform(authed(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/v1/projects/" + project), token)).andExpect(status().isNoContent());
        release.countDown();

        mvc.perform(authed(get("/api/v1/jobs/" + jobId), token)).andExpect(status().isNotFound());
    }

    @Test
    void exportNamesAreAsciiSlugs() {
        assertEquals("api-de-gestao-de-estoque", ArtifactService.slug("API de Gestão de Estoque"));
        assertEquals("project", ArtifactService.slug("../../\u202e"));
        assertTrue(ArtifactService.slug("x".repeat(80)).length() <= 40);
    }

    private static Map<String, String> unzip(byte[] bytes) throws Exception {
        Map<String, String> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                assertFalse(entry.getName().contains("/") || entry.getName().contains(".."));
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
