package com.docgen;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Model downloads: real progress events, Ollama's in-stream errors, cancellation and timeouts. */
class OllamaPullTest {
    private static final String LAYER = "sha256:7f4030143c1c477224c5434f8272c662a8b042079a0a584f0a27a1684fe2e1fa";
    private static final String PROGRESS = "{\"status\":\"pulling 7f4030143c1c\",\"digest\":\"" + LAYER
            + "\",\"total\":522640096,\"completed\":1000}";
    private static final OllamaStream.Limits FAST = new OllamaStream.Limits(
            16 * 1024, 1L << 20, 1_000, Duration.ofMillis(700), Duration.ofSeconds(20));

    @Test
    void reportsTheRealProgressEventsUntilSuccess() throws Exception {
        List<String> lines = new ArrayList<>(FakeOllamaServer.fixture("pull-partial.ndjson").lines().toList());
        lines.addAll(List.of("{\"status\":\"verifying sha256 digest\"}", "{\"status\":\"writing manifest\"}",
                "{\"status\":\"removing any unused layers\"}", "{\"status\":\"success\"}"));
        try (FakeOllamaServer ollama = new FakeOllamaServer()
                .stream("/api/pull", lines, Duration.ZERO, FakeOllamaServer.After.CLOSE)) {
            List<PullProgress> events = Collections.synchronizedList(new ArrayList<>());

            client(ollama).pull("qwen3:0.6b", events::add, new Cancellation());

            assertEquals(PullProgress.Stage.MANIFEST, events.getFirst().stage());
            PullProgress started = events.get(1);
            assertEquals(PullProgress.Stage.DOWNLOADING, started.stage());
            assertEquals(LAYER, started.digest());
            assertEquals(522_640_096L, started.totalBytes());
            assertEquals(0, started.completedBytes(), "completed may be missing at the start of a layer");
            assertTrue(events.stream().anyMatch(event -> event.completedBytes() == 478_562_170L));
            assertEquals(List.of(PullProgress.Stage.VERIFYING, PullProgress.Stage.WRITING, PullProgress.Stage.CLEANUP,
                    PullProgress.Stage.SUCCESS), events.subList(events.size() - 4, events.size()).stream()
                    .map(PullProgress::stage).toList());
            var request = ollama.requestBodies().getFirst();
            assertEquals("qwen3:0.6b", request.path("model").asText());
            assertTrue(request.path("stream").asBoolean());
            assertTrue(request.path("insecure").isMissingNode(), "never an insecure registry connection");
        }
    }

    @Test
    void anErrorInsideTheStreamFailsTheDownload() throws Exception {
        List<String> lines = FakeOllamaServer.fixture("pull-error.ndjson").lines().toList();
        try (FakeOllamaServer ollama = new FakeOllamaServer()
                .stream("/api/pull", lines, Duration.ZERO, FakeOllamaServer.After.CLOSE)) {
            ModelPullException error = assertThrows(ModelPullException.class,
                    () -> client(ollama).pull("docgen/nao-existe-xyz:1b", progress -> { }, new Cancellation()));

            assertEquals(ModelPullException.Reason.NOT_FOUND, error.reason());
            assertFalse(error.getMessage().contains("file does not exist"), "Ollama's text is never echoed");
        }
    }

    @Test
    void ollamaErrorTextIsOnlyClassified() {
        assertEquals(ModelPullException.Reason.REGISTRY_UNREACHABLE, ModelPullException.from(
                "pull model manifest: Get \"https://registry.ollama.ai/v2/library/qwen3/manifests/8b\": dial tcp: lookup registry.ollama.ai: no such host").reason());
        assertEquals(ModelPullException.Reason.DISK_FULL, ModelPullException.from("write /models/blobs/x: no space left on device").reason());
        assertEquals(ModelPullException.Reason.FAILED, ModelPullException.from("something else").reason());
        assertEquals(ModelPullException.Reason.FAILED, ModelPullException.from(null).reason());
    }

