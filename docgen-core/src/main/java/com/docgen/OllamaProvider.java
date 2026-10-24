package com.docgen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Generates text with a model of the local Ollama service.
 *
 * <p>Every request carries an explicit context window ({@code num_ctx}). Without
 * it Ollama uses a small default (4k tokens below 24 GiB of VRAM) and silently
 * drops the start of longer prompts, which is where the security rules are; it
 * also truncates when an explicit {@code num_ctx} is too small. The window is
 * therefore sized from a conservative {@link PromptTokens} estimate plus room
 * for the answer, capped at the model's maximum, and a prompt that does not fit
 * fails before anything is sent. {@code num_predict} caps the answer at that
 * reserved room, so generation can never push the prompt out of the window.</p>
 */
public final class OllamaProvider implements LLMProvider {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final int MAX_STREAM_LINE_BYTES = 1024 * 1024;
    static final int MAX_STREAM_FRAGMENT_CHARACTERS = 512 * 1024;
    static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    static final int MAX_STREAM_LINES = 32_768;
    static final int MAX_SHOW_RESPONSE_BYTES = 4 * 1024 * 1024;
    static final int MAX_REPORTED_CONTEXT_TOKENS = 16 * 1024 * 1024;
    /** Context reserved for the answer; also sent as {@code num_predict}. */
    static final int OUTPUT_RESERVE_TOKENS = 8_192;
    /** Windows are rounded up to this step so similar prompts reuse a loaded model instead of reloading it. */
    static final int CONTEXT_STEP_TOKENS = 2_048;
    /**
     * Prompts tokenize at no more than about 6 bytes per token (code measured at
     * 5.2); fewer evaluated tokens than one per this many bytes means Ollama
     * dropped part of the prompt anyway.
     */
    static final int MAX_PLAUSIBLE_BYTES_PER_TOKEN = 24;

    private final String model;
    private final OllamaEndpoint endpoint;
    private final HttpClient client;

    public OllamaProvider(String model, OllamaEndpoint endpoint) {
        this(model, endpoint, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .proxy(DirectProxySelector.INSTANCE)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    OllamaProvider(String model, OllamaEndpoint endpoint, HttpClient client) {
        this.model = Objects.requireNonNull(model, "model");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.client = client;
    }

    @Override
    public String generateDocumentation(String prompt) throws Exception {
        return generate(prompt, false);
    }

    @Override
    public String generateStructured(String prompt) throws Exception {
        return generate(prompt, true);
    }

    String buildRequestBody(String prompt, boolean json, int contextTokens) throws IOException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("stream", true);
        if (json) {
            body.put("format", "json");
        }
        ObjectNode options = body.putObject("options");
        options.put("num_ctx", contextTokens);
        options.put("num_predict", OUTPUT_RESERVE_TOKENS);
        return MAPPER.writeValueAsString(body);
    }

    /** The estimate plus the answer reserve, rounded up to {@link #CONTEXT_STEP_TOKENS}, never above the maximum. */
    static int contextWindowFor(String prompt, int maximumContextTokens, String model) throws PromptTooLargeException {
        long needed = (long) PromptTokens.estimate(prompt) + OUTPUT_RESERVE_TOKENS;
        if (needed > maximumContextTokens) {
            throw new PromptTooLargeException(model, needed, maximumContextTokens);
        }
        long rounded = (needed + CONTEXT_STEP_TOKENS - 1) / CONTEXT_STEP_TOKENS * CONTEXT_STEP_TOKENS;
        return (int) Math.min(rounded, maximumContextTokens);
    }

    private String generate(String prompt, boolean json) throws Exception {
        int contextTokens = contextWindowFor(prompt, maximumContextTokens(), model);

        HttpRequest request = HttpRequest.newBuilder(endpoint.api("generate"))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(prompt, json, contextTokens)))
                .build();

