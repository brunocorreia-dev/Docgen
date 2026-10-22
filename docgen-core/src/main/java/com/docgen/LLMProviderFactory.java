package com.docgen;

import java.util.Locale;
import java.util.List;

public final class LLMProviderFactory {
    private static final String GROQ_DEFAULT_MODEL = "llama-3.3-70b-versatile";
    private static final String OLLAMA_DEFAULT_MODEL = "llama3.2";
    private static final List<ProviderDescriptor> SUPPORTED_PROVIDERS = List.of(
            new ProviderDescriptor("groq", true, GROQ_DEFAULT_MODEL),
            new ProviderDescriptor("ollama", false, OLLAMA_DEFAULT_MODEL)
    );

    private LLMProviderFactory() {
    }

    public static LLMProvider create(String provider, String model, boolean allowRemote) {
        String selected = provider == null ? "groq" : provider.toLowerCase(Locale.ROOT);
        return switch (selected) {
            case "groq" -> {
                if (!allowRemote) {
                    throw new IllegalArgumentException("Groq sends repository content to a remote service. Re-run with --allow-remote if you consent.");
                }
                yield new GroqProvider(model == null || model.isBlank() ? GROQ_DEFAULT_MODEL : model);
            }
            case "ollama" -> new OllamaProvider(model == null || model.isBlank() ? OLLAMA_DEFAULT_MODEL : model);
            default -> throw new IllegalArgumentException("Unsupported provider: " + provider + ". Use 'groq' or 'ollama'.");
        };
    }

    /** Returns static provider metadata without constructing clients or making network calls. */
    public static List<ProviderDescriptor> supportedProviders() {
        return SUPPORTED_PROVIDERS;
    }

    public record ProviderDescriptor(String id, boolean remote, String defaultModel) {
    }
}
