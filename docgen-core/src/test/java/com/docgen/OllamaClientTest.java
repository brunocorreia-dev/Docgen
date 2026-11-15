package com.docgen;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The short Ollama operations against a loopback fake answering with real Ollama 0.32.11 responses. */
class OllamaClientTest {
    @Test
    void readsTheVersion() throws Exception {
        try (FakeOllamaServer ollama = new FakeOllamaServer()) {
            assertEquals("0.32.11", client(ollama).version());
        }
        try (FakeOllamaServer ollama = new FakeOllamaServer().respond("/api/version", 200, "{\"version\":\"0.32 \\u001b[2J\"}")) {
            assertThrows(IOException.class, () -> client(ollama).version());
        }
    }

    @Test
    void listsInstalledModelsAndFlagsTheRemoteOne() throws Exception {
        try (FakeOllamaServer ollama = new FakeOllamaServer().respond("/api/tags", 200, FakeOllamaServer.fixture("tags.json"))) {
            List<InstalledModel> models = client(ollama).installedModels();

            assertEquals(List.of("docgen-probe-remote:latest", "qwen2.5:14b", "gemma4:26b", "llama3.2:latest"),
                    models.stream().map(InstalledModel::name).toList());
            InstalledModel remote = models.getFirst();
            assertTrue(remote.remote(), "remote_host and remote_model mark a model that runs elsewhere");
            assertEquals(191, remote.sizeBytes());
            InstalledModel qwen = models.get(1);
            assertFalse(qwen.remote());
            assertEquals(8_988_124_069L, qwen.sizeBytes());
            assertEquals("qwen2", qwen.family());
            assertEquals("14.8B", qwen.parameterSize());
            assertEquals("Q4_K_M", qwen.quantization());
            assertEquals(32_768, qwen.contextLength());
            assertEquals(List.of("completion", "tools"), qwen.capabilities());
            assertTrue(models.get(2).capabilities().contains("thinking"));
            assertNull(models.get(2).contextLength(), "gemma4 does not report it in the list");
        }
    }

    @Test
    void cloudTagsAreRemoteEvenWithoutMetadata() throws Exception {
        String tags = "{\"models\":[{\"name\":\"gpt-oss:120b-cloud\",\"size\":384},{\"name\":\"gemma4:cloud\",\"size\":384},"
                + "{\"name\":\"qwen3:8b\",\"size\":5200000000}]}";
        try (FakeOllamaServer ollama = new FakeOllamaServer().respond("/api/tags", 200, tags)) {
            List<InstalledModel> models = client(ollama).installedModels();

            assertEquals(List.of(true, true, false), models.stream().map(InstalledModel::remote).toList());
        }
    }

    @Test
    void modelEntriesThatCannotBeNamedSafelyAreSkippedAndBrokenListsFail() throws Exception {
        String tags = "{\"models\":[{\"name\":\"../../etc\",\"size\":1},{\"name\":\"ok:1b\",\"size\":1}]}";
        try (FakeOllamaServer ollama = new FakeOllamaServer().respond("/api/tags", 200, tags)) {
            assertEquals(List.of("ok:1b"), client(ollama).installedModels().stream().map(InstalledModel::name).toList());
        }
        for (String broken : new String[]{"[]", "{\"models\":{}}", "{\"models\":[{\"name\":\"a:1\",\"size\":-1}]}",
                "{\"models\":[{\"name\":\"a:1\",\"size\":1,\"details\":{\"family\":7}}]}", "not json"}) {
            try (FakeOllamaServer ollama = new FakeOllamaServer().respond("/api/tags", 200, broken)) {
                assertThrows(IOException.class, () -> client(ollama).installedModels(), broken);
            }
        }
    }

    @Test
    void listsAreBoundedInCountAndBytes() throws Exception {
        String tooMany = IntStream.range(0, OllamaClient.MAX_MODELS + 1)
                .mapToObj(index -> "{\"name\":\"m" + index + ":1b\",\"size\":1}")
                .collect(Collectors.joining(",", "{\"models\":[", "]}"));
        try (FakeOllamaServer ollama = new FakeOllamaServer().respond("/api/tags", 200, tooMany)) {
            assertThrows(IOException.class, () -> client(ollama).installedModels());
        }
        String tooBig = "{\"models\":[],\"pad\":\"" + "x".repeat(OllamaClient.MAX_LIST_RESPONSE_BYTES) + "\"}";
        try (FakeOllamaServer ollama = new FakeOllamaServer().respond("/api/tags", 200, tooBig)) {
            IOException error = assertThrows(IOException.class, () -> client(ollama).installedModels());
            assertTrue(error.getMessage().contains("byte safety limit"), error.getMessage());
        }
    }

