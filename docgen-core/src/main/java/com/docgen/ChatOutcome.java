package com.docgen;

import java.time.Duration;

/**
 * How a chat reply ended, from the last {@code /api/chat} chunk. Counts and
 * durations are {@code null} when Ollama did not report them.
 *
 * @param doneReason {@code stop} (the model finished) or {@code length} (it hit {@code num_predict})
 */
public record ChatOutcome(
        String doneReason,
        Integer promptTokens,
        Integer outputTokens,
        Duration loadDuration,
        Duration totalDuration,
        Duration evalDuration
) {
    /** The answer was cut at the output limit instead of finishing. */
    public boolean cutAtLimit() {
        return "length".equals(doneReason);
    }

    /** Generation speed, when Ollama reported both the token count and the time. */
    public Double tokensPerSecond() {
        if (outputTokens == null || evalDuration == null || evalDuration.isZero() || evalDuration.isNegative()) {
            return null;
        }
        return outputTokens / (evalDuration.toNanos() / 1_000_000_000.0);
    }
}
