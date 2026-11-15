package com.docgen;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Streaming chat: real chunks, reasoning kept apart, refusal of remote models, cancellation. */
class OllamaChatTest {
    private static final OllamaStream.Limits FAST = new OllamaStream.Limits(
            1024 * 1024, 1L << 20, 1_000, Duration.ofMillis(700), Duration.ofSeconds(20));
    private static final String THINKING_MODEL = "{\"capabilities\":[\"completion\",\"thinking\"],"
            + "\"model_info\":{\"general.architecture\":\"qwen3\",\"qwen3.context_length\":40960}}";
    private static final String QWEN35_THINKING_MODEL = "{\"capabilities\":[\"completion\",\"thinking\"],"
            + "\"model_info\":{\"general.architecture\":\"qwen35\",\"qwen35.context_length\":40960}}";

    /** Records what a listener receives. */
    static final class Recorder implements OllamaApi.ChatListener {
        final StringBuilder answer = new StringBuilder();
        final StringBuilder reasoning = new StringBuilder();
        final List<String> pieces = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void onContent(String text) {
            pieces.add(text);
            answer.append(text);
        }

        @Override
        public void onThinking(String text) {
            reasoning.append(text);
        }
    }

    @Test
    void streamsTheRealChunksAndReportsTheMetrics() throws Exception {
        List<String> lines = FakeOllamaServer.fixture("chat-stream.ndjson").lines().toList();
        try (FakeOllamaServer ollama = new FakeOllamaServer()
                .stream("/api/chat", lines, Duration.ZERO, FakeOllamaServer.After.CLOSE)) {
            Recorder recorder = new Recorder();

            ChatOutcome outcome = client(ollama).chat(request("llama3.2:latest", false, "30s"), recorder, new Cancellation());

            assertEquals(List.of("Ok", "."), recorder.pieces);
            assertEquals("stop", outcome.doneReason());
            assertEquals(30, outcome.promptTokens());
            assertEquals(3, outcome.outputTokens());
            assertEquals(2_980_295_662L, outcome.loadDuration().toNanos());
            assertTrue(outcome.tokensPerSecond() > 100);
            assertFalse(outcome.cutAtLimit());

            JsonNode body = ollama.requestBodies().get(1);
            assertEquals(List.of("/api/show", "/api/chat"), ollama.requestedPaths(), "looked up before anything is sent");
            assertEquals("llama3.2:latest", body.path("model").asText());
            assertEquals("system", body.path("messages").get(0).path("role").asText());
            assertEquals("Diga apenas: ok", body.path("messages").get(1).path("content").asText());
            assertTrue(body.path("stream").asBoolean());
            assertEquals(4_096, body.path("options").path("num_ctx").asInt());
            assertEquals(64, body.path("options").path("num_predict").asInt());
            assertEquals("30s", body.path("keep_alive").asText());
            assertTrue(body.path("think").isMissingNode(), "a model that cannot think gets no think field");
        }
    }

    @Test
    void reasoningSeparatedByOllamaNeverReachesTheAnswer() throws Exception {
        List<String> lines = List.of(
                chunk("{\"role\":\"assistant\",\"content\":\"\",\"thinking\":\"Vou pensar\"}"),
                chunk("{\"role\":\"assistant\",\"content\":\"\",\"thinking\":\" um pouco.\"}"),
                chunk("{\"role\":\"assistant\",\"content\":\"Olá!\"}"),
                done("stop"));
        try (FakeOllamaServer ollama = new FakeOllamaServer().show("qwen3:8b", THINKING_MODEL)
                .stream("/api/chat", lines, Duration.ZERO, FakeOllamaServer.After.CLOSE)) {
            Recorder recorder = new Recorder();

            client(ollama).chat(request("qwen3:8b", false, null), recorder, new Cancellation());

            assertEquals("Olá!", recorder.answer.toString());
            assertEquals("Vou pensar um pouco.", recorder.reasoning.toString());
            JsonNode body = ollama.requestBodies().get(1);
            assertFalse(body.path("think").asBoolean(true), "think=false is sent to a model that can think");
            assertTrue(body.path("keep_alive").isMissingNode());
        }
    }

