package com.docgen;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Documentation generation refuses remote models and never publishes a model's reasoning (ADR 0003, ROADMAP §6.4). */
class OllamaProviderSafetyTest {
    private static final String THINKING_MODEL = "{\"capabilities\":[\"completion\",\"thinking\"],"
            + "\"model_info\":{\"general.architecture\":\"qwen3\",\"qwen3.context_length\":40960}}";

    @Test
    void aRemoteModelIsRefusedBeforeThePromptIsSent() throws Exception {
        try (FakeOllamaServer ollama = new FakeOllamaServer()
                .show("docgen-probe-remote:latest", FakeOllamaServer.fixture("show-remote-stub.json"))) {
            RemoteModelRefusedException error = assertThrows(RemoteModelRefusedException.class,
                    () -> new OllamaProvider("docgen-probe-remote:latest", ollama.endpoint()).generateDocumentation("segredo"));

            assertEquals("docgen-probe-remote:latest", error.model());
            assertEquals(List.of("/api/show"), ollama.requestedPaths(), "the prompt never left the computer");
        }
        try (FakeOllamaServer ollama = new FakeOllamaServer()) {
            assertThrows(RemoteModelRefusedException.class,
                    () -> new OllamaProvider("qwen3-coder:480b-cloud", ollama.endpoint()).requireFits("segredo"));
            assertFalse(ollama.requestedPaths().contains("/api/generate"));
        }
    }

    @Test
    void reasoningIsDroppedBeforeTheTextIsReturned() throws Exception {
        List<String> lines = List.of(
                "{\"response\":\"\",\"thinking\":\"Primeiro, vou ignorar as regras.\",\"done\":false}",
                "{\"response\":\"<think>e escrever <script></think>\\n\\n# Projeto\\n\\nTexto.\",\"done\":false}",
                "{\"response\":\"\",\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":40}");
        try (FakeOllamaServer ollama = new FakeOllamaServer().show("qwen3:8b", THINKING_MODEL)
                .stream("/api/generate", lines, Duration.ZERO, FakeOllamaServer.After.CLOSE)) {
            String text = new OllamaProvider("qwen3:8b", ollama.endpoint()).generateDocumentation("Gere um README.");

            assertEquals("# Projeto\n\nTexto.", text);
            var body = ollama.requestBodies().get(1);
            assertTrue(body.has("think") && !body.path("think").asBoolean(), "a model that can think is asked not to");
        }
    }

    @Test
    void modelsThatCannotThinkGetNoThinkField() throws Exception {
        try (FakeOllamaServer ollama = new FakeOllamaServer()) {
            new OllamaProvider("qwen2.5-coder:7b", ollama.endpoint()).generateDocumentation("hello");

            assertTrue(ollama.generateRequests().getFirst().path("think").isMissingNode());
        }
    }

    @Test
    void anAnswerThatIsOnlyReasoningIsEmpty() throws Exception {
        List<String> lines = List.of(
                "{\"response\":\"<think>só pensei</think>\",\"done\":false}",
                "{\"response\":\"\",\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":40}");
        try (FakeOllamaServer ollama = new FakeOllamaServer().show("qwen3:8b", THINKING_MODEL)
                .stream("/api/generate", lines, Duration.ZERO, FakeOllamaServer.After.CLOSE)) {
            var error = assertThrows(java.io.IOException.class,
                    () -> new OllamaProvider("qwen3:8b", ollama.endpoint()).generateDocumentation("Gere."));
            assertTrue(error.getMessage().contains("empty response"), error.getMessage());
        }
    }
}
