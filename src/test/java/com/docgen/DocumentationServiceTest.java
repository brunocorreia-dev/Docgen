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
                DocumentationRequest.Operation.ANALYZE, List.of(), false, false));

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
                DocumentationRequest.Operation.ANALYZE, List.of(), false, false));
        IOException error = assertThrows(IOException.class, () -> service.execute(request(
                DocumentationRequest.Operation.GENERATE, List.of(), false, false)));

        assertTrue(analysis.analysis().includedFiles().isEmpty());
        assertTrue(error.getMessage().contains("no files matched"));
    }

    @Test
    void appliesTheExtensionAllowlistThroughTheExistingScanner() throws Exception {
        Files.writeString(repository.resolve("App.java"), "class App {}");
        Files.writeString(repository.resolve("notes.md"), "notes");

        DocumentationResult result = service(failingProviderResolver()).execute(request(
                DocumentationRequest.Operation.ANALYZE, List.of("md"), false, false));

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
                DocumentationRequest.Operation.ANALYZE, List.of(), false, false)));

        assertEquals(expected, actual);
    }

    @Test
    void generatesAndReturnsValidatedDocumentationWithASimulatedProvider() throws Exception {
        Files.writeString(repository.resolve("App.java"), "class App {}");
        AtomicInteger calls = new AtomicInteger();
        LLMProvider provider = provider(false, calls, "# Readme\n\nText", "# Architecture\n\nText");

        DocumentationResult result = service((name, model, remote) -> provider).execute(request(
                DocumentationRequest.Operation.GENERATE, List.of(), false, false));

        assertEquals(2, calls.get());
        assertEquals("# Readme\n\nText\n", result.readme());
        assertEquals("# Architecture\n\nText\n", result.architecture());
        assertFalse(result.persisted());
    }

    @Test
    void persistsTheValidatedPairWhenRequested() throws Exception {
        Files.writeString(repository.resolve("App.java"), "class App {}");
        AtomicInteger calls = new AtomicInteger();
        LLMProvider provider = provider(false, calls, "# Readme\n\nText", "# Architecture\n\nText");

        DocumentationResult result = service((name, model, remote) -> provider).execute(request(
                DocumentationRequest.Operation.GENERATE, List.of(), true, false));

        assertTrue(result.persisted());
        assertEquals(result.readme(), Files.readString(output.resolve("README.md")));
        assertEquals(result.architecture(), Files.readString(output.resolve("ARCHITECTURE.md")));
    }

    @Test
    void validatesBothResponsesBeforePublishingEitherDocument() throws Exception {
        Files.writeString(repository.resolve("App.java"), "class App {}");
        AtomicInteger calls = new AtomicInteger();
        LLMProvider provider = provider(false, calls, "# Readme\n\nValid", "<script>invalid</script>");

        assertThrows(IOException.class, () -> service((name, model, remote) -> provider).execute(request(
                DocumentationRequest.Operation.GENERATE, List.of(), true, false)));

        assertEquals(2, calls.get());
        assertFalse(Files.exists(output.resolve("README.md")));
        assertFalse(Files.exists(output.resolve("ARCHITECTURE.md")));
    }

    @Test
    void providerFailureDoesNotPublishAPartialDocumentationPair() throws Exception {
        Files.writeString(repository.resolve("App.java"), "class App {}");
        AtomicInteger calls = new AtomicInteger();
        LLMProvider provider = new LLMProvider() {
            @Override
            public String generateDocumentation(String prompt) throws Exception {
                if (calls.getAndIncrement() == 0) {
                    return "# Readme\n\nValid";
                }
                throw new IOException("provider failed");
            }

            @Override
            public boolean isRemote() {
                return false;
            }

            @Override
            public String describeDestination() {
                return "simulated local provider";
            }
        };

        assertThrows(IOException.class, () -> service((name, model, remote) -> provider).execute(request(
                DocumentationRequest.Operation.GENERATE, List.of(), true, false)));

        assertEquals(2, calls.get());
        assertFalse(Files.exists(output.resolve("README.md")));
        assertFalse(Files.exists(output.resolve("ARCHITECTURE.md")));
    }

    @Test
    void analysisAndPromptPreviewNeverResolveOrCallAProvider() throws Exception {
        Files.writeString(repository.resolve("App.java"), "class App {}");
        AtomicInteger resolutions = new AtomicInteger();
        DocumentationService service = service((name, model, remote) -> {
            resolutions.incrementAndGet();
            throw new AssertionError("provider must not be resolved");
        });

        service.execute(request(DocumentationRequest.Operation.ANALYZE, List.of(), false, false));
        DocumentationResult preview = service.execute(request(
                DocumentationRequest.Operation.PREVIEW_PROMPTS, List.of(), false, false));

        assertEquals(0, resolutions.get());
        assertTrue(preview.readmePrompt().contains("<repository_snapshot>"));
        assertNull(preview.readme());
    }

    @Test
    void remoteGenerationRequiresBothExplicitPermissionAndInterfaceConsent() throws Exception {
        Files.writeString(repository.resolve("App.java"), "class App {}");
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger consentChecks = new AtomicInteger();
        LLMProvider remoteProvider = provider(true, calls, "# Readme", "# Architecture");
        DocumentationService service = service((name, model, remote) -> remoteProvider);

        assertThrows(IllegalArgumentException.class, () -> service.execute(
                request(DocumentationRequest.Operation.GENERATE, List.of(), false, false),
                transmission -> {
                    consentChecks.incrementAndGet();
                    return true;
                },
                DocumentationService.ProgressListener.NONE));
        assertThrows(DocumentationService.RemoteConsentDeniedException.class, () -> service.execute(
                request(DocumentationRequest.Operation.GENERATE, List.of(), false, true),
                transmission -> {
                    consentChecks.incrementAndGet();
                    return false;
                },
                DocumentationService.ProgressListener.NONE));

        assertEquals(1, consentChecks.get(), "--allow-remote remains separate from UI confirmation");
        assertEquals(0, calls.get());
    }

    private DocumentationRequest request(
            DocumentationRequest.Operation operation,
            List<String> extensions,
            boolean persist,
            boolean allowRemote
    ) {
        return new DocumentationRequest(
                repository, output, operation, "ollama", null, extensions,
                allowRemote, persist, false);
    }

    private static DocumentationService service(DocumentationService.ProviderResolver resolver) {
        return service((path, options) -> new RepoScanner().scan(path, options), resolver);
    }

    private static DocumentationService service(
            DocumentationService.RepositoryReader reader,
            DocumentationService.ProviderResolver resolver
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

    private static DocumentationService.ProviderResolver failingProviderResolver() {
        return (name, model, remote) -> {
            throw new AssertionError("provider must not be resolved");
        };
    }

    private static LLMProvider provider(
            boolean remote,
            AtomicInteger calls,
            String firstResponse,
            String secondResponse
    ) {
        return new LLMProvider() {
            @Override
            public String generateDocumentation(String prompt) {
                return calls.getAndIncrement() == 0 ? firstResponse : secondResponse;
            }

            @Override
            public boolean isRemote() {
                return remote;
            }

            @Override
            public String describeDestination() {
                return remote ? "simulated remote provider" : "simulated local provider";
            }
        };
    }
}
