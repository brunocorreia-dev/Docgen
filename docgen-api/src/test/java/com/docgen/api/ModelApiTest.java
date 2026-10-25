package com.docgen.api;

import com.docgen.ModelPullException;
import com.docgen.hardware.HardwareProfile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Ollama status, hardware, catalog and the model manager, against the in-memory Ollama. */
class ModelApiTest extends ApiTestSupport {
    @Autowired
    ActivityTracker activity;

    private JsonNode pull(String token, String model, int status) throws Exception {
        return json(authed(post("/api/v1/models/pulls"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"model\":\"" + model + "\"}"), status);
    }

    private JsonNode awaitPull(String token, String id) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            JsonNode view = json(authed(get("/api/v1/models/pulls/" + id), token), 200);
            if (!view.path("state").asString().equals("RUNNING")) {
                return view;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the download never finished");
    }

    @Test
    void ollamaStatusSaysWhetherItAnswers() throws Exception {
        String token = token();
        JsonNode up = json(authed(get("/api/v1/ollama/status"), token), 200);
        assertTrue(up.path("reachable").asBoolean());
        assertEquals("0.32.11", up.path("version").asString());
        assertEquals("127.0.0.1:11434", up.path("address").asString());

        ollama.reachable(false);
        JsonNode down = json(authed(get("/api/v1/ollama/status"), token), 200);
        assertFalse(down.path("reachable").asBoolean(), "an Ollama that is down is a status, never an error page");
        assertTrue(down.path("version").isNull());
    }

    @Test
    void theHardwareDiagnosisReportsWhatWasNotDetectedAsNull() throws Exception {
        String token = token();
        hardware.set(new HardwareProfile("Linux", null, "x86_64", null, null, null, 8L << 30, null, List.of(), false,
                new HardwareProfile.Storage(null, 100L << 30, false)));

        JsonNode view = json(authed(get("/api/v1/hardware"), token), 200);

        assertEquals("Linux", view.path("hardware").path("osName").asString());
        assertTrue(view.path("hardware").path("cpuModel").isNull());
        assertTrue(view.path("hardware").path("ramAvailableBytes").isNull());
        assertFalse(view.path("hardware").path("modelStorage").path("exact").asBoolean());
        assertTrue(view.path("ollama").path("reachable").asBoolean());
    }

    @Test
    void theCatalogRecommendsForThisMachineAndMarksInstalledModels() throws Exception {
        String token = token();
        ollama.install("qwen2.5-coder:7b", false);

        JsonNode catalog = json(authed(get("/api/v1/models/catalog"), token), 200);

        assertEquals(16, catalog.path("models").size());
        assertEquals("qwen3.5:9b", catalog.path("suggestedGeneral").asString());
        assertEquals("qwen2.5-coder:7b", catalog.path("suggestedCode").asString());
        assertEquals("DEDICATED", catalog.path("budget").path("gpu").asString());
        assertEquals(8_192, catalog.path("contextTokens").asInt());
        JsonNode coder = find(catalog.path("models"), "tag", "qwen2.5-coder:7b");
        assertTrue(coder.path("installed").asBoolean());
        assertEquals("RECOMMENDED", coder.path("fit").asString());
        assertTrue(coder.path("reason").asString().startsWith("Cabe inteiro na placa de vídeo"));
        assertEquals(5_756_632_960L, coder.path("estimate").path("totalBytes").asLong());
        assertEquals("GPU_OFFLOAD", find(catalog.path("models"), "tag", "qwen3:14b").path("fit").asString());
    }

    @Test
    void recommendationsWorkWhileOllamaIsDown() throws Exception {
        String token = token();
        ollama.reachable(false);

        JsonNode catalog = json(authed(get("/api/v1/models/catalog"), token), 200);

        assertFalse(catalog.path("ollamaReachable").asBoolean());
        assertEquals(16, catalog.path("models").size());
    }

    @Test
    void installedModelsSayWhichOnesDocGenMayUse() throws Exception {
        String token = token();
        ollama.install("qwen3:8b", false).install("llama3.2:latest", false).install("docgen-probe-remote:latest", true)
                .load("qwen3:8b", 7_000_000_000L, 5_000_000_000L);

        JsonNode list = json(authed(get("/api/v1/models"), token), 200);

        JsonNode qwen = find(list.path("models"), "name", "qwen3:8b");
        assertTrue(qwen.path("usable").asBoolean());
        assertEquals(5_000_000_000L, qwen.path("loaded").path("vramBytes").asLong(), "partly offloaded to the CPU");
        JsonNode llama = find(list.path("models"), "name", "llama3.2:latest");
        assertFalse(llama.path("inCatalog").asBoolean());
        assertFalse(llama.path("usable").asBoolean(), "outside the catalog (ADR 0003, layer 1)");
        JsonNode remote = find(list.path("models"), "name", "docgen-probe-remote:latest");
        assertTrue(remote.path("remote").asBoolean());
        assertFalse(remote.path("usable").asBoolean());
        assertTrue(list.path("defaultModel").isNull());
    }

    @Test
    void ollamaBeingDownIsAFriendlyErrorNotAStackTrace() throws Exception {
        String token = token();
        ollama.reachable(false);

        JsonNode error = json(authed(get("/api/v1/models"), token), 503);

        assertEquals("ollama_unavailable", error.path("code").asString());
        assertEquals("Ollama is not reachable on this computer.", error.path("message").asString());
        assertFalse(error.toString().contains("Exception"));
    }

    @Test
    void detailsAndDeletionOfAnInstalledModel() throws Exception {
        String token = token();
        ollama.install("qwen3:8b", false);

        JsonNode details = json(authed(get("/api/v1/models/details").param("name", "qwen3:8b"), token), 200);
        assertEquals("qwen3", details.path("architecture").asString());
        assertTrue(details.path("inCatalog").asBoolean());
        assertEquals("model_not_installed", json(authed(get("/api/v1/models/details").param("name", "qwen3:14b"),
                token), 404).path("code").asString());
        json(authed(get("/api/v1/models/details").param("name", "../etc/passwd x"), token), 400);

        json(authed(delete("/api/v1/models").param("name", "qwen3:8b"), token), 204);
        assertTrue(ollama.calls().contains("delete qwen3:8b"));
        assertEquals("model_not_installed",
                json(authed(delete("/api/v1/models").param("name", "qwen3:8b"), token), 404).path("code").asString());
    }

    @Test
    void theQuickTestLoadsTheModelAndReturnsOnlyTheAnswer() throws Exception {
        String token = token();
        ollama.install("qwen3:8b", false).answers("Olá!\u0007 Estou funcionando.‮");

        JsonNode result = json(authed(post("/api/v1/models/test"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"model\":\"qwen3:8b\"}"), 200);

        assertEquals("Olá! Estou funcionando.", result.path("answer").asString(), "control and bidi characters are gone");
        assertFalse(result.toString().contains("raciocínio"), "reasoning never reaches the answer");
        assertEquals(1_200, result.path("loadMillis").asLong());
        assertEquals(100.0, result.path("tokensPerSecond").asDouble(), 0.01);
        var chat = ollama.chats.getFirst();
        assertEquals(ModelService.TEST_CONTEXT_TOKENS, chat.contextTokens());
        assertFalse(chat.thinking());
    }

    @Test
    void theQuickTestOnlyRunsCatalogModelsThatAreLocal() throws Exception {
        String token = token();
        ollama.install("llama3.2:latest", false).install("qwen3:14b", true);

        assertEquals("not_in_catalog", testModel(token, "llama3.2:latest", 422).path("code").asString());
        assertEquals("remote_model_refused", testModel(token, "qwen3:14b", 422).path("code").asString());
        assertEquals("model_not_installed", testModel(token, "qwen3:8b", 404).path("code").asString());
        ollama.reachable(false);
        assertEquals("ollama_unavailable", testModel(token, "qwen3:8b", 503).path("code").asString());
        assertTrue(ollama.chats.isEmpty());
    }

    private JsonNode testModel(String token, String model, int status) throws Exception {
        return json(authed(post("/api/v1/models/test"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"model\":\"" + model + "\"}"), status);
    }

    @Test
    void aDownloadReportsOllamasProgressUntilItSucceeds() throws Exception {
        String token = token();

        JsonNode started = pull(token, "qwen3:8b", 202);
        assertEquals("RUNNING", started.path("state").asString());
        assertEquals(5_250_000_000L, started.path("expectedBytes").asLong());

        JsonNode done = awaitPull(token, started.path("id").asString());
        assertEquals("SUCCEEDED", done.path("state").asString());
        assertEquals("SUCCESS", done.path("stage").asString());
        assertEquals(1_000, done.path("completedBytes").asLong());
        assertEquals(1_000, done.path("totalBytes").asLong());
        assertEquals(started.path("id").asString(),
                json(authed(get("/api/v1/models/pulls"), token), 200).path("pulls").get(0).path("id").asString());
        assertTrue(find(json(authed(get("/api/v1/models/catalog"), token), 200).path("models"), "tag", "qwen3:8b")
                .path("installed").asBoolean());
    }

    @Test
    void onlyCatalogTagsAreEverDownloaded() throws Exception {
        String token = token();
        for (String model : new String[]{"llama3.2:latest", "gpt-oss:120b-cloud", "registry.example/qwen3:8b", "qwen3:4b"}) {
            assertEquals("not_in_catalog", pull(token, model, 422).path("code").asString(), model);
        }
        json(authed(post("/api/v1/models/pulls"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"model\":\"bad name\"}"), 400);
        assertTrue(ollama.calls().stream().noneMatch(call -> call.startsWith("pull")));
    }

    @Test
    void downloadsAreRefusedWhenTheyCannotWork() throws Exception {
        String token = token();
        ollama.install("qwen3:8b", false);
        assertEquals("already_installed", pull(token, "qwen3:8b", 409).path("code").asString());

        hardware.set(new HardwareProfile("Linux", null, "x86_64", null, null, null, 32L << 30, 24L << 30, List.of(),
                false, new HardwareProfile.Storage("/models", 3_000_000_000L, true)));
        assertEquals("insufficient_disk", pull(token, "qwen3:14b", 507).path("code").asString());

        hardware.reset();
        ollama.reachable(false);
        assertEquals("ollama_unavailable", pull(token, "qwen3:14b", 503).path("code").asString());
    }

    @Test
    void cancellingADownloadStopsItAndOnlyOneRunsAtATime() throws Exception {
        String token = token();
        CountDownLatch started = new CountDownLatch(1);
        ollama.pulls(FakeOllama.blockingPull(started));

        String id = pull(token, "qwen3:8b", 202).path("id").asString();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertTrue(activity.busy(), "a download keeps the app from its idle shutdown");
        assertEquals("pull_in_progress", pull(token, "qwen3:14b", 409).path("code").asString());
        assertEquals("model_busy", json(authed(delete("/api/v1/models").param("name", "qwen3:8b"), token), 409)
                .path("code").asString());
        JsonNode downloading = json(authed(get("/api/v1/models/pulls/" + id), token), 200);
        assertEquals(250, downloading.path("completedBytes").asLong());

        json(authed(delete("/api/v1/models/pulls/" + id), token), 202);

        JsonNode cancelled = awaitPull(token, id);
        assertEquals("CANCELLED", cancelled.path("state").asString());
        assertFalse(activity.busy());
        assertEquals("pull_finished", json(authed(delete("/api/v1/models/pulls/" + id), token), 409).path("code").asString());
        json(authed(get("/api/v1/models/pulls/" + java.util.UUID.randomUUID()), token), 404);
    }

    @Test
    void aFailedDownloadSaysWhyWithAStableCode() throws Exception {
        String token = token();
        ollama.pulls((model, listener, cancellation) -> {
            throw new ModelPullException(ModelPullException.Reason.REGISTRY_UNREACHABLE);
        });

        JsonNode failed = awaitPull(token, pull(token, "qwen3:8b", 202).path("id").asString());

        assertEquals("FAILED", failed.path("state").asString());
        assertEquals("registry_unreachable", failed.path("error").path("code").asString());
    }

    private static JsonNode find(JsonNode array, String field, String value) {
        for (JsonNode node : array) {
            if (node.path(field).asString().equals(value)) {
                return node;
            }
        }
        throw new AssertionError(value + " not found in " + array);
    }
}
