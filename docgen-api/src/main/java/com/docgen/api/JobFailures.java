package com.docgen.api;

import com.docgen.ArchitecturePlanParser;
import com.docgen.ModelNotInstalledException;
import com.docgen.OllamaUnavailableException;
import com.docgen.PromptTooLargeException;
import com.docgen.PromptTruncatedException;
import com.docgen.RemoteModelRefusedException;
import com.docgen.UnsafeOutputException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CancellationException;

/**
 * Maps job failures to stable codes and fixed messages. Exception messages are
 * never returned to clients: they can contain provider responses or user data.
 */
final class JobFailures {
    private JobFailures() {
    }

    record Failure(String code, String message) {
    }

    static Failure classify(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
            Failure known = known(cause);
            if (known != null) {
                return known;
            }
        }
        return new Failure("internal_error", "The job failed unexpectedly.");
    }

    private static Failure known(Throwable failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        if (failure instanceof InterruptedException || failure instanceof CancellationException) {
            return new Failure("cancelled", "The job was interrupted.");
        }
        if (failure instanceof ArchitecturePlanParser.InvalidPlanException) {
            return new Failure("invalid_model_output", "The model did not return a valid architecture plan.");
        }
        if (failure instanceof UnsafeOutputException) {
            return new Failure("unsafe_model_output",
                    "The generated documentation failed the passive Markdown safety checks.");
        }
        if (failure instanceof ModelNotInstalledException) {
            return new Failure("model_not_installed", "The selected model is not installed in Ollama.");
        }
        if (failure instanceof PromptTooLargeException) {
            return new Failure("prompt_too_large", "The input does not fit the model's context window.");
        }
        if (failure instanceof PromptTruncatedException) {
            return new Failure("prompt_truncated", "Ollama dropped part of the input, so the result was discarded.");
        }
        if (failure instanceof OllamaUnavailableException || failure instanceof ConnectException
                || message.contains("is not responding")) {
            return new Failure("provider_unavailable", "Ollama is not reachable on this computer.");
        }
        if (failure instanceof RemoteModelRefusedException) {
            return new Failure("remote_model_refused",
                    "The selected model runs outside this computer, so DocGen refused to use it.");
        }
        if (failure instanceof HttpTimeoutException) {
            return new Failure("provider_timeout", "Ollama did not answer in time.");
        }
        if (failure instanceof IOException && message.startsWith("Ollama")) {
            return new Failure("provider_failed", "The Ollama request failed.");
        }
        return null;
    }
}
