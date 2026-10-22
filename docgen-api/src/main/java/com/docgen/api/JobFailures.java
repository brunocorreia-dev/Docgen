package com.docgen.api;

import com.docgen.ArchitecturePlanParser;
import com.docgen.DocumentationService;
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
        if (failure instanceof DocumentationService.RemoteConsentDeniedException) {
            return new Failure("consent_required", "Remote transmission was not authorized.");
        }
        if (failure instanceof ArchitecturePlanParser.InvalidPlanException) {
            return new Failure("invalid_model_output", "The model did not return a valid architecture plan.");
        }
        if (failure instanceof UnsafeOutputException) {
            return new Failure("unsafe_model_output",
                    "The generated documentation failed the passive Markdown safety checks.");
        }
        if (failure instanceof HttpTimeoutException) {
            return new Failure("provider_timeout", "The AI provider did not answer in time.");
        }
        if (failure instanceof ConnectException || message.contains("is not responding")) {
            return new Failure("provider_unavailable", "The AI provider is not reachable.");
        }
        if (failure instanceof IllegalStateException && message.contains("GROQ_API_KEY")) {
            return new Failure("provider_not_configured", "The AI provider is not configured on the server.");
        }
        if (failure instanceof IOException) {
            if (message.contains("HTTP 429")) {
                return new Failure("provider_rate_limited", "The AI provider rate limit was reached.");
            }
            if (message.contains("HTTP 401") || message.contains("HTTP 403")) {
                return new Failure("provider_rejected", "The AI provider rejected the server credentials.");
            }
            if (message.contains("HTTP 413")) {
                return new Failure("provider_payload_too_large", "The request is too large for the AI provider.");
            }
            if (message.startsWith("Groq") || message.startsWith("Ollama")) {
                return new Failure("provider_failed", "The AI provider request failed.");
            }
        }
        return null;
    }
}
