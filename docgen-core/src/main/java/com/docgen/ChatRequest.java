package com.docgen;

import java.util.List;

/**
 * A chat turn for {@code /api/chat}. The context window is always explicit
 * (ADR 0003: without {@code num_ctx} Ollama silently drops the start of long
 * conversations), and {@code num_predict} bounds the answer.
 *
 * @param thinking  whether a model that can reason should do so; ignored for models that cannot
 * @param keepAlive how long Ollama keeps the model loaded afterwards ({@code null}: Ollama's default)
 */
public record ChatRequest(
        String model,
        List<ChatMessage> messages,
        int contextTokens,
        int maxOutputTokens,
        boolean thinking,
        String keepAlive
) {
    public static final int MAX_MESSAGES = 1_000;
    public static final int MAX_CHARACTERS = 4_000_000;
    public static final int MIN_CONTEXT_TOKENS = 512;

    public ChatRequest {
        OllamaClient.requireModelName(model);
        messages = List.copyOf(messages);
        if (messages.isEmpty() || messages.size() > MAX_MESSAGES) {
            throw new IllegalArgumentException("A chat needs between 1 and " + MAX_MESSAGES + " messages");
        }
        long characters = messages.stream().mapToLong(message -> message.content().length()).sum();
        if (characters > MAX_CHARACTERS) {
            throw new IllegalArgumentException("The conversation is too long");
        }
        if (contextTokens < MIN_CONTEXT_TOKENS || contextTokens > OllamaClient.MAX_REPORTED_CONTEXT_TOKENS) {
            throw new IllegalArgumentException("Invalid context window");
        }
        if (maxOutputTokens < 1 || maxOutputTokens > contextTokens) {
            throw new IllegalArgumentException("Invalid answer limit");
        }
        if (keepAlive != null && !keepAlive.matches("0|[1-9][0-9]{0,4}[smh]")) {
            throw new IllegalArgumentException("Invalid keep-alive");
        }
    }
}
