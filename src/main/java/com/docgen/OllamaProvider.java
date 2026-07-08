package com.docgen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Stream;

public final class OllamaProvider implements LLMProvider {
    private static final URI GENERATE_URL = URI.create("http://localhost:11434/api/generate");
    private static final URI TAGS_URL = URI.create("http://localhost:11434/api/tags");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String model;
    private final HttpClient client;

    public OllamaProvider(String model) {
        this(model, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
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

        HttpResponse<Stream<String>> response = client.send(request, HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Ollama generation failed with HTTP " + response.statusCode());
        }

        StringBuilder output = new StringBuilder();
        try (Stream<String> lines = response.body()) {
            lines.forEach(line -> appendResponseLine(line, output));
        }
        if (output.isEmpty()) {
            throw new IOException("Ollama returned an empty response.");
        }
        return output.toString().trim();
    }

    private void ensureOllamaIsRunning() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(TAGS_URL)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Ollama is not responding on http://localhost:11434 (HTTP " + response.statusCode() + ").");
        }
    }

    private static void appendResponseLine(String line, StringBuilder output) {
        if (line == null || line.isBlank()) {
            return;
        }
        try {
            JsonNode node = MAPPER.readTree(line);
            JsonNode response = node.path("response");
            if (response.isTextual()) {
                output.append(response.asText());
            }
            JsonNode error = node.path("error");
            if (error.isTextual() && !error.asText().isBlank()) {
                throw new IllegalStateException("Ollama error: " + error.asText());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Invalid JSON line returned by Ollama.", e);
        }
    }
}
