package com.docgen;

import java.io.IOException;
import java.util.List;

/**
 * The operations of the local Ollama that the desktop app uses. The only
 * implementation that talks to Ollama is {@link OllamaClient}; tests of the
 * app replace it with an in-memory fake.
 */
public interface OllamaApi {
    /** {@code GET /api/version}. */
    String version() throws IOException, InterruptedException;

    /** {@code GET /api/tags}: the models on disk. */
    List<InstalledModel> installedModels() throws IOException, InterruptedException;

    /** {@code POST /api/show}; {@link ModelNotInstalledException} when the model is not installed. */
    ModelDescription describe(String model) throws IOException, InterruptedException;

    /** {@code GET /api/ps}: the models held in memory. */
    List<LoadedModel> loadedModels() throws IOException, InterruptedException;

    /** {@code DELETE /api/delete}; {@link ModelNotInstalledException} when the model is not installed. */
    void delete(String model) throws IOException, InterruptedException;

    /**
     * {@code POST /api/pull}: Ollama downloads the model from its library and
     * reports progress until it succeeds. Only after an explicit click of the
     * user (ADR 0003). Cancelling closes the connection, which stops the
     * download; Ollama resumes it on the next pull. Throws
     * {@link java.util.concurrent.CancellationException} when cancelled.
     */
    void pull(String model, PullListener listener, Cancellation cancellation) throws IOException, InterruptedException;

    @FunctionalInterface
    interface PullListener {
        void onProgress(PullProgress progress);
    }

    /**
     * {@code POST /api/chat} with streaming. Before anything is sent, the model
     * is looked up and refused if it runs outside this computer
     * ({@link RemoteModelRefusedException}). The answer arrives in pieces;
     * reasoning, whether Ollama separates it or the model writes it inline,
     * goes only to {@link ChatListener#onThinking}. Cancelling closes the
     * connection, which stops the generation, and throws
     * {@link java.util.concurrent.CancellationException}.
     */
    ChatOutcome chat(ChatRequest request, ChatListener listener, Cancellation cancellation)
            throws IOException, InterruptedException;

    interface ChatListener {
        void onContent(String text);

        /** Reasoning of models that think before answering; never part of the answer. */
        default void onThinking(String text) {
        }
    }
}
