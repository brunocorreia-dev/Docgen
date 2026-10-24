package com.docgen;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderResponseValidationTest {
    @Test
    void boundsSuccessfulResponseBytesBeforeMaterializingTheBody() throws Exception {
        HttpResponse.BodySubscriber<String> accepted = BoundedUtf8BodySubscriber.of(5, "Provider");
        TestSubscription acceptedSubscription = new TestSubscription();
        accepted.onSubscribe(acceptedSubscription);
        accepted.onNext(List.of(ByteBuffer.wrap("hello".getBytes(StandardCharsets.UTF_8))));
        accepted.onComplete();

        assertEquals("hello", accepted.getBody().toCompletableFuture().get());
        assertFalse(acceptedSubscription.cancelled);

        HttpResponse.BodySubscriber<String> rejected = BoundedUtf8BodySubscriber.of(5, "Provider");
        TestSubscription rejectedSubscription = new TestSubscription();
        rejected.onSubscribe(rejectedSubscription);
        rejected.onNext(List.of(
                ByteBuffer.wrap("hello".getBytes(StandardCharsets.UTF_8)),
                ByteBuffer.wrap("!".getBytes(StandardCharsets.UTF_8))));

        ExecutionException error = assertThrows(ExecutionException.class,
                () -> rejected.getBody().toCompletableFuture().get());
        assertTrue(error.getCause() instanceof IOException);
        assertTrue(error.getCause().getMessage().contains("safety limit"));
        assertTrue(rejectedSubscription.cancelled);
    }

    @Test
    void ollamaRequiresAStopMarker() throws Exception {
        String result = OllamaProvider.parseStreamingResponse(Stream.of(
                "{\"response\":\"# Pro\",\"done\":false}",
                "{\"response\":\"ject\\n\",\"done\":true,\"done_reason\":\"stop\"}"));

        assertEquals("# Project", result);
        assertThrows(IOException.class, () -> OllamaProvider.parseStreamingResponse(Stream.of(
                "{\"response\":\"# Partial\",\"done\":false}")));
        assertThrows(IOException.class, () -> OllamaProvider.parseStreamingResponse(Stream.of(
                "{\"response\":\"# Partial\",\"done\":true,\"done_reason\":\"length\"}")));
        assertThrows(IOException.class, () -> OllamaProvider.parseStreamingResponse(Stream.of(
                "{\"response\":\"# Partial\",\"done\":true}")));
        assertThrows(IOException.class, () -> OllamaProvider.parseStreamingResponse(Stream.of(
                "{\"response\":\"# Partial\",\"done\":true,\"done_reason\":null}")));
    }

    @Test
    void ollamaDoesNotEchoProviderControlledErrorText() {
        IOException error = assertThrows(IOException.class,
                () -> OllamaProvider.parseStreamingResponse(Stream.of(
                        "{\"error\":\"bad\\u001b[31m\\nsecret\",\"done\":true}")));

        assertEquals("Ollama reported an error.", error.getMessage());
        assertFalse(error.getMessage().contains("secret"));

        IOException malformed = assertThrows(IOException.class,
                () -> OllamaProvider.parseStreamingResponse(Stream.of("{\"response\":\"echoed-secret\"")));
        assertEquals("Ollama returned an invalid JSON response line.", malformed.getMessage());
        assertEquals(null, malformed.getCause(), "raw provider lines must not survive in exception causes");
    }

    @Test
    void ollamaRejectsOversizedLinesAndFragmentsBeforeOutputAccumulation() {
        String oversizedLine = " ".repeat(OllamaProvider.MAX_STREAM_LINE_BYTES + 1);
        IOException lineError = assertThrows(IOException.class,
                () -> OllamaProvider.parseStreamingResponse(Stream.of(oversizedLine)));
        assertTrue(lineError.getMessage().contains("line exceeded"));

        byte[] oversizedRawLine = "x".repeat(OllamaProvider.MAX_STREAM_LINE_BYTES + 1)
                .getBytes(StandardCharsets.UTF_8);
        IOException rawLineError = assertThrows(IOException.class,
                () -> OllamaProvider.parseStreamingResponse(new ByteArrayInputStream(oversizedRawLine)));
        assertTrue(rawLineError.getMessage().contains("line exceeded"));

        String oversizedFragment = "x".repeat(OllamaProvider.MAX_STREAM_FRAGMENT_CHARACTERS + 1);
        String fragmentLine = "{\"response\":\"" + oversizedFragment + "\",\"done\":true}";
        IOException fragmentError = assertThrows(IOException.class,
                () -> OllamaProvider.parseStreamingResponse(Stream.of(fragmentLine)));
        assertTrue(fragmentError.getMessage().contains("fragment exceeded"));
    }

    @Test
    void ollamaBoundsTheCompleteHttpBodyBeforeParsingNdjson() throws Exception {
        HttpResponse.BodySubscriber<String> rejected = OllamaProvider.boundedResponseBodySubscriber(5);
        TestSubscription subscription = new TestSubscription();
        rejected.onSubscribe(subscription);
        rejected.onNext(List.of(ByteBuffer.wrap("123456".getBytes(StandardCharsets.UTF_8))));

        ExecutionException error = assertThrows(ExecutionException.class,
                () -> rejected.getBody().toCompletableFuture().get());

        assertTrue(error.getCause() instanceof IOException);
        assertTrue(error.getCause().getMessage().contains("Ollama response exceeded"));
        assertTrue(subscription.cancelled);
    }

    @Test
    void endToEndDeadlineCancelsAnExchangeThatNeverFinishesItsBody() {
        CompletableFuture<HttpResponse<String>> exchange = new CompletableFuture<>();

        IOException error = assertThrows(IOException.class,
                () -> HttpExchange.await(exchange, Duration.ofMillis(10), "Test provider"));

        assertTrue(error.getMessage().contains("end-to-end deadline"));
        assertTrue(exchange.isCancelled());
    }

    private static final class TestSubscription implements Flow.Subscription {
        private boolean cancelled;

        @Override
        public void request(long n) {
            // Test publisher pushes explicitly.
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }
}
