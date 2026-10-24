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
    private final List<String> requestedPaths = Collections.synchronizedList(new ArrayList<>());
    private volatile Integer modelContextTokens = 32_768;
    private volatile boolean modelInstalled = true;
    private volatile boolean ignoresNumCtx;

    FakeOllamaServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::unknown);
        server.createContext("/api/show", this::show);
        server.createContext("/api/generate", this::generate);
        server.start();
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

    private void show(HttpExchange exchange) throws IOException {
        requestedPaths.add(exchange.getRequestURI().getPath());
        exchange.getRequestBody().readAllBytes();
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
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
