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
}
