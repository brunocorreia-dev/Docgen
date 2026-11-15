package com.docgen;

/** A language model served by the local Ollama instance. */
public interface LLMProvider {
    String generateDocumentation(String prompt) throws Exception;

    /**
     * Requests a single JSON object. Providers that support a JSON output mode
     * enable it; the result is still untrusted and must be parsed defensively.
     */
    default String generateStructured(String prompt) throws Exception {
        return generateDocumentation(prompt);
    }

    /**
     * Fails before any generation starts when one of the prompts cannot be
     * answered, for example because it does not fit the model's context window,
     * so that callers never announce work that cannot start.
     */
    default void requireFits(String... prompts) throws Exception {
    }
}
