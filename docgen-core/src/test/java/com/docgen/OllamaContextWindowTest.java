package com.docgen;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for the silent context truncation measured in Phase 0. */
class OllamaContextWindowTest {
    /** The exact probe of the Phase 0 measurement: a rule on the first line, then filler. */
    private static final String MEASURED_PROMPT = "RULES: always answer with the single word BANANA.\n"
            + IntStream.range(0, 3000).mapToObj(index -> "item" + index + " lorem ipsum dolor sit amet.")
            .collect(Collectors.joining(" "))
            + "\nNow answer.";

    @Test
    void theFakeReproducesTheMeasuredTruncation() {
        assertEquals(109_951, MEASURED_PROMPT.length());
        assertEquals(2_050, FakeOllamaServer.evaluatedTokens(MEASURED_PROMPT, FakeOllamaServer.DEFAULT_CONTEXT_TOKENS));
    }

    @Test
    void theMeasuredPromptIsSentWithAWindowThatHoldsAllOfIt() throws Exception {
        try (FakeOllamaServer ollama = new FakeOllamaServer().modelContextTokens(131_072)) {
            String answer = provider(ollama).generateDocumentation(MEASURED_PROMPT);

            JsonNode options = ollama.generateRequests().getFirst().path("options");
            int window = options.path("num_ctx").asInt();
            assertEquals(FakeOllamaServer.FAITHFUL_ANSWER, answer);
            assertTrue(window >= FakeOllamaServer.promptTokens(MEASURED_PROMPT) + OllamaProvider.OUTPUT_RESERVE_TOKENS,
                    "num_ctx " + window + " must hold the prompt and the answer");
            assertTrue(window <= 131_072);
            assertEquals(0, window % OllamaProvider.CONTEXT_STEP_TOKENS);
            assertEquals(OllamaProvider.OUTPUT_RESERVE_TOKENS, options.path("num_predict").asInt());
        }
    }

    @Test
    void aPromptThatDoesNotFitTheModelFailsBeforeAnythingIsSent() throws Exception {
        try (FakeOllamaServer ollama = new FakeOllamaServer().modelContextTokens(32_768)) {
            PromptTooLargeException error = assertThrows(PromptTooLargeException.class,
                    () -> provider(ollama).generateDocumentation(MEASURED_PROMPT));

            assertTrue(error.getMessage().contains("accepts at most 32768"), error.getMessage());
            assertTrue(ollama.generateRequests().isEmpty(), "no prompt may reach Ollama");
        }
    }

    @Test
    void aServerThatIgnoresNumCtxIsCaughtByTheEvaluatedTokenCount() throws Exception {
        try (FakeOllamaServer ollama = new FakeOllamaServer().modelContextTokens(131_072).ignoresNumCtx()) {
            PromptTruncatedException error = assertThrows(PromptTruncatedException.class,
                    () -> provider(ollama).generateDocumentation(MEASURED_PROMPT));

            assertTrue(error.getMessage().contains("only 2050 tokens"), error.getMessage());
        }
    }

    @Test
    void smallPromptsGetTheSmallestRoundedWindow() throws Exception {
        try (FakeOllamaServer ollama = new FakeOllamaServer()) {
            provider(ollama).generateStructured("hello");

            JsonNode request = ollama.generateRequests().getFirst();
            assertEquals(10_240, request.path("options").path("num_ctx").asInt());
            assertEquals("json", request.path("format").asText());
        }
    }

    @Test
    void theWindowNeverExceedsTheModelMaximum() throws Exception {
        assertEquals(9_000, OllamaProvider.contextWindowFor("hello", 9_000, "m"));
        assertThrows(PromptTooLargeException.class, () -> OllamaProvider.contextWindowFor("hello", 8_000, "m"));
    }

    @Test
    void aMissingModelFailsWithoutAnyDownloadOrGeneration() throws Exception {
        try (FakeOllamaServer ollama = new FakeOllamaServer().modelInstalled(false)) {
            ModelNotInstalledException error = assertThrows(ModelNotInstalledException.class,
                    () -> provider(ollama).generateDocumentation("hello"));

            assertEquals("qwen2.5-coder:7b", error.model());
            assertEquals(java.util.List.of("/api/show"), ollama.requestedPaths(), "nothing but the lookup is requested");
        }
    }

    @Test
    void theDefaultModelIsTheVerifiedQwenCoderTag() {
        assertEquals("qwen2.5-coder:7b", LLMProviderFactory.DEFAULT_MODEL);
    }

    @Test
    void aModelWithoutAReportedContextWindowFailsClosed() throws Exception {
        try (FakeOllamaServer ollama = new FakeOllamaServer().modelContextTokens(null)) {
            IOException error = assertThrows(IOException.class, () -> provider(ollama).generateDocumentation("hello"));

            assertTrue(error.getMessage().contains("context window"), error.getMessage());
            assertTrue(ollama.generateRequests().isEmpty());
        }
    }

    @Test
    void contextLengthMetadataIsValidatedStrictly() {
        assertThrows(IOException.class, () -> OllamaProvider.parseContextLength("not json", "m"));
        assertThrows(IOException.class, () -> OllamaProvider.parseContextLength(
                "{\"model_info\":{\"general.architecture\":\"qwen2\",\"qwen2.context_length\":\"32768\"}}", "m"));
        assertThrows(IOException.class, () -> OllamaProvider.parseContextLength(
                "{\"model_info\":{\"general.architecture\":\"qwen2\",\"qwen2.context_length\":-1}}", "m"));
        assertThrows(IOException.class, () -> OllamaProvider.parseContextLength(
                "{\"model_info\":{\"general.architecture\":\"../x\",\"../x.context_length\":4096}}", "m"));
    }

    private static OllamaProvider provider(FakeOllamaServer ollama) {
        return new OllamaProvider("qwen2.5-coder:7b", ollama.endpoint());
    }
}
