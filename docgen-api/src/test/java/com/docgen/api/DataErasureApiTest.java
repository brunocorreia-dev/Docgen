package com.docgen.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Settings → "Apagar todos os meus dados". */
class DataErasureApiTest extends ApiTestSupport {
    @Autowired
    StorageConfig.StoragePaths paths;

    @Test
    void erasesEveryLocalRowCancelsRunningWorkAndEmptiesTheStagingArea() throws Exception {
        String token = token();
        String project = createProject(token, "ollama");
        json(authed(post("/api/v1/projects/" + project + "/attachments"), token)
                .contentType("application/pdf").header(AttachmentController.NAME_HEADER, "a.pdf")
                .content(TestPdfs.withText("Requisito: relatório mensal")), 201);
        CountDownLatch started = new CountDownLatch(1);
        FakeLlm.enqueue(FakeLlm.blocking(started, new CountDownLatch(1), validPlanJson()));
        String jobId = json(authed(post("/api/v1/projects/" + project + "/plans"), token)
                .contentType(MediaType.APPLICATION_JSON).content("{}"), 202).path("id").asString();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        Files.writeString(paths.staging().resolve("leftover.part"), "x");
        String foreign = foreignProject();

        mvc.perform(authed(delete("/api/v1/data"), token)).andExpect(status().isNoContent());

        assertEquals(0, json(authed(get("/api/v1/projects"), token), 200).path("projects").size());
        for (String table : new String[]{"projects", "attachments", "plans", "artifacts", "jobs"}) {
            assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE owner = 'local'")
                    .query(Integer.class).single(), table);
        }
        mvc.perform(authed(get("/api/v1/jobs/" + jobId), token)).andExpect(status().isNotFound());
        assertFalse(Files.exists(paths.staging().resolve("leftover.part")));
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM projects WHERE id = :id").param("id", foreign)
                .query(Integer.class).single(), "only the local owner's rows are erased");
        mvc.perform(authed(get("/api/v1/session"), token)).andExpect(status().isOk());
    }

    @Test
    void erasingNeedsASessionAndTheAppsOwnOrigin() throws Exception {
        mvc.perform(delete("/api/v1/data")).andExpect(status().isUnauthorized());
        mvc.perform(authed(delete("/api/v1/data"), token()).header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
    }
}
