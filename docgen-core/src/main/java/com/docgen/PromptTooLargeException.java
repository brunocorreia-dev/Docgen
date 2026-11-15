package com.docgen;

import java.io.IOException;

/**
 * The prompt plus the space reserved for the answer does not fit the model's
 * context window. It is raised before anything is sent, instead of letting
 * Ollama drop the start of the prompt.
 */
public final class PromptTooLargeException extends IOException {
    public PromptTooLargeException(String model, long neededTokens, int contextTokens) {
        super("The input needs about " + neededTokens + " tokens (an estimate that includes "
                + OllamaProvider.OUTPUT_RESERVE_TOKENS + " for the answer), but model " + model
                + " accepts at most " + contextTokens
                + ". Reduce the input or choose a model with a larger context window.");
    }
}
