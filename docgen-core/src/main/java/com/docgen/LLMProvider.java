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
}
