package com.docgen;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

public final class OutputWriter {
    private final SecurePairWriter.Observer observer;

    public OutputWriter() {
        this(null);
    }

    OutputWriter(SecurePairWriter.Observer observer) {
        this.observer = observer;
    }

    public void preflight(Path outputDir, boolean force) throws IOException {
        Objects.requireNonNull(outputDir, "outputDir");
        SecurePairWriter.preflight(
                outputDir,
                Path.of("README.md"),
                Path.of("ARCHITECTURE.md"),
                force,
                SecurePairWriter.Kind.DOCUMENTATION
        );
    }

    public void write(Path outputDir, String readme, String architecture, boolean force) throws IOException {
        Objects.requireNonNull(outputDir, "outputDir");
        SecurePairWriter.write(
                outputDir,
                new SecurePairWriter.Spec(Path.of("README.md"), readme),
                new SecurePairWriter.Spec(Path.of("ARCHITECTURE.md"), architecture),
                force,
                SecurePairWriter.Kind.DOCUMENTATION,
                observer
        );
    }
}
