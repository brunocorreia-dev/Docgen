package com.docgen;

import java.io.IOException;

/**
 * Ollama evaluated far fewer prompt tokens than the prompt can contain, so it
 * dropped part of the input despite the requested context window. The answer
 * is discarded because the rules at the start of the prompt may be missing.
 */
public final class PromptTruncatedException extends IOException {
    public PromptTruncatedException(int evaluatedTokens, int promptBytes) {
        super("Ollama evaluated only " + evaluatedTokens + " tokens of a " + promptBytes
                + "-byte prompt, so part of the input was dropped. The answer was discarded.");
    }
}