    @Test
    void describesAModelFromItsRealShowResponse() throws Exception {
        try (FakeOllamaServer ollama = new FakeOllamaServer().show("qwen2.5:14b", FakeOllamaServer.fixture("show-qwen2.5-14b.json"))) {
            ModelDescription model = client(ollama).describe("qwen2.5:14b");

            assertEquals("qwen2", model.architecture());
            assertEquals(32_768, model.contextLength());
            assertEquals(14_770_033_664L, model.parameterCount());
            assertEquals("Q4_K_M", model.quantization());
            assertEquals(48, model.layers());
            assertEquals(8, model.kvHeads());
            assertEquals(128, model.headDim(), "5120 / 40 attention heads");
            assertEquals(List.of("completion", "tools"), model.capabilities());
            assertFalse(model.thinking());
            assertFalse(model.remote());
            assertEquals("qwen2.5:14b", ollama.requestBodies().getFirst().path("model").asText());
        }
    }

    @Test
    void aRemoteStubIsRecognizedFromItsShowResponse() throws Exception {
        try (FakeOllamaServer ollama = new FakeOllamaServer().show("docgen-probe-remote:latest",
                FakeOllamaServer.fixture("show-remote-stub.json"))) {
            ModelDescription model = client(ollama).describe("docgen-probe-remote:latest");

            assertTrue(model.remote());
            assertNull(model.contextLength());
        }
        try (FakeOllamaServer ollama = new FakeOllamaServer()) {
            assertTrue(client(ollama).describe("gemma4:cloud").remote(), "the cloud tag alone is enough");
        }
    }

    @Test
    void describingOrDeletingAMissingModelSaysItIsNotInstalled() throws Exception {
        try (FakeOllamaServer ollama = new FakeOllamaServer().modelInstalled(false)) {
            ModelNotInstalledException error = assertThrows(ModelNotInstalledException.class,
                    () -> client(ollama).describe("qwen3:8b"));
            assertEquals("qwen3:8b", error.model());
            assertThrows(ModelNotInstalledException.class, () -> client(ollama).delete("qwen3:8b"));
        }
    }

    @Test
    void deletesWithTheDeleteMethod() throws Exception {
        try (FakeOllamaServer ollama = new FakeOllamaServer().deletable("qwen3:0.6b")) {
            client(ollama).delete("qwen3:0.6b");

            assertEquals("qwen3:0.6b", ollama.requestBodies().getFirst().path("model").asText());
            assertThrows(ModelNotInstalledException.class, () -> client(ollama).delete("qwen3:0.6b"),
                    "a second delete finds nothing, like Ollama's 404");
        }
    }

    @Test
    void listsLoadedModelsWithTheirGpuShare() throws Exception {
        try (FakeOllamaServer ollama = new FakeOllamaServer().respond("/api/ps", 200, FakeOllamaServer.fixture("ps.json"))) {
            LoadedModel loaded = client(ollama).loadedModels().getFirst();

            assertEquals("llama3.2:latest", loaded.name());
            assertEquals(2_554_708_622L, loaded.sizeBytes());
            assertEquals(loaded.sizeBytes(), loaded.vramBytes(), "fully on the GPU");
            assertEquals(4_096, loaded.contextLength());
        }
    }

    @Test
    void invalidModelNamesNeverReachOllama() throws Exception {
        try (FakeOllamaServer ollama = new FakeOllamaServer()) {
            for (String name : new String[]{null, "", " qwen", "a\nb", "-x", "x".repeat(101)}) {
                assertThrows(IllegalArgumentException.class, () -> client(ollama).describe(name), String.valueOf(name));
                assertThrows(IllegalArgumentException.class, () -> client(ollama).delete(name), String.valueOf(name));
            }
            assertTrue(ollama.requestedPaths().isEmpty());
        }
    }

    @Test
    void anUnexpectedStatusIsAnErrorWithoutTheBody() throws Exception {
        try (FakeOllamaServer ollama = new FakeOllamaServer().respond("/api/tags", 500, "{\"error\":\"secret detail\"}")) {
            IOException error = assertThrows(IOException.class, () -> client(ollama).installedModels());

            assertEquals("Ollama answered tags with HTTP 500.", error.getMessage());
        }
    }

    @Test
    void cloudNamesFollowOllamasTagConvention() {
        assertTrue(RemoteModels.isCloudName("gemma4:cloud"));
        assertTrue(RemoteModels.isCloudName("gpt-oss:120b-cloud"));
        assertTrue(RemoteModels.isCloudName("qwen3-coder:480B-CLOUD"));
        assertFalse(RemoteModels.isCloudName("qwen3:8b"));
        assertFalse(RemoteModels.isCloudName("cloud/model"));
        assertFalse(RemoteModels.isCloudName("cloudy:7b"));
        assertFalse(RemoteModels.isCloudName(null));
    }

    private static OllamaClient client(FakeOllamaServer ollama) {
        return new OllamaClient(ollama.endpoint());
    }
}
