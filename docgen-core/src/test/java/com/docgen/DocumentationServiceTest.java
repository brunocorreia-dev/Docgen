package com.docgen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentationServiceTest {
    @TempDir
    Path repository;

    @TempDir
    Path output;

    @Test
    void analyzesAValidRepositoryWithoutExposingAbsolutePathsOrSourceContent() throws Exception {
        Files.writeString(repository.resolve("App.java"), "class App {}");

        DocumentationResult result = service(failingProviderResolver()).execute(request(
                DocumentationRequest.Operation.ANALYZE, List.of(), false));

        assertEquals(List.of("App.java"), result.analysis().includedFiles().stream()
                .map(DocumentationResult.SelectedFile::relativePath).toList());
        assertEquals("class App {}".length(), result.analysis().totalCharacters());
        assertNull(result.readme());
        assertFalse(result.persisted());
        assertFalse(result.toString().contains(repository.toString()));
        assertFalse(result.toString().contains("class App {}"));
    }

    @Test
    void distinguishesEmptyAnalysisFromGenerationWithNothingToDocument() throws Exception {
        Files.writeString(repository.resolve("archive.bin"), "binary");
        DocumentationService service = service(failingProviderResolver());

        DocumentationResult analysis = service.execute(request(
                DocumentationRequest.Operation.ANALYZE, List.of(), false));
        IOException error = assertThrows(IOException.class, () -> service.execute(request(
                DocumentationRequest.Operation.GENERATE, List.of(), false)));

        assertTrue(analysis.analysis().includedFiles().isEmpty());
        assertTrue(error.getMessage().contains("no files matched"));
    }

    @Test
    void appliesTheExtensionAllowlistThroughTheExistingScanner() throws Exception {
        Files.writeString(repository.resolve("App.java"), "class App {}");
        Files.writeString(repository.resolve("notes.md"), "notes");

        DocumentationResult result = service(failingProviderResolver()).execute(request(
                DocumentationRequest.Operation.ANALYZE, List.of("md"), false));

        assertEquals(List.of("notes.md"), result.analysis().includedFiles().stream()
                .map(DocumentationResult.SelectedFile::relativePath).toList());
        assertTrue(result.analysis().excludedFiles().contains("App.java (not in --include-ext allowlist)"));
    }

    @Test
    void propagatesRepositoryReadFailuresWithoutTerminalOutput() {
        IOException expected = new IOException("secure read failed");
        DocumentationService service = service((path, options) -> {
            throw expected;
        }, failingProviderResolver());

        IOException actual = assertThrows(IOException.class, () -> service.execute(request(
                DocumentationRequest.Operation.ANALYZE, List.of(), false)));

        assertEquals(expected, actual);
    }

    @Test
    void generatesAndReturnsValidatedDocumentationWithASimulatedProvider() throws Exception {
        Files.writeString(repository.resolve("App.java"), "class App {}");
        AtomicInteger calls = new AtomicInteger();
        LLMProvider provider = provider(calls, "# Readme\n\nText", "# Architecture\n\nText");

        DocumentationResult result = service(model -> provider).execute(request(
                DocumentationRequest.Operation.GENERATE, List.of(), false));

        assertEquals(2, calls.get());
        assertEquals("# Readme\n\nText\n", result.readme());
        assertEquals("# Architecture\n\nText\n", result.architecture());
        assertFalse(result.persisted());
    }

    @Test
    void persistsTheValidatedPairWhenRequested() throws Exception {
        Files.writeString(repository.resolve("App.java"), "class App {}");
        AtomicInteger calls = new AtomicInteger();
        LLMProvider provider = provider(calls, "# Readme\n\nText", "# Architecture\n\nText");

        DocumentationResult result = service(model -> provider).execute(request(
                DocumentationRequest.Operation.GENERATE, List.of(), true));

        assertTrue(result.persisted());
        assertEquals(result.readme(), Files.readString(output.resolve("README.md")));
        assertEquals(result.architecture(), Files.readString(output.resolve("ARCHITECTURE.md")));
    }

    @Test
    void validatesBothResponsesBeforePublishingEitherDocument() throws Exception {
        Files.writeString(repository.resolve("App.java"), "class App {}");
        AtomicInteger calls = new AtomicInteger();
        LLMProvider provider = provider(calls, "# Readme\n\nValid", "<script>invalid</script>");

        assertThrows(IOException.class, () -> service(model -> provider).execute(request(
                DocumentationRequest.Operation.GENERATE, List.of(), true)));

        assertEquals(2, calls.get());
        assertFalse(Files.exists(output.resolve("README.md")));
        assertFalse(Files.exists(output.resolve("ARCHITECTURE.md")));
    }

    @Test
    void providerFailureDoesNotPublishAPartialDocumentationPair() throws Exception {
        Files.writeString(repository.resolve("App.java"), "class App {}");
        AtomicInteger calls = new AtomicInteger();
        LLMProvider provider = prompt -> {
            if (calls.getAndIncrement() == 0) {
                return "# Readme\n\nValid";
            }
            throw new IOException("provider failed");
        };

        assertThrows(IOException.class, () -> service(model -> provider).execute(request(
                DocumentationRequest.Operation.GENERATE, List.of(), true)));

        assertEquals(2, calls.get());
        assertFalse(Files.exists(output.resolve("README.md")));
        assertFalse(Files.exists(output.resolve("ARCHITECTURE.md")));
    }

    @Test
    void analysisAndPromptPreviewNeverResolveOrCallAProvider() throws Exception {
        Files.writeString(repository.resolve("App.java"), "class App {}");
        AtomicInteger resolutions = new AtomicInteger();
        DocumentationService service = service(model -> {
            resolutions.incrementAndGet();
            throw new AssertionError("provider must not be resolved");
        });

        service.execute(request(DocumentationRequest.Operation.ANALYZE, List.of(), false));
        DocumentationResult preview = service.execute(request(
                DocumentationRequest.Operation.PREVIEW_PROMPTS, List.of(), false));

        assertEquals(0, resolutions.get());
        assertTrue(preview.readmePrompt().contains("<repository_snapshot>"));
        assertNull(preview.readme());
    }

    @Test
    void passesTheRequestedModelToTheLocalProvider() throws Exception {
        Files.writeString(repository.resolve("App.java"), "class App {}");
        AtomicInteger calls = new AtomicInteger();
        List<String> models = new java.util.ArrayList<>();
        LLMProvider provider = provider(calls, "# Readme\n\nText", "# Architecture\n\nText");
        DocumentationService service = service(model -> {
            models.add(model);
            return provider;
        });

        service.execute(new DocumentationRequest(repository, output, DocumentationRequest.Operation.GENERATE,
                "qwen2.5-coder:7b", List.of(), false, false));

        assertEquals(List.of("qwen2.5-coder:7b"), models);
        assertEquals(2, calls.get());
    }

    private DocumentationRequest request(
            DocumentationRequest.Operation operation,
            List<String> extensions,
            boolean persist
    ) {
        return new DocumentationRequest(repository, output, operation, null, extensions, persist, false);
    }

    private static DocumentationService service(LLMProviderResolver resolver) {
        return service((path, options) -> new RepoScanner().scan(path, options), resolver);
    }

    private static DocumentationService service(
            DocumentationService.RepositoryReader reader,
            LLMProviderResolver resolver
    ) {
        OutputWriter outputWriter = new OutputWriter();
        PromptPreviewWriter previewWriter = new PromptPreviewWriter();
        return new DocumentationService(
                reader,
                new PromptBuilder(),
                resolver,
                new DocumentationService.DocumentStore() {
                    @Override
                    public void preflight(Path directory, boolean force) throws IOException {
                        outputWriter.preflight(directory, force);
                    }

                    @Override
                    public void write(Path directory, String readme, String architecture, boolean force)
                            throws IOException {
                        outputWriter.write(directory, readme, architecture, force);
                    }
                },
                new DocumentationService.PromptStore() {
                    @Override
                    public void preflight(Path directory, boolean force) throws IOException {
                        previewWriter.preflight(directory, force);
                    }

                    @Override
                    public void write(Path directory, String readme, String architecture, boolean force)
                            throws IOException {
                        previewWriter.write(directory, readme, architecture, force);
                    }
                }
        );
    }

    private static LLMProviderResolver failingProviderResolver() {
        return model -> {
            throw new AssertionError("provider must not be resolved");
        };
    }

    private static LLMProvider provider(AtomicInteger calls, String firstResponse, String secondResponse) {
        return prompt -> calls.getAndIncrement() == 0 ? firstResponse : secondResponse;
    }
}
