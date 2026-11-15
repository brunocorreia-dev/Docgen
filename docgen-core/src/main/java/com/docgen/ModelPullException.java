package com.docgen;

import java.io.IOException;
import java.util.Locale;

/**
 * Ollama reported that a download failed. Ollama sends the error inside the
 * stream with HTTP 200 (verified with 0.32.11), as free text; it is only
 * classified, never shown or logged.
 */
public final class ModelPullException extends IOException {
    public enum Reason {
        /** The registry has no such model or tag. */
        NOT_FOUND,
        /** The registry could not be reached: no internet, DNS or TLS failure. */
        REGISTRY_UNREACHABLE,
        /** The disk where Ollama keeps models is full. */
        DISK_FULL,
        FAILED
    }

    private final Reason reason;

    public ModelPullException(Reason reason) {
        super(switch (reason) {
            case NOT_FOUND -> "The Ollama library has no model with this name.";
            case REGISTRY_UNREACHABLE -> "Ollama could not reach its model library. Check the internet connection.";
            case DISK_FULL -> "There is not enough disk space for the model.";
            case FAILED -> "Ollama could not download the model.";
        });
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    /** Classifies Ollama's error text, which comes from Go's network and file errors. */
    static ModelPullException from(String error) {
        String text = error == null ? "" : error.toLowerCase(Locale.ROOT);
        if (text.contains("file does not exist") || text.contains("not found")) {
            return new ModelPullException(Reason.NOT_FOUND);
        }
        if (text.contains("no space left")) {
            return new ModelPullException(Reason.DISK_FULL);
        }
        for (String network : new String[]{"dial tcp", "no such host", "i/o timeout", "connection refused",
                "network is unreachable", "tls", "connection reset", "eof"}) {
            if (text.contains(network)) {
                return new ModelPullException(Reason.REGISTRY_UNREACHABLE);
            }
        }
        return new ModelPullException(Reason.FAILED);
    }
}
