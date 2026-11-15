package com.docgen.models;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One Qwen model of {@code models/qwen-catalog.json}: an Ollama tag DocGen
 * may download and use (ADR 0003, layer 1), with what the recommender needs
 * to estimate its memory. Sources are in the {@code evidence} of each entry.
 *
 * @param parameterCount       all weights, the quality proxy used to rank models
 * @param activeParameterCount weights used per token (smaller than the total in mixture-of-experts
 *                             models), the speed proxy
 * @param downloadSize         the size as the Ollama library shows it, for the screen
 * @param downloadBytes        the upper bound of that size, used for memory and disk estimates
 * @param contextTokens        the model's maximum context window
 */
public record CatalogModel(
        String tag,
        String family,
        long parameterCount,
        long activeParameterCount,
        String quantization,
        String downloadSize,
        long downloadBytes,
        int contextTokens,
        Architecture architecture,
        List<Purpose> purposes,
        Thinking thinking,
        boolean vision,
        String description,
        boolean verified,
        String evidence
) {
    public CatalogModel {
        purposes = purposes == null ? List.of() : List.copyOf(purposes);
    }

    /**
     * What sizes the cache that grows with the context.
     *
     * @param attentionLayers layers with full attention; only they keep a KV cache
     * @param stateBytes      fixed state of linear-attention layers (Qwen3.5), independent of the context
     */
    public record Architecture(int layers, int attentionLayers, int kvHeads, int headDim, long stateBytes) {
    }

    public enum Purpose {
        @JsonProperty("general") GENERAL,
        @JsonProperty("code") CODE,
        @JsonProperty("reasoning") REASONING
    }

    /** {@code HYBRID}: thinks by default and can be told not to; {@code NONE}: answers directly. */
    public enum Thinking {
        @JsonProperty("none") NONE,
        @JsonProperty("hybrid") HYBRID
    }
}
