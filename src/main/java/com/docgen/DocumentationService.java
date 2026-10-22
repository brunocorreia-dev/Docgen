package com.docgen;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Orchestrates the reusable, terminal-independent documentation pipeline. */
public final class DocumentationService {
    private final RepositoryReader repositoryReader;
    private final PromptBuilder promptBuilder;
    private final ProviderResolver providerResolver;
    private final DocumentStore documentStore;
    private final PromptStore promptStore;

    public DocumentationService() {
        this(
                (repository, options) -> new RepoScanner().scan(repository, options),
                new PromptBuilder(),
                LLMProviderFactory::create,
                new DocumentStore() {
                    private final OutputWriter writer = new OutputWriter();

                    @Override
                    public void preflight(Path outputDirectory, boolean force) throws IOException {
                        writer.preflight(outputDirectory, force);
                    }

                    @Override
                    public void write(Path outputDirectory, String readme, String architecture, boolean force)
                            throws IOException {
                        writer.write(outputDirectory, readme, architecture, force);
                    }
                },
                new PromptStore() {
                    private final PromptPreviewWriter writer = new PromptPreviewWriter();

                    @Override
                    public void preflight(Path outputDirectory, boolean force) throws IOException {
                        writer.preflight(outputDirectory, force);
                    }

                    @Override
                    public void write(Path outputDirectory, String readme, String architecture, boolean force)
                            throws IOException {
                        writer.write(outputDirectory, readme, architecture, force);
                    }
                }
        );
    }

    DocumentationService(
            RepositoryReader repositoryReader,
            PromptBuilder promptBuilder,
            ProviderResolver providerResolver,
            DocumentStore documentStore,
            PromptStore promptStore
    ) {
        this.repositoryReader = Objects.requireNonNull(repositoryReader, "repositoryReader");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
        this.providerResolver = Objects.requireNonNull(providerResolver, "providerResolver");
        this.documentStore = Objects.requireNonNull(documentStore, "documentStore");
        this.promptStore = Objects.requireNonNull(promptStore, "promptStore");
    }

    public DocumentationResult execute(DocumentationRequest request) throws Exception {
        return execute(request, transmission -> false, ProgressListener.NONE);
    }

    public DocumentationResult execute(
            DocumentationRequest request,
            RemoteConsent remoteConsent,
            ProgressListener progressListener
    ) throws Exception {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(remoteConsent, "remoteConsent");
        Objects.requireNonNull(progressListener, "progressListener");

        RepoScanner.Options scanOptions = RepoScanner.Options.withAllowedExtensions(request.includeExtensions());
        RepoContext context = repositoryReader.scan(request.repository(), scanOptions);
        DocumentationResult.RepositoryAnalysis analysis = toAnalysis(context);
        progressListener.repositoryScanned(analysis);

        if (request.operation() == DocumentationRequest.Operation.ANALYZE) {
            return result(request.operation(), analysis, null, null, null, null, false);
        }
        requireEligibleFiles(context);

        if (request.persist()) {
            if (request.operation() == DocumentationRequest.Operation.PREVIEW_PROMPTS) {
                promptStore.preflight(request.outputDirectory(), request.force());
            } else {
                documentStore.preflight(request.outputDirectory(), request.force());
            }
        }

        String readmePrompt = promptBuilder.buildReadmePrompt(context);
        String architecturePrompt = promptBuilder.buildArchitecturePrompt(context);

        if (request.operation() == DocumentationRequest.Operation.PREVIEW_PROMPTS) {
            if (request.persist()) {
                promptStore.write(request.outputDirectory(), readmePrompt, architecturePrompt, request.force());
            }
            return result(request.operation(), analysis, readmePrompt, architecturePrompt,
                    null, null, request.persist());
        }

        LLMProvider provider = providerResolver.create(
                request.provider(), request.model(), request.allowRemote());
        if (provider.isRemote()) {
            if (!request.allowRemote()) {
                throw new IllegalArgumentException(
                        "Groq sends repository content to a remote service. Re-run with --allow-remote if you consent.");
            }
            RemoteTransmission transmission = new RemoteTransmission(
                    provider.describeDestination(), analysis.includedFiles().size(), analysis.totalCharacters());
            if (!remoteConsent.authorize(transmission)) {
                throw new RemoteConsentDeniedException();
            }
        }

        progressListener.generationStarted(Artifact.README);
        String readme = DocumentationValidator.redactAndValidate(
                provider.generateDocumentation(readmePrompt), "README.md");
        progressListener.generationStarted(Artifact.ARCHITECTURE);
        String architecture = DocumentationValidator.redactAndValidate(
                provider.generateDocumentation(architecturePrompt), "ARCHITECTURE.md");

        if (request.persist()) {
            documentStore.write(request.outputDirectory(), readme, architecture, request.force());
        }
        return result(request.operation(), analysis, null, null, readme, architecture, request.persist());
    }

    private static void requireEligibleFiles(RepoContext context) throws IOException {
        if (context.files().isEmpty()) {
            throw new IOException(
                    "no files matched the scan filters; nothing to document. Run with --dry-run to inspect the selection.");
        }
    }

    private static DocumentationResult.RepositoryAnalysis toAnalysis(RepoContext context) {
        return new DocumentationResult.RepositoryAnalysis(
                context.files().stream().map(file -> new DocumentationResult.SelectedFile(
                        file.relativePath().toString().replace('\\', '/'),
                        file.content().length(),
                        file.truncated()
                )).toList(),
                context.totalCharacters(),
                context.skippedFiles(),
                context.excludedFiles()
        );
    }

    private static DocumentationResult result(
            DocumentationRequest.Operation operation,
            DocumentationResult.RepositoryAnalysis analysis,
            String readmePrompt,
            String architecturePrompt,
            String readme,
            String architecture,
            boolean persisted
    ) {
        return new DocumentationResult(operation, analysis, readmePrompt, architecturePrompt,
                readme, architecture, persisted);
    }

    public enum Artifact {
        README,
        ARCHITECTURE
    }

    public record RemoteTransmission(String destination, int fileCount, int totalCharacters) {
        public RemoteTransmission {
            Objects.requireNonNull(destination, "destination");
        }
    }

    @FunctionalInterface
    public interface RemoteConsent {
        boolean authorize(RemoteTransmission transmission);
    }

    public interface ProgressListener {
        ProgressListener NONE = new ProgressListener() {
        };

        default void repositoryScanned(DocumentationResult.RepositoryAnalysis analysis) {
        }

        default void generationStarted(Artifact artifact) {
        }
    }

    public static final class RemoteConsentDeniedException extends Exception {
        public RemoteConsentDeniedException() {
            super("Remote transmission was not authorized.");
        }
    }

    @FunctionalInterface
    interface RepositoryReader {
        RepoContext scan(Path repository, RepoScanner.Options options) throws IOException;
    }

    @FunctionalInterface
    interface ProviderResolver {
        LLMProvider create(String provider, String model, boolean allowRemote);
    }

    interface DocumentStore {
        void preflight(Path outputDirectory, boolean force) throws IOException;

        void write(Path outputDirectory, String readme, String architecture, boolean force) throws IOException;
    }

    interface PromptStore {
        void preflight(Path outputDirectory, boolean force) throws IOException;

        void write(Path outputDirectory, String readmePrompt, String architecturePrompt, boolean force)
                throws IOException;
    }
}
