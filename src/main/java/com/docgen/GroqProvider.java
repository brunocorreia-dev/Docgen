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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GroqProvider implements LLMProvider {
    private static final URI API_URL = URI.create("https://api.groq.com/openai/v1/chat/completions");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern RETRY_AFTER_BODY = Pattern.compile("try again in ([0-9.]+)s", Pattern.CASE_INSENSITIVE);

    private final String model;
    private final String apiKey;
    private final HttpClient client;

    public GroqProvider(String model) {
        this(model, System.getenv("GROQ_API_KEY"), HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
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
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GROQ_API_KEY is required for the Groq provider.");
        }

        String body = buildRequestBody(prompt);
        for (int attempt = 1; attempt <= 3; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(API_URL)
                    .timeout(Duration.ofMinutes(5))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return parseContent(response.body());
            }
            if (attempt < 3 && (status == 429 || status >= 500)) {
                sleepBeforeRetry(response);
                continue;
            }
            throw new IOException("Groq request failed with HTTP " + status + ": " + abbreviate(response.body(), 500));
        }
        throw new IOException("Groq request failed after retries.");
    }

    private String buildRequestBody(String prompt) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("temperature", 0.2);
        root.put("max_tokens", 4096);
        root.putArray("messages")
                .addObject()
                .put("role", "user")
                .put("content", prompt);
        return MAPPER.writeValueAsString(root);
    }

    private static String parseContent(String body) throws IOException {
        JsonNode root = MAPPER.readTree(body);
        JsonNode content = root.path("choices").path(0).path("message").path("content");
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
        Matcher matcher = RETRY_AFTER_BODY.matcher(response.body() == null ? "" : response.body());
        if (matcher.find()) {
            return Optional.of(Math.max(1L, (long) Math.ceil(Double.parseDouble(matcher.group(1)))));
        }
        return Optional.empty();
    }

    private static String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
