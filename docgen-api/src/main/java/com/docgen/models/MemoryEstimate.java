package com.docgen.models;

/**
 * Memory a model needs to run with a given context window; see
 * {@link ModelRecommender#estimate} for the formula and its assumptions.
 */
public record MemoryEstimate(
        int contextTokens,
        long weightsBytes,
        long kvCacheBytes,
        long stateBytes,
        long overheadBytes
) {
    public long totalBytes() {
        return weightsBytes + kvCacheBytes + stateBytes + overheadBytes;
    }
}
