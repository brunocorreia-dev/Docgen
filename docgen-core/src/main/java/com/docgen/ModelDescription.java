package com.docgen;

import java.util.List;

/**
 * What {@code /api/show} says about an installed model. Architecture numbers
 * come from {@code model_info} ({@code <architecture>.block_count} and so on)
 * and are {@code null} when Ollama does not report them as plain integers, for
 * example per-layer arrays.
 *
 * @param contextLength the model's maximum context window in tokens
 * @param remote        the model runs outside this computer (see {@link RemoteModels})
 */
public record ModelDescription(
        String name,
        String architecture,
        Integer contextLength,
        Long parameterCount,
        String parameterSize,
        String quantization,
        String family,
        List<String> capabilities,
        Integer layers,
        Integer kvHeads,
        Integer headDim,
        boolean remote
) {
    public ModelDescription {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }

    /** The model can reason before answering (Qwen3 and later); that reasoning is never part of the answer. */
    public boolean thinking() {
        return capabilities.contains("thinking");
    }
}