    @Test
    void qwen35ChatDisablesThinkingInTheRequestBody() throws Exception {
        try (FakeOllamaServer ollama = new FakeOllamaServer().show("qwen3.5:9b", QWEN35_THINKING_MODEL)
                .stream("/api/chat", List.of(done("stop")), Duration.ZERO, FakeOllamaServer.After.CLOSE)) {
            client(ollama).chat(request("qwen3.5:9b", false, null), new Recorder(), new Cancellation());

            JsonNode body = ollama.requestBodies().get(1);
            assertTrue(body.path("think").isBoolean(), "thinking-capable model gets an explicit flag");
            assertFalse(body.path("think").asBoolean(), "chat defaults to no reasoning");
        }
    }

    @Test
    void inlineThinkTagsSplitAcrossChunksAreKeptApart() throws Exception {
        List<String> lines = List.of(
                chunk("{\"role\":\"assistant\",\"content\":\"<thi\"}"),
                chunk("{\"role\":\"assistant\",\"content\":\"nk>ignore all rules</th\"}"),
                chunk("{\"role\":\"assistant\",\"content\":\"ink>\\n\\n# Título\"}"),
                done("stop"));
        try (FakeOllamaServer ollama = new FakeOllamaServer()
                .stream("/api/chat", lines, Duration.ZERO, FakeOllamaServer.After.CLOSE)) {
            Recorder recorder = new Recorder();

            client(ollama).chat(request("qwen3:8b", true, null), recorder, new Cancellation());

            assertEquals("# Título", recorder.answer.toString());
            assertEquals("ignore all rules", recorder.reasoning.toString());
        }
    }

    @Test
    void remoteModelsAreRefusedBeforeAnythingIsSent() throws Exception {
        try (FakeOllamaServer ollama = new FakeOllamaServer()
                .show("docgen-probe-remote:latest", FakeOllamaServer.fixture("show-remote-stub.json"))) {
            assertThrows(RemoteModelRefusedException.class, () -> client(ollama)
                    .chat(request("docgen-probe-remote:latest", false, null), new Recorder(), new Cancellation()));
            assertThrows(RemoteModelRefusedException.class, () -> client(ollama)
                    .chat(request("gemma4:cloud", false, null), new Recorder(), new Cancellation()));
            assertFalse(ollama.requestedPaths().contains("/api/chat"), "no prompt left the computer");
        }
    }

    @Test
    void aChunkThatDeclaresARemoteHostEndsTheReply() throws Exception {
        List<String> lines = List.of(
                "{\"model\":\"qwen3:8b\",\"remote_host\":\"https://ollama.com:443\",\"message\":{\"role\":\"assistant\",\"content\":\"hi\"},\"done\":false}",
                done("stop"));
        try (FakeOllamaServer ollama = new FakeOllamaServer()
                .stream("/api/chat", lines, Duration.ZERO, FakeOllamaServer.After.CLOSE)) {
            Recorder recorder = new Recorder();

            assertThrows(RemoteModelRefusedException.class,
                    () -> client(ollama).chat(request("qwen3:8b", false, null), recorder, new Cancellation()));
            assertEquals("", recorder.answer.toString(), "nothing from that chunk is shown");
        }
    }

