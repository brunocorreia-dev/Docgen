package com.docgen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.stream.Stream;

public final class OllamaProvider implements LLMProvider {
    private static final URI GENERATE_URL = URI.create("http://127.0.0.1:11434/api/generate");
    private static final URI TAGS_URL = URI.create("http://127.0.0.1:11434/api/tags");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final int MAX_STREAM_LINE_BYTES = 1024 * 1024;
    static final int MAX_STREAM_FRAGMENT_CHARACTERS = 512 * 1024;
    static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    static final int MAX_STREAM_LINES = 32_768;

    private final String model;
    private final HttpClient client;

    public OllamaProvider(String model) {
        this(model, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .proxy(DirectProxySelector.INSTANCE)
                .build());
    }

    OllamaProvider(String model, HttpClient client) {
        this.model = model;
        this.client = client;
    }

    @Override
    public boolean isRemote() {
        return false;
    }

    @Override
    public String describeDestination() {
        return "Ollama at " + GENERATE_URL + " (local service; model " + model + ")";
    }

    @Override
    public String generateDocumentation(String prompt) throws Exception {
        ensureOllamaIsRunning();

        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("stream", true);

        HttpRequest request = HttpRequest.newBuilder(GENERATE_URL)
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
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
        return parseStreamingResponse(response.body().lines());
    }

    static HttpResponse.BodySubscriber<String> boundedResponseBodySubscriber(int maxBytes) {
        return GroqProvider.boundedUtf8BodySubscriber(maxBytes, "Ollama");
    }

    private void ensureOllamaIsRunning() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(TAGS_URL)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<Void> response = HttpExchange.send(
                client, request, HttpResponse.BodyHandlers.discarding(), Duration.ofSeconds(5), "Ollama");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Ollama is not responding on http://127.0.0.1:11434 (HTTP "
                    + response.statusCode() + ").");
        }
    }

    static String parseStreamingResponse(Stream<String> lines) throws IOException {
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
        return accumulator.finish();
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

        private String finish() throws IOException {
            if (!completed) {
                throw new IOException("Ollama response ended without a completion marker.");
            }
            if (output.isEmpty()) {
                throw new IOException("Ollama returned an empty response.");
            }
            return output.toString().trim();
        }
    }

}
