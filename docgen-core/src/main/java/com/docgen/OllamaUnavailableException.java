package com.docgen;

import java.io.IOException;

/**
 * Nothing answers on the Ollama port: Ollama is not installed, not running or
 * listens on another port. Raised instead of a generic request failure so the
 * user is told to open Ollama rather than to retry.
 */
public final class OllamaUnavailableException extends IOException {
    public OllamaUnavailableException() {
        super("Ollama is not reachable on this computer.");
    }
}
