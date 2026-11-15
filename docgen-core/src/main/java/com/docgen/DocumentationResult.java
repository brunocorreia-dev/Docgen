package com.docgen;

import java.util.List;
import java.util.Objects;

/**
 * Structured engine output safe to adapt to different interfaces.
 * Absolute repository/output paths and raw source contents are intentionally
 * absent. Prompt text is returned only for the explicit preview operation.
 */
public record DocumentationResult(
        DocumentationRequest.Operation operation,
        RepositoryAnalysis analysis,
        String readmePrompt,
        String architecturePrompt,
        String readme,
        String architecture,
        boolean persisted
) {
    public DocumentationResult {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(analysis, "analysis");
    }

    public record RepositoryAnalysis(
            List<SelectedFile> includedFiles,
            int totalCharacters,
            List<String> skippedFiles,
            List<String> excludedFiles
    ) {
        public RepositoryAnalysis {
            includedFiles = List.copyOf(includedFiles);
            skippedFiles = List.copyOf(skippedFiles);
            excludedFiles = List.copyOf(excludedFiles);
        }
    }

    public record SelectedFile(String relativePath, int characters, boolean truncated) {
        public SelectedFile {
            Objects.requireNonNull(relativePath, "relativePath");
        }
    }
}
