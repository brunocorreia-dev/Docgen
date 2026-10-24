package com.docgen;

/** Creates the local Ollama provider. DocGen has no remote provider. */
public final class LLMProviderFactory {
    public static final String DEFAULT_MODEL = "llama3.2";

    private LLMProviderFactory() {
    }

    public static LLMProvider create(String model) {
        return new OllamaProvider(model == null || model.isBlank() ? DEFAULT_MODEL : model);
    }
}
