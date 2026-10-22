package com.docgen;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Server-side input for the documentation engine.
 *
 * <p>The paths are trusted application configuration, not an HTTP transport
 * contract. A future API must resolve an authorized repository identifier to
 * these paths instead of binding client-supplied filesystem paths directly.</p>
 */
public record DocumentationRequest(
        Path repository,
        Path outputDirectory,
        Operation operation,
        String provider,
        String model,
        List<String> includeExtensions,
        boolean allowRemote,
        boolean persist,
        boolean force
) {
    public DocumentationRequest {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(operation, "operation");
        includeExtensions = includeExtensions == null ? List.of() : List.copyOf(includeExtensions);
        if (persist && operation != Operation.ANALYZE && outputDirectory == null) {
            throw new IllegalArgumentException("outputDirectory is required when results are persisted");
        }
    }

    public enum Operation {
        ANALYZE,
        PREVIEW_PROMPTS,
        GENERATE
    }
}
