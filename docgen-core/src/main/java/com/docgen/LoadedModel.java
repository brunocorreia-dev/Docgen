package com.docgen;

/**
 * A model Ollama holds in memory, as {@code /api/ps} reports it.
 *
 * @param sizeBytes     memory the loaded model takes, on the GPU and in RAM together
 * @param vramBytes     the part of {@code sizeBytes} on the GPU; equal means fully on the GPU
 * @param contextLength the context window it was loaded with, when reported
 */
public record LoadedModel(String name, long sizeBytes, long vramBytes, Integer contextLength, String expiresAt) {
}
