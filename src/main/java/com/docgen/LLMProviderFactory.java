package com.docgen;

import java.util.Locale;

public final class LLMProviderFactory {
    private LLMProviderFactory() {
    }

    public static LLMProvider create(String provider, String model, boolean allowRemote) {
        String selected = provider == null ? "groq" : provider.toLowerCase(Locale.ROOT);
        return switch (selected) {
            case "groq" -> {
                if (!allowRemote) {
                    throw new IllegalArgumentException("Groq sends repository content to a remote service. Re-run with --allow-remote if you consent.");
                }
                yield new GroqProvider(model == null || model.isBlank() ? "llama-3.3-70b-versatile" : model);
            }
            case "ollama" -> new OllamaProvider(model == null || model.isBlank() ? "llama3.2" : model);
            default -> throw new IllegalArgumentException("Unsupported provider: " + provider + ". Use 'groq' or 'ollama'.");
        };
    }
}
