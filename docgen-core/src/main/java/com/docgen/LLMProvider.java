package com.docgen;

public interface LLMProvider {
    String generateDocumentation(String prompt) throws Exception;

    /**
     * Requests a single JSON object. Providers that support a JSON output mode
     * enable it; the result is still untrusted and must be parsed defensively.
     */
    default String generateStructured(String prompt) throws Exception {
        return generateDocumentation(prompt);
    }

    /** True when prompts leave the local machine. Drives consent checks and warnings. */
    boolean isRemote();

    /** Human-readable destination of prompts, shown in warnings before sending. */
    String describeDestination();
}
