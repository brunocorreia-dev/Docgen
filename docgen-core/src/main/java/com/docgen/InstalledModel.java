package com.docgen;

import java.util.List;

/**
 * A model of the local Ollama, as {@code /api/tags} lists it. Text fields are
 * normalized and bounded; {@code null} means Ollama did not report the value.
 *
 * @param remote the model runs outside this computer (see {@link RemoteModels}); DocGen never uses it
 */
public record InstalledModel(
        String name,
        long sizeBytes,
        String digest,
        String family,
        String parameterSize,
        String quantization,
        Integer contextLength,
        List<String> capabilities,
        String modifiedAt,
        boolean remote
) {
    public InstalledModel {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }
}
