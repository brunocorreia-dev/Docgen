package com.docgen.api;

import com.docgen.ModelNotInstalledException;
import com.docgen.OllamaUnavailableException;
import com.docgen.RemoteModelRefusedException;
import org.springframework.http.HttpStatus;

import java.net.http.HttpTimeoutException;

/**
 * Turns a failed Ollama call into a fixed, client-safe error. Messages are
 * constants: Ollama's own text never reaches the client or the log.
 */
final class OllamaFailures {
    private OllamaFailures() {
    }

    static StatusException translate(Exception failure) {
        if (failure instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        if (failure instanceof OllamaUnavailableException) {
            return unavailable();
        }
        if (failure instanceof ModelNotInstalledException) {
            return new StatusException(HttpStatus.NOT_FOUND, "model_not_installed",
                    "The model is not installed in Ollama.");
        }
        if (failure instanceof RemoteModelRefusedException) {
            return new StatusException(HttpStatus.UNPROCESSABLE_CONTENT, "remote_model_refused",
                    "The model runs outside this computer, so DocGen refused to use it.");
        }
        if (failure instanceof HttpTimeoutException) {
            return new StatusException(HttpStatus.GATEWAY_TIMEOUT, "ollama_timeout", "Ollama did not answer in time.");
        }
        return new StatusException(HttpStatus.BAD_GATEWAY, "ollama_failed", "The Ollama request failed.");
    }

    static StatusException unavailable() {
        return new StatusException(HttpStatus.SERVICE_UNAVAILABLE, "ollama_unavailable",
                "Ollama is not reachable on this computer.");
    }

    static StatusException notInCatalog() {
        return new StatusException(HttpStatus.UNPROCESSABLE_CONTENT, "not_in_catalog",
                "DocGen only downloads and uses the models of its catalog.");
    }
}
