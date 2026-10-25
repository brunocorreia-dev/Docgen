package com.docgen;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One streaming NDJSON exchange with Ollama ({@code /api/pull}, {@code /api/chat}).
 *
 * <p>The JDK client has no read timeout for a streamed body, so a watchdog
 * aborts the exchange when no byte arrives for {@link Limits#idleTimeout} or
 * the whole exchange outlives {@link Limits#deadline}. Aborting, whether by
 * the watchdog or by a {@link Cancellation}, cancels the request and closes
 * the body stream; that unblocks the reading thread (in JDK 21 closing the
 * stream also releases a pending read) and closes the connection, which is
 * what makes Ollama stop. Threads are never interrupted: callers may be in the
 * middle of a database write.</p>
 *
 * <p>Lines, total bytes and line count are bounded, lines must be strict
 * UTF-8 JSON objects, and nothing read from the stream reaches an exception
 * message.</p>
 */
final class OllamaStream {
    private static final ScheduledExecutorService WATCHDOG = watchdog();
    private static final Duration WATCH_INTERVAL = Duration.ofMillis(200);

    private OllamaStream() {
    }

    record Limits(int maxLineBytes, long maxTotalBytes, long maxLines, Duration idleTimeout, Duration deadline) {
    }

    /** Receives each JSON line; returns {@code true} once the stream is complete. */
    @FunctionalInterface
    interface LineHandler {
        boolean accept(JsonNode line) throws IOException;
    }

    /** Turns an unsuccessful HTTP status into the exception to throw. */
    @FunctionalInterface
    interface StatusFailure {
        IOException forStatus(int status);
    }

    private enum Abort { CANCELLED, TIMED_OUT }

    static void exchange(
            HttpClient client,
            HttpRequest request,
            Limits limits,
            Cancellation cancellation,
            StatusFailure statusFailure,
            LineHandler handler
    ) throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<InputStream>> exchange =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        AtomicReference<Abort> aborted = new AtomicReference<>();
        AtomicReference<InputStream> body = new AtomicReference<>();
        long started = System.nanoTime();
        AtomicLong lastActivity = new AtomicLong(started);
        Runnable abort = () -> {
            exchange.cancel(true);
            closeQuietly(body.get());
        };
        Runnable unregister = cancellation.onCancel(() -> {
            aborted.compareAndSet(null, Abort.CANCELLED);
            abort.run();
        });
        ScheduledFuture<?> watch = WATCHDOG.scheduleWithFixedDelay(() -> {
            long now = System.nanoTime();
            if (now - lastActivity.get() > limits.idleTimeout().toNanos()
                    || now - started > limits.deadline().toNanos()) {
                aborted.compareAndSet(null, Abort.TIMED_OUT);
                abort.run();
            }
        }, WATCH_INTERVAL.toMillis(), WATCH_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
        try {
            HttpResponse<InputStream> response = awaitHeaders(exchange, aborted);
            body.set(response.body());
            if (aborted.get() != null) {
                closeQuietly(response.body());
            }
            if (!OllamaClient.successful(response.statusCode())) {
                closeQuietly(response.body());
                throw statusFailure.forStatus(response.statusCode());
            }
            read(response.body(), limits, lastActivity, handler);
        } catch (IOException failure) {
            if (aborted.get() == Abort.CANCELLED) {
                throw new CancellationException("The Ollama operation was cancelled.");
            }
            if (aborted.get() == Abort.TIMED_OUT) {
                throw new HttpTimeoutException("Ollama stopped responding.");
            }
            throw failure;
        } finally {
            watch.cancel(false);
            unregister.run();
            closeQuietly(body.get());
        }
    }

    private static HttpResponse<InputStream> awaitHeaders(
            CompletableFuture<HttpResponse<InputStream>> exchange,
            AtomicReference<Abort> aborted
    ) throws IOException, InterruptedException {
        try {
            return exchange.get();
        } catch (InterruptedException interrupted) {
            exchange.cancel(true);
            throw interrupted;
        } catch (CancellationException cancelled) {
            throw new IOException("Ollama request was aborted.");
        } catch (ExecutionException failed) {
            if (aborted.get() == null && HttpExchange.refusedConnection(failed.getCause())) {
                throw new OllamaUnavailableException();
            }
            throw new IOException("Ollama request failed.");
        }
    }

    private static void read(InputStream stream, Limits limits, AtomicLong lastActivity, LineHandler handler)
            throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(1024);
        byte[] buffer = new byte[8192];
        long total = 0;
        long lines = 0;
        int count;
        while ((count = stream.read(buffer)) != -1) {
            lastActivity.set(System.nanoTime());
            total += count;
            if (total > limits.maxTotalBytes()) {
                throw new IOException("Ollama stream exceeded the byte safety limit.");
            }
            int start = 0;
            for (int index = 0; index < count; index++) {
                if (buffer[index] != '\n') {
                    continue;
                }
                append(line, buffer, start, index - start, limits);
                start = index + 1;
                if (++lines > limits.maxLines()) {
                    throw new IOException("Ollama stream exceeded the line safety limit.");
                }
                if (handleLine(line, handler)) {
                    return;
                }
                line.reset();
            }
            append(line, buffer, start, count - start, limits);
        }
        if (line.size() > 0 && handleLine(line, handler)) {
            return;
        }
        throw new IOException("Ollama stream ended before it was complete.");
    }

    private static void append(ByteArrayOutputStream line, byte[] buffer, int offset, int length, Limits limits)
            throws IOException {
        if (line.size() + length > limits.maxLineBytes()) {
            throw new IOException("Ollama stream line exceeded the byte safety limit.");
        }
        line.write(buffer, offset, length);
    }

    private static boolean handleLine(ByteArrayOutputStream line, LineHandler handler) throws IOException {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(line.toByteArray()))
                    .toString();
        } catch (CharacterCodingException ignored) {
            throw new IOException("Ollama returned a non-UTF-8 stream line.");
        }
        if (text.isBlank()) {
            return false;
        }
        return handler.accept(OllamaJson.object(text, "stream line"));
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
            // Closing is the abort itself; a failure to close changes nothing for the caller.
        }
    }

    private static ScheduledExecutorService watchdog() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "docgen-ollama-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
