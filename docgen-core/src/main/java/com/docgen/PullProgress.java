package com.docgen;

import java.util.Locale;

/**
 * One progress event of a model download ({@code POST /api/pull}). Ollama
 * downloads a model as layers; each layer reports its own {@code digest},
 * {@code total} and {@code completed} bytes, and {@code completed} can be
 * missing at the start of a layer (docs/api.md). Ollama's status text is not
 * kept: only the stage it names.
 *
 * @param digest the layer being downloaded, or {@code null} outside the download stage
 */
public record PullProgress(Stage stage, String digest, long totalBytes, long completedBytes) {
    /** The steps of a pull, in order, as Ollama's status messages name them. */
    public enum Stage {
        MANIFEST, DOWNLOADING, VERIFYING, WRITING, CLEANUP, SUCCESS, OTHER;

        static Stage of(String status, String digest) {
            String text = status == null ? "" : status.toLowerCase(Locale.ROOT);
            if (text.equals("pulling manifest")) {
                return MANIFEST;
            }
            if (digest != null || text.startsWith("pulling ")) {
                return DOWNLOADING;
            }
            if (text.startsWith("verifying")) {
                return VERIFYING;
            }
            if (text.startsWith("writing manifest")) {
                return WRITING;
            }
            if (text.startsWith("removing")) {
                return CLEANUP;
            }
            if (text.equals("success")) {
                return SUCCESS;
            }
            return OTHER;
        }
    }
}
