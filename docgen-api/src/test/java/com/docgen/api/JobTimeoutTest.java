package com.docgen.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@TestPropertySource(properties = "docgen.jobs.timeout=500ms")
class JobTimeoutTest extends ApiTestSupport {
    @Test
    void jobsThatExceedTheirDeadlineFailAndTheirResultIsDiscarded() throws Exception {
        String token = token();
        String project = createProject(token, "ollama");
        CountDownLatch started = new CountDownLatch(1);
        FakeLlm.enqueue(FakeLlm.blocking(started, new CountDownLatch(1), validPlanJson()));

        String jobId = json(authed(post("/api/v1/projects/" + project + "/plans"), token)
                .contentType(MediaType.APPLICATION_JSON).content("{}"), 202).path("id").asString();
        JsonNode job = awaitJob(token, jobId);

        assertEquals("FAILED", job.path("status").asString());
        assertEquals("timeout", job.path("error").path("code").asString());
        assertEquals(0, json(authed(get("/api/v1/projects/" + project + "/plans"), token), 200).path("plans").size());
    }
}
