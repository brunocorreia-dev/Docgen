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
import java.util.Optional;

public final class GroqProvider implements LLMProvider {
    private static final URI API_URL = URI.create("https://api.groq.com/openai/v1/chat/completions");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    private final String model;
    private final String apiKey;
    private final HttpClient client;

    public GroqProvider(String model) {
        this(model, System.getenv("GROQ_API_KEY"), HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .proxy(DirectProxySelector.INSTANCE)
                .build());
    }

    GroqProvider(String model, String apiKey, HttpClient client) {
        this.model = model;
        this.apiKey = apiKey;
        this.client = client;
    }

    @Override
    public boolean isRemote() {
        return true;
    }

    @Override
    public String describeDestination() {
        return "Groq at " + API_URL + " (remote service; model " + model + ")";
    }

    @Override
    public String generateDocumentation(String prompt) throws Exception {
        return complete(buildRequestBody(prompt, false));
    }

    @Override
    public String generateStructured(String prompt) throws Exception {
        return complete(buildRequestBody(prompt, true));
    }

    private String complete(String body) throws Exception {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GROQ_API_KEY is required for the Groq provider.");
        }

        for (int attempt = 1; attempt <= 3; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(API_URL)
                    .timeout(Duration.ofMinutes(5))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = HttpExchange.send(client, request, responseInfo -> {
                int status = responseInfo.statusCode();
                if (status >= 200 && status < 300) {
                    return BoundedUtf8BodySubscriber.of(MAX_RESPONSE_BYTES, "Groq");
                }
                // Never retain a remote error body: providers can echo prompt
                // data in errors, and the body is not needed for diagnostics.
                return HttpResponse.BodySubscribers.replacing("");
            }, Duration.ofMinutes(5), "Groq");
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return parseContent(response.body());
            }
            if (attempt < 3 && (status == 429 || status >= 500)) {
                sleepBeforeRetry(response);
                continue;
            }
            // The response body is controlled by a remote service and can echo
            // request data or contain terminal control sequences. Do not expose it.
            throw new IOException("Groq request failed with HTTP " + status + ".");
        }
        throw new IOException("Groq request failed after retries.");
    }

    String buildRequestBody(String prompt, boolean json) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("temperature", 0.2);
        root.put("max_completion_tokens", json ? 8192 : 4096);
        if (json) {
            root.putObject("response_format").put("type", "json_object");
        }
        root.putArray("messages")
                .addObject()
                .put("role", "user")
                .put("content", prompt);
        return MAPPER.writeValueAsString(root);
    }

    static String parseContent(String body) throws IOException {
        if (body == null || body.isBlank()) {
            throw new IOException("Groq returned an empty response body.");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (IOException ignored) {
            throw new IOException("Groq returned an invalid JSON response.");
        }
        if (root == null || !root.isObject()) {
            throw new IOException("Groq returned an invalid JSON response object.");
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IOException("Groq response did not contain a completion choice.");
        }
        JsonNode choice = choices.path(0);
        JsonNode finishReason = choice.path("finish_reason");
        if (!finishReason.isTextual() || !"stop".equals(finishReason.asText())) {
            String reason = finishReason.isTextual() ? SafeText.forTerminal(finishReason.asText()) : "missing";
            throw new IOException("Groq response was incomplete (finish_reason: " + reason + ").");
        }
        JsonNode content = choice.path("message").path("content");
        if (!content.isTextual() || content.asText().isBlank()) {
            throw new IOException("Groq response did not contain choices[0].message.content.");
        }
        return content.asText().trim();
    }

    private static void sleepBeforeRetry(HttpResponse<String> response) throws InterruptedException {
        long seconds = retryAfterSeconds(response).orElse(2L);
        Thread.sleep(Math.min(seconds, 60L) * 1000L);
    }

    private static Optional<Long> retryAfterSeconds(HttpResponse<String> response) {
        Optional<String> header = response.headers().firstValue("Retry-After");
        if (header.isPresent()) {
            try {
                return Optional.of(Math.max(1L, Long.parseLong(header.get().trim())));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

}
