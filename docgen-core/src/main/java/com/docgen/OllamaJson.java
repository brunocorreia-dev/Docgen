package com.docgen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Defensive reading of Ollama's JSON. Responses are untrusted: required fields
 * of the wrong type fail with a fixed message (never echoing the body), and
 * descriptive fields are normalized and bounded before anyone displays them.
 */
final class OllamaJson {
    static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern ARCHITECTURE = Pattern.compile("[A-Za-z0-9_]{1,64}");
    private static final Pattern CAPABILITY = Pattern.compile("[a-z][a-z0-9_-]{0,31}");
    static final int MAX_CAPABILITIES = 32;

    private OllamaJson() {
    }

    static JsonNode object(String body, String what) throws IOException {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (IOException ignored) {
            throw new IOException("Ollama returned an invalid " + what + ".");
        }
        if (root == null || !root.isObject()) {
            throw new IOException("Ollama returned an invalid " + what + ".");
        }
        return root;
    }

    /** Optional single-line text, normalized; {@code null} when absent. */
    static String text(JsonNode node, String field, int maxCharacters, String what) throws IOException {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IOException("Ollama returned an invalid " + what + ".");
        }
        String normalized = UntrustedText.line(value.asText(), maxCharacters);
        return normalized.isEmpty() ? null : normalized;
    }

    /** A required non-negative integer that fits in a long. */
    static long nonNegativeLong(JsonNode node, String field, String what) throws IOException {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0) {
            throw new IOException("Ollama returned an invalid " + what + ".");
        }
        return value.asLong();
    }

    /** An optional integer in {@code [min, max]}; anything else is treated as not reported. */
    static Integer boundedInt(JsonNode value, int min, int max) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            return null;
        }
        int number = value.asInt();
        return number < min || number > max ? null : number;
    }

    /** An optional positive long up to {@code max}; anything else is treated as not reported. */
    static Long boundedLong(JsonNode value, long max) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            return null;
        }
        long number = value.asLong();
        return number <= 0 || number > max ? null : number;
    }

    /** {@code general.architecture} of a {@code model_info} block, when it is a plain identifier. */
    static String architecture(JsonNode modelInfo) {
        JsonNode value = modelInfo.path("general.architecture");
        return value.isTextual() && ARCHITECTURE.matcher(value.asText()).matches() ? value.asText() : null;
    }

    /** Capability names such as {@code completion}, {@code tools} or {@code thinking}; malformed entries are skipped. */
    static List<String> capabilities(JsonNode node) {
        JsonNode values = node.path("capabilities");
        List<String> capabilities = new ArrayList<>();
        if (!values.isArray()) {
            return capabilities;
        }
        for (JsonNode value : values) {
            if (capabilities.size() >= MAX_CAPABILITIES) {
                break;
            }
            if (value.isTextual() && CAPABILITY.matcher(value.asText()).matches()
                    && !capabilities.contains(value.asText())) {
                capabilities.add(value.asText());
            }
        }
        return capabilities;
    }
}
