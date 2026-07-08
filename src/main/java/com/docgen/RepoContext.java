package com.docgen;

import java.nio.file.Path;
import java.util.List;

/**
 * Snapshot of a scanned repository. {@code skippedFiles} (size/limit skips) is
 * surfaced to the model inside the prompt; {@code excludedFiles} (sensitive,
 * ignored or filtered-out files) is intentionally kept out of prompts and only
 * shown locally, e.g. by {@code --dry-run}.
 */
public record RepoContext(
        Path root,
        String fileTree,
        List<ScannedFile> files,
        int totalCharacters,
        List<String> skippedFiles,
        List<String> excludedFiles
) {
    public RepoContext {
        files = List.copyOf(files);
        skippedFiles = List.copyOf(skippedFiles);
        excludedFiles = List.copyOf(excludedFiles);
    }

    public record ScannedFile(Path relativePath, String content, boolean truncated) {
        public ScannedFile {
            if (relativePath == null) {
                throw new IllegalArgumentException("relativePath cannot be null");
            }
            content = content == null ? "" : content;
        }
    }
}
