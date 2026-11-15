package com.docgen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/**
 * Loopback HTTP stand-in for Ollama that reproduces the context behavior
 * measured in Phase 0 against Ollama 0.32.11: without {@code num_ctx} the
 * window is 4,096 tokens, and a prompt longer than the window is cut to
 * {@code window / 2 + 2} evaluated tokens with no error (109,951 characters
 * came back as 2,050 tokens, and 6,881 tokens became 1,026 with a window of
 * 2,048). A truncated prompt loses its opening rules, so the answer here also
 * ignores them.
 */
final class FakeOllamaServer implements AutoCloseable {
    /** Characters per token measured for DocGen's own README prompt with the Qwen2.5 tokenizer. */
    static final double MEASURED_CHARACTERS_PER_TOKEN = 3.38;
    static final int TEMPLATE_TOKENS = 30;
    static final int DEFAULT_CONTEXT_TOKENS = 4_096;
    static final String FAITHFUL_ANSWER = "# Generated\n\nWritten from the whole prompt.";
    static final String TRUNCATED_ANSWER = "I'm happy to help with that.";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final List<JsonNode> generateRequests = Collections.synchronizedList(new ArrayList<>());
    private final List<JsonNode> requestBodies = Collections.synchronizedList(new ArrayList<>());
    private final List<String> requestedPaths = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, String> showBodies = new ConcurrentHashMap<>();
    private final Map<String, Response> fixed = new ConcurrentHashMap<>();
    private final Set<String> deletable = ConcurrentHashMap.newKeySet();
    private volatile Integer modelContextTokens = 32_768;
    private volatile boolean modelInstalled = true;
    private volatile boolean ignoresNumCtx;

    /** A canned answer for one route. */
    record Response(int status, String body) {
    }

    /** What a streamed route does after its scripted lines. */
    enum After {
        /** Ends the response. */
        CLOSE,
        /** Keeps the connection open and sends nothing (up to 10 s). */
        SILENCE,
        /** Repeats the last line until the client goes away (up to 30 s). */
        REPEAT_LAST
    }

    private record Stream(List<String> lines, Duration interval, After after) {
    }

    private final Map<String, Stream> streams = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> clientGone = new ConcurrentHashMap<>();

    /** Streams these NDJSON lines on a route, one every {@code interval}, then does {@code after}. */
    FakeOllamaServer stream(String path, List<String> lines, Duration interval, After after) {
        streams.put(path, new Stream(List.copyOf(lines), interval, after));
        clientGone.put(path, new CountDownLatch(1));
        try {
            server.removeContext(path);
        } catch (IllegalArgumentException notRegistered) {
            // Only the default routes are registered up front.
        }
        server.createContext(path, this::streamed);
        return this;
    }

    /** Counts down when a write to the streamed route fails because the client closed the connection. */
    CountDownLatch clientGone(String path) {
        return clientGone.get(path);
    }

