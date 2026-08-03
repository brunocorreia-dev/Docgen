package com.docgen;

import java.io.IOException;
import java.nio.file.Path;

/** Writes the two sensitive prompt snapshots as one rollback-safe operation. */
final class PromptPreviewWriter {
    private final SecurePairWriter.Observer observer;

    PromptPreviewWriter() {
        this(null);
    }

    PromptPreviewWriter(SecurePairWriter.Observer observer) {
        this.observer = observer;
    }

    void preflight(Path outputDirectory, boolean force) throws IOException {
        SecurePairWriter.preflight(
                outputDirectory,
                Path.of(DocGenCLI.README_PROMPT_FILE),
                Path.of(DocGenCLI.ARCHITECTURE_PROMPT_FILE),
                force,
                SecurePairWriter.Kind.PROMPT_PREVIEW
        );
    }

    void write(Path outputDirectory, String readmePrompt, String architecturePrompt, boolean force)
            throws IOException {
        SecurePairWriter.write(
                outputDirectory,
                new SecurePairWriter.Spec(Path.of(DocGenCLI.README_PROMPT_FILE), readmePrompt),
                new SecurePairWriter.Spec(Path.of(DocGenCLI.ARCHITECTURE_PROMPT_FILE), architecturePrompt),
                force,
                SecurePairWriter.Kind.PROMPT_PREVIEW,
                observer
        );
    }
}