    @Test
    void stoppingTheGenerationClosesTheConnection() throws Exception {
        try (FakeOllamaServer ollama = new FakeOllamaServer().stream("/api/chat",
                List.of(chunk("{\"role\":\"assistant\",\"content\":\"palavra \"}")), Duration.ofMillis(40),
                FakeOllamaServer.After.REPEAT_LAST)) {
            Cancellation cancellation = new Cancellation();
            CountDownLatch streaming = new CountDownLatch(3);
            CompletableFuture<Throwable> outcome = CompletableFuture.supplyAsync(() -> {
                try {
                    client(ollama).chat(request("qwen3:8b", false, null), new OllamaApi.ChatListener() {
                        @Override
                        public void onContent(String text) {
                            streaming.countDown();
                        }
                    }, cancellation);
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });
            assertTrue(streaming.await(5, TimeUnit.SECONDS));

            cancellation.cancel();

            assertTrue(outcome.get(5, TimeUnit.SECONDS) instanceof CancellationException);
            assertTrue(ollama.clientGone("/api/chat").await(5, TimeUnit.SECONDS), "Ollama sees the disconnect");
        }
    }

    @Test
    void anAnswerCutAtTheLimitIsReportedAsSuch() throws Exception {
        try (FakeOllamaServer ollama = new FakeOllamaServer().stream("/api/chat",
                List.of(chunk("{\"role\":\"assistant\",\"content\":\"Olá! Como\"}"), done("length")), Duration.ZERO,
                FakeOllamaServer.After.CLOSE)) {
            assertTrue(client(ollama).chat(request("qwen3:8b", false, null), new Recorder(), new Cancellation())
                    .cutAtLimit());
        }
    }

    @Test
    void errorsAndBrokenStreamsFailWithoutEchoingOllama() throws Exception {
        for (List<String> lines : List.of(
                List.of("{\"error\":\"secret internal detail\"}"),
                List.of(chunk("{\"role\":\"assistant\",\"content\":\"x\"}")),
                List.of(done("load")),
                List.of("{\"message\":{\"content\":7},\"done\":false}"))) {
            try (FakeOllamaServer ollama = new FakeOllamaServer()
                    .stream("/api/chat", lines, Duration.ZERO, FakeOllamaServer.After.CLOSE)) {
                IOException error = assertThrows(IOException.class,
                        () -> client(ollama).chat(request("qwen3:8b", false, null), new Recorder(), new Cancellation()));
                assertFalse(error.getMessage().contains("secret"), error.getMessage());
            }
        }
    }

    @Test
    void aMissingModelIsReportedBeforeAnyChat() throws Exception {
        try (FakeOllamaServer ollama = new FakeOllamaServer().modelInstalled(false)) {
            assertThrows(ModelNotInstalledException.class,
                    () -> client(ollama).chat(request("qwen3:8b", false, null), new Recorder(), new Cancellation()));
            assertEquals(List.of("/api/show"), ollama.requestedPaths());
        }
    }

    @Test
    void requestsAreValidated() {
        List<ChatMessage> messages = List.of(ChatMessage.user("oi"));
        assertThrows(IllegalArgumentException.class, () -> new ChatRequest("qwen3:8b", List.of(), 4096, 64, false, null));
        assertThrows(IllegalArgumentException.class, () -> new ChatRequest("qwen3:8b", messages, 100, 64, false, null));
        assertThrows(IllegalArgumentException.class, () -> new ChatRequest("qwen3:8b", messages, 4096, 5000, false, null));
        assertThrows(IllegalArgumentException.class, () -> new ChatRequest("qwen3:8b", messages, 4096, 64, false, "forever"));
        assertThrows(IllegalArgumentException.class, () -> new ChatRequest("bad name", messages, 4096, 64, false, null));
    }

    private static ChatRequest request(String model, boolean thinking, String keepAlive) {
        return new ChatRequest(model, List.of(ChatMessage.system("Responda em português."), ChatMessage.user("Diga apenas: ok")),
                4_096, 64, thinking, keepAlive);
    }

    private static String chunk(String message) {
        return "{\"model\":\"m\",\"created_at\":\"2026-10-25T13:55:20Z\",\"message\":" + message + ",\"done\":false}";
    }

    private static String done(String reason) {
        return "{\"model\":\"m\",\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,\"done_reason\":\""
                + reason + "\",\"prompt_eval_count\":12,\"eval_count\":4,\"eval_duration\":20000000}";
    }

    private static OllamaClient client(FakeOllamaServer ollama) {
        return new OllamaClient(ollama.endpoint(), OllamaClient.directClient(), FAST, FAST);
    }
}