    @Test
    void cancellingClosesTheConnectionSoOllamaStops() throws Exception {
        try (FakeOllamaServer ollama = new FakeOllamaServer()
                .stream("/api/pull", List.of("{\"status\":\"pulling manifest\"}", PROGRESS), Duration.ofMillis(50),
                        FakeOllamaServer.After.REPEAT_LAST)) {
            Cancellation cancellation = new Cancellation();
            CountDownLatch downloading = new CountDownLatch(3);
            CompletableFuture<Throwable> outcome = CompletableFuture.supplyAsync(() -> {
                try {
                    client(ollama).pull("qwen3:0.6b", progress -> downloading.countDown(), cancellation);
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });
            assertTrue(downloading.await(5, TimeUnit.SECONDS), "progress arrived");

            cancellation.cancel();

            assertTrue(outcome.get(5, TimeUnit.SECONDS) instanceof CancellationException);
            assertTrue(ollama.clientGone("/api/pull").await(5, TimeUnit.SECONDS),
                    "the connection was closed, which is what stops Ollama's download");
        }
    }

    @Test
    void aCancellationBeforeTheStartSendsNothingUseful() throws Exception {
        try (FakeOllamaServer ollama = new FakeOllamaServer()
                .stream("/api/pull", List.of(PROGRESS), Duration.ofMillis(50), FakeOllamaServer.After.REPEAT_LAST)) {
            Cancellation cancellation = new Cancellation();
            cancellation.cancel();

            assertThrows(CancellationException.class, () -> client(ollama).pull("qwen3:0.6b", progress -> {
                throw new AssertionError("no progress after a cancellation");
            }, cancellation));
        }
    }

    @Test
    void aSilentOllamaTimesOutButASlowOneDoesNot() throws Exception {
        try (FakeOllamaServer ollama = new FakeOllamaServer()
                .stream("/api/pull", List.of(PROGRESS), Duration.ZERO, FakeOllamaServer.After.SILENCE)) {
            long started = System.nanoTime();
            assertThrows(HttpTimeoutException.class, () -> client(ollama).pull("qwen3:0.6b", progress -> { },
                    new Cancellation()));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 5_000, "the idle limit applied");
        }
        List<String> slow = new ArrayList<>(Collections.nCopies(8, PROGRESS));
        slow.add("{\"status\":\"success\"}");
        try (FakeOllamaServer ollama = new FakeOllamaServer()
                .stream("/api/pull", slow, Duration.ofMillis(250), FakeOllamaServer.After.CLOSE)) {
            client(ollama).pull("qwen3:0.6b", progress -> { }, new Cancellation());
        }
    }

    @Test
    void aStreamThatStopsBeforeSuccessIsNotADownload() throws Exception {
        try (FakeOllamaServer ollama = new FakeOllamaServer()
                .stream("/api/pull", List.of(PROGRESS), Duration.ZERO, FakeOllamaServer.After.CLOSE)) {
            IOException error = assertThrows(IOException.class,
                    () -> client(ollama).pull("qwen3:0.6b", progress -> { }, new Cancellation()));

            assertTrue(error.getMessage().contains("ended before it was complete"), error.getMessage());
        }
    }

    @Test
    void malformedOrOversizedLinesFail() throws Exception {
        for (String line : new String[]{"not json", "[1]", "{\"status\":7}", "{\"status\":\"x\",\"total\":-1}",
                "{\"status\":\"" + "x".repeat(20 * 1024) + "\"}"}) {
            try (FakeOllamaServer ollama = new FakeOllamaServer()
                    .stream("/api/pull", List.of(line), Duration.ZERO, FakeOllamaServer.After.CLOSE)) {
                assertThrows(IOException.class, () -> client(ollama).pull("qwen3:0.6b", progress -> { },
                        new Cancellation()), line.length() > 40 ? "long line" : line);
            }
        }
    }

    @Test
    void cloudModelsAreNeverDownloaded() throws Exception {
        try (FakeOllamaServer ollama = new FakeOllamaServer()) {
            assertThrows(RemoteModelRefusedException.class,
                    () -> client(ollama).pull("gpt-oss:120b-cloud", progress -> { }, new Cancellation()));
            assertThrows(IllegalArgumentException.class,
                    () -> client(ollama).pull("bad name", progress -> { }, new Cancellation()));
            assertTrue(ollama.requestedPaths().isEmpty());
        }
    }

    @Test
    void ollamaThatIsNotRunningIsUnavailable() throws Exception {
        OllamaClient client = new OllamaClient(new OllamaEndpoint(OllamaUnavailableTest.closedPort()),
                OllamaClient.directClient(), FAST);

        assertThrows(OllamaUnavailableException.class,
                () -> client.pull("qwen3:0.6b", progress -> { }, new Cancellation()));
    }

    private static OllamaClient client(FakeOllamaServer ollama) {
        return new OllamaClient(ollama.endpoint(), OllamaClient.directClient(), FAST);
    }
}