        HttpResponse<String> response = HttpExchange.send(client, request, responseInfo -> {
            int status = responseInfo.statusCode();
            if (status >= 200 && status < 300) {
                return boundedResponseBodySubscriber(MAX_RESPONSE_BYTES);
            }
            return HttpResponse.BodySubscribers.replacing("");
        }, Duration.ofMinutes(10), "Ollama");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Ollama generation failed with HTTP " + response.statusCode());
        }
        Generation generation = parseGeneration(response.body().lines());
        requireWholePromptEvaluated(prompt, generation.promptTokens());
        return generation.text();
    }

    /** Defense in depth for a server that ignores {@code num_ctx}; the sizing above is the primary control. */
    static void requireWholePromptEvaluated(String prompt, Integer evaluatedTokens) throws PromptTruncatedException {
        if (evaluatedTokens == null) {
            return;
        }
        int bytes = prompt.getBytes(StandardCharsets.UTF_8).length;
        if ((long) evaluatedTokens * MAX_PLAUSIBLE_BYTES_PER_TOKEN < bytes) {
            throw new PromptTruncatedException(evaluatedTokens, bytes);
        }
    }

    static HttpResponse.BodySubscriber<String> boundedResponseBodySubscriber(int maxBytes) {
        return BoundedUtf8BodySubscriber.of(maxBytes, "Ollama");
    }

    /** Asks Ollama for the model's maximum context ({@code <architecture>.context_length} in {@code model_info}). */
    private int maximumContextTokens() throws IOException, InterruptedException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        HttpRequest request = HttpRequest.newBuilder(endpoint.api("show"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = HttpExchange.send(client, request, responseInfo -> {
            int status = responseInfo.statusCode();
            if (status >= 200 && status < 300) {
                return BoundedUtf8BodySubscriber.of(MAX_SHOW_RESPONSE_BYTES, "Ollama");
            }
            return HttpResponse.BodySubscribers.replacing("");
        }, Duration.ofSeconds(10), "Ollama");
        if (response.statusCode() == 404) {
            throw new ModelNotInstalledException(model);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Ollama is not responding on " + endpoint + " (HTTP "
                    + response.statusCode() + ").");
        }
        return parseContextLength(response.body(), model);
    }

    static int parseContextLength(String showResponse, String model) throws IOException {
        JsonNode root;
        try {
            root = MAPPER.readTree(showResponse);
        } catch (IOException ignored) {
            throw new IOException("Ollama returned an invalid model description.");
        }
        JsonNode info = root == null ? null : root.path("model_info");
        JsonNode architecture = info == null ? null : info.path("general.architecture");
        if (architecture != null && architecture.isTextual()
                && architecture.asText().matches("[A-Za-z0-9_]{1,64}")) {
            JsonNode length = info.path(architecture.asText() + ".context_length");
            if (length.isIntegralNumber() && length.canConvertToInt()
                    && length.asInt() > 0 && length.asInt() <= MAX_REPORTED_CONTEXT_TOKENS) {
                return length.asInt();
            }
        }
        throw new IOException("Ollama did not report the context window of model " + model + ".");
    }

    static String parseStreamingResponse(Stream<String> lines) throws IOException {
        return parseGeneration(lines).text();
    }

    /** The generated text and how many prompt tokens Ollama says it evaluated (absent in some responses). */
    record Generation(String text, Integer promptTokens) {
    }

    static Generation parseGeneration(Stream<String> lines) throws IOException {
        if (lines == null) {
            throw new IOException("Ollama returned no response stream.");
        }
        StreamingResponseAccumulator accumulator = new StreamingResponseAccumulator();
        Iterator<String> iterator = lines.iterator();
        while (iterator.hasNext()) {
            accumulator.acceptLine(iterator.next());
        }
        return accumulator.finish();
    }

    static String parseStreamingResponse(InputStream stream) throws IOException {
        if (stream == null) {
            throw new IOException("Ollama returned no response stream.");
        }

        StreamingResponseAccumulator accumulator = new StreamingResponseAccumulator();
        ByteArrayOutputStream line = new ByteArrayOutputStream(8192);
        long totalBytes = 0;
        try (BufferedInputStream buffered = new BufferedInputStream(stream)) {
            int next;
            while ((next = buffered.read()) != -1) {
                totalBytes++;
                if (totalBytes > MAX_RESPONSE_BYTES) {
                    throw new IOException("Ollama response exceeded the byte safety limit.");
                }
                if (next == '\n') {
                    accumulator.acceptLine(decodeLine(line));
                    line.reset();
                    continue;
                }
                if (line.size() >= MAX_STREAM_LINE_BYTES) {
                    throw new IOException("Ollama response line exceeded the byte safety limit.");
                }
                line.write(next);
            }
            if (line.size() > 0) {
                accumulator.acceptLine(decodeLine(line));
            }
        }
        return accumulator.finish().text();
    }

    private static String decodeLine(ByteArrayOutputStream line) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(line.toByteArray()))
                    .toString();
        } catch (CharacterCodingException ignored) {
            throw new IOException("Ollama returned a non-UTF-8 response line.");
        }
    }

    private static boolean exceedsUtf8Limit(String value, int limit) {
        long bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 0x7f) {
                bytes++;
            } else if (character <= 0x7ff) {
                bytes += 2;
            } else if (Character.isHighSurrogate(character)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                bytes += 4;
                index++;
            } else {
                bytes += 3;
            }
            if (bytes > limit) {
                return true;
            }
        }
        return false;
    }

    private static final class StreamingResponseAccumulator {
        private final StringBuilder output = new StringBuilder();
        private boolean completed;
        private int linesSeen;
        private Integer promptTokens;

        private void acceptLine(String line) throws IOException {
            if (line == null) {
                return;
            }
            linesSeen++;
            if (linesSeen > MAX_STREAM_LINES) {
                throw new IOException("Ollama response exceeded the line safety limit.");
            }
            if (exceedsUtf8Limit(line, MAX_STREAM_LINE_BYTES)) {
                throw new IOException("Ollama response line exceeded the byte safety limit.");
            }
            if (completed) {
                throw new IOException("Ollama returned data after its completion marker.");
            }
            if (line.isBlank()) {
                return;
            }
            JsonNode node;
            try {
                node = MAPPER.readTree(line);
            } catch (IOException ignored) {
                throw new IOException("Ollama returned an invalid JSON response line.");
            }
            if (node == null || !node.isObject()) {
                throw new IOException("Ollama returned a non-object JSON response line.");
            }
            JsonNode error = node.path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                throw new IOException("Ollama reported an error.");
            }
            JsonNode fragment = node.path("response");
            if (!fragment.isMissingNode() && !fragment.isTextual()) {
                throw new IOException("Ollama returned a non-text response fragment.");
            }
            if (fragment.isTextual()) {
                String value = fragment.asText();
                if (value.length() > MAX_STREAM_FRAGMENT_CHARACTERS) {
                    throw new IOException("Ollama response fragment exceeded the safety limit.");
                }
                if (value.length() > DocumentationValidator.MAX_OUTPUT_CHARACTERS - output.length()) {
                    throw new IOException("Ollama output exceeded the safety limit.");
                }
                output.append(value);
            }

            JsonNode evaluated = node.path("prompt_eval_count");
            if (!evaluated.isMissingNode() && !evaluated.isNull()) {
                if (!evaluated.isIntegralNumber() || !evaluated.canConvertToInt() || evaluated.asInt() < 0) {
                    throw new IOException("Ollama returned an invalid prompt token count.");
                }
                promptTokens = evaluated.asInt();
            }

            JsonNode done = node.path("done");
            if (!done.isMissingNode() && !done.isBoolean()) {
                throw new IOException("Ollama returned an invalid completion marker.");
            }
            if (done.asBoolean(false)) {
                JsonNode reason = node.path("done_reason");
                if (!reason.isTextual() || !"stop".equals(reason.asText())) {
                    throw new IOException("Ollama response was incomplete.");
                }
                completed = true;
            }
        }

        private Generation finish() throws IOException {
            if (!completed) {
                throw new IOException("Ollama response ended without a completion marker.");
            }
            if (output.isEmpty()) {
                throw new IOException("Ollama returned an empty response.");
            }
            return new Generation(output.toString().trim(), promptTokens);
        }
    }

}