    private void streamed(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requestedPaths.add(path);
        requestBodies.add(MAPPER.readTree(exchange.getRequestBody().readAllBytes()));
        Stream stream = streams.get(path);
        exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
        exchange.sendResponseHeaders(200, 0);
        OutputStream output = exchange.getResponseBody();
        try {
            for (String line : stream.lines()) {
                output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
                pause(stream.interval());
            }
            if (stream.after() == After.SILENCE) {
                pause(Duration.ofSeconds(10));
            } else if (stream.after() == After.REPEAT_LAST) {
                long until = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                byte[] last = (stream.lines().getLast() + "\n").getBytes(StandardCharsets.UTF_8);
                while (System.nanoTime() < until) {
                    output.write(last);
                    output.flush();
                    pause(stream.interval());
                }
            }
            output.close();
        } catch (IOException gone) {
            clientGone.get(path).countDown();
        } finally {
            exchange.close();
        }
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    FakeOllamaServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "fake-ollama");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/", this::unknown);
        server.createContext("/api/show", this::show);
        server.createContext("/api/generate", this::generate);
        server.createContext("/api/version", exchange -> fixedOr(exchange, new Response(200, "{\"version\":\"0.32.11\"}")));
        server.createContext("/api/tags", exchange -> fixedOr(exchange, new Response(200, "{\"models\":[]}")));
        server.createContext("/api/ps", exchange -> fixedOr(exchange, new Response(200, "{\"models\":[]}")));
        server.createContext("/api/delete", this::delete);
        server.start();
    }

    /** Answers a route with this status and body instead of its default. */
    FakeOllamaServer respond(String path, int status, String body) {
        fixed.put(path, new Response(status, body));
        return this;
    }

    /** Answers {@code /api/show} for one model with this body (other models keep the default). */
    FakeOllamaServer show(String model, String body) {
        showBodies.put(model, body);
        return this;
    }

    /** Models that {@code /api/delete} removes; any other name gets Ollama's 404. */
    FakeOllamaServer deletable(String... models) {
        deletable.addAll(List.of(models));
        return this;
    }

    /** JSON request bodies, in order, of every route that has one. */
    List<JsonNode> requestBodies() {
        synchronized (requestBodies) {
            return List.copyOf(requestBodies);
        }
    }

    static String fixture(String name) throws IOException {
        try (var stream = FakeOllamaServer.class.getResourceAsStream("/ollama/" + name)) {
            if (stream == null) {
                throw new IOException("missing fixture " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    OllamaEndpoint endpoint() {
        return new OllamaEndpoint(server.getAddress().getPort());
    }

    /** {@code null} describes a model whose metadata has no context length. */
    FakeOllamaServer modelContextTokens(Integer tokens) {
        modelContextTokens = tokens;
        return this;
    }

    FakeOllamaServer modelInstalled(boolean installed) {
        modelInstalled = installed;
        return this;
    }

    /** Simulates a server that keeps its default window whatever the request asks for. */
    FakeOllamaServer ignoresNumCtx() {
        ignoresNumCtx = true;
        return this;
    }

    List<JsonNode> generateRequests() {
        synchronized (generateRequests) {
            return List.copyOf(generateRequests);
        }
    }

    /** Every path requested, in order, including ones this fake does not implement (such as a pull). */
    List<String> requestedPaths() {
        synchronized (requestedPaths) {
            return List.copyOf(requestedPaths);
        }
    }

    static int promptTokens(String prompt) {
        return (int) Math.ceil(prompt.length() / MEASURED_CHARACTERS_PER_TOKEN) + TEMPLATE_TOKENS;
    }

    /** Evaluated prompt tokens for a window, following the measured truncation. */
    static int evaluatedTokens(String prompt, int windowTokens) {
        int tokens = promptTokens(prompt);
        return tokens > windowTokens ? windowTokens / 2 + 2 : tokens;
    }

    private void unknown(HttpExchange exchange) throws IOException {
        requestedPaths.add(exchange.getRequestURI().getPath());
        exchange.getRequestBody().readAllBytes();
        respond(exchange, 404, "{\"error\":\"not implemented by the fake\"}");
    }

    private void fixedOr(HttpExchange exchange, Response fallback) throws IOException {
        requestedPaths.add(exchange.getRequestURI().getPath());
        exchange.getRequestBody().readAllBytes();
        Response response = fixed.getOrDefault(exchange.getRequestURI().getPath(), fallback);
        respond(exchange, response.status(), response.body());
    }

    private void delete(HttpExchange exchange) throws IOException {
        requestedPaths.add(exchange.getRequestURI().getPath());
        JsonNode request = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
        requestBodies.add(request);
        if (!"DELETE".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"method not allowed\"}");
            return;
        }
        String model = request.path("model").asText();
        if (deletable.remove(model)) {
            respond(exchange, 200, "");
        } else {
            respond(exchange, 404, "{\"error\":\"model '" + model + "' not found\"}");
        }
    }

    private void show(HttpExchange exchange) throws IOException {
        requestedPaths.add(exchange.getRequestURI().getPath());
        JsonNode request = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
        requestBodies.add(request);
        String override = showBodies.get(request.path("model").asText());
        if (override != null) {
            respond(exchange, 200, override);
            return;
        }
        if (!modelInstalled) {
            respond(exchange, 404, "{\"error\":\"model not found\"}");
            return;
        }
        ObjectNode info = MAPPER.createObjectNode().put("general.architecture", "qwen2");
        if (modelContextTokens != null) {
            info.put("qwen2.context_length", modelContextTokens);
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.set("model_info", info);
        respond(exchange, 200, MAPPER.writeValueAsString(body));
    }

    private void generate(HttpExchange exchange) throws IOException {
        requestedPaths.add(exchange.getRequestURI().getPath());
        JsonNode request = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
        generateRequests.add(request);
        String prompt = request.path("prompt").asText();
        JsonNode requested = request.path("options").path("num_ctx");
        int window = ignoresNumCtx || !requested.canConvertToInt() ? DEFAULT_CONTEXT_TOKENS : requested.asInt();
        int evaluated = evaluatedTokens(prompt, window);
        String answer = evaluated < promptTokens(prompt) ? TRUNCATED_ANSWER : FAITHFUL_ANSWER;
        String fragment = MAPPER.writeValueAsString(MAPPER.createObjectNode().put("response", answer).put("done", false));
        String last = MAPPER.writeValueAsString(MAPPER.createObjectNode().put("response", "").put("done", true)
                .put("done_reason", "stop").put("prompt_eval_count", evaluated));
        respond(exchange, 200, fragment + "\n" + last + "\n");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
