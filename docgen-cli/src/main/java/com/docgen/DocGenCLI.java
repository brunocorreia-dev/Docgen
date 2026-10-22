package com.docgen;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.Console;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

@Command(name = "docgen", mixinStandardHelpOptions = true, version = "docgen 1.0.0",
        description = "Generate README.md and ARCHITECTURE.md from a repository snapshot.")
public final class DocGenCLI implements Callable<Integer> {
    static final String README_PROMPT_FILE = DocumentationArtifacts.README_PROMPT;
    static final String ARCHITECTURE_PROMPT_FILE = DocumentationArtifacts.ARCHITECTURE_PROMPT;

    private final DocumentationService documentationService;

    @Parameters(index = "0", defaultValue = ".", description = "Repository directory to scan. Defaults to the current directory.")
    private Path repository;

    @Option(names = {"-o", "--output"}, defaultValue = ".", description = "Output directory. Defaults to the current directory.")
    private Path output;

    @Option(names = "--provider", defaultValue = "groq", description = "LLM provider: groq or ollama. Default: ${DEFAULT-VALUE}.")
    private String provider;

    @Option(names = "--model", description = "Provider model. Defaults: Groq llama-3.3-70b-versatile, Ollama llama3.2.")
    private String model;

    @Option(names = "--allow-remote", description = "Required when using Groq because repository content is sent to a remote API.")
    private boolean allowRemote;

    @Option(names = "--force", description = "Overwrite existing documentation or prompt-preview files.")
    private boolean force;

    @Option(names = "--dry-run", description = "List the files that would be included or excluded, then exit without contacting any provider.")
    private boolean dryRun;

    @Option(names = "--preview-prompt", description = "Write the exact prompts to <output>/" + README_PROMPT_FILE + " and <output>/" + ARCHITECTURE_PROMPT_FILE + " without contacting any provider.")
    private boolean previewPrompt;

    @Option(names = "--include-ext", split = ",", paramLabel = "ext",
            description = "Comma-separated allowlist of file extensions to include (e.g. java,md,xml). Narrows the built-in supported set; sensitive-file and .docgenignore exclusions still apply.")
    private List<String> includeExtensions;

    @Option(names = {"-y", "--yes"}, description = "Skip the interactive confirmation shown before sending content to a remote provider.")
    private boolean assumeYes;

    public DocGenCLI() {
        this(new DocumentationService());
    }

    DocGenCLI(DocumentationService documentationService) {
        this.documentationService = documentationService;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new DocGenCLI()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        try {
            DocumentationRequest.Operation operation = dryRun
                    ? DocumentationRequest.Operation.ANALYZE
                    : previewPrompt
                    ? DocumentationRequest.Operation.PREVIEW_PROMPTS
                    : DocumentationRequest.Operation.GENERATE;
            DocumentationRequest request = new DocumentationRequest(
                    repository,
                    output,
                    operation,
                    provider,
                    model,
                    includeExtensions,
                    allowRemote,
                    !dryRun,
                    force
            );
            DocumentationResult result = documentationService.execute(
                    request,
                    this::confirmRemoteSend,
                    new CliProgressListener()
            );

            if (dryRun) {
                printDryRun(result.analysis());
            } else if (previewPrompt) {
                printPromptPreviewLocations();
            } else {
                System.out.println("Documentation written to: "
                        + SafeText.forTerminal(output.toAbsolutePath().normalize().toString()));
            }
            return 0;
        } catch (DocumentationService.RemoteConsentDeniedException denied) {
            System.err.println("Aborted: no repository content was sent.");
            return 1;
        } catch (Exception e) {
            System.err.println("Error: " + safeFailureMessage(e));
            return 1;
        }
    }

    static String safeFailureMessage(Throwable failure) {
        StringBuilder diagnostic = new StringBuilder();
        if (failure != null) {
            diagnostic.append(SafeText.forTerminal(failure.getMessage()));
        }
        if (failure != null) {
            Throwable[] suppressed = failure.getSuppressed();
            for (int index = 0; index < suppressed.length && index < 3; index++) {
                if (!diagnostic.isEmpty()) {
                    diagnostic.append("; ");
                }
                diagnostic.append(SafeText.forTerminal(suppressed[index].getMessage()));
            }
        }
        return SafeText.forTerminal(diagnostic.toString());
    }

    private void printDryRun(DocumentationResult.RepositoryAnalysis analysis) {
        System.out.println();
        System.out.println("Dry run: showing the selection only. No provider was contacted and no content leaves this machine.");
        System.out.println();
        System.out.println("Included files (content would be redacted, then sent to the selected provider):");
        if (analysis.includedFiles().isEmpty()) {
            System.out.println("  (none)");
        }
        for (DocumentationResult.SelectedFile file : analysis.includedFiles()) {
            System.out.println("  + " + SafeText.forTerminal(file.relativePath())
                    + " (" + file.characters() + " chars" + (file.truncated() ? ", truncated" : "") + ")");
        }
        if (!analysis.skippedFiles().isEmpty()) {
            System.out.println();
            System.out.println("Skipped by size limits (names are listed inside the prompt, content is not sent):");
            analysis.skippedFiles().forEach(item -> System.out.println("  - " + SafeText.forTerminal(item)));
        }
        if (!analysis.excludedFiles().isEmpty()) {
            System.out.println();
            System.out.println("Excluded (neither name nor content is sent):");
            analysis.excludedFiles().forEach(item -> System.out.println("  - " + SafeText.forTerminal(item)));
        }
        System.out.println();
        System.out.println("Total prompt content: " + analysis.totalCharacters() + " chars from "
                + analysis.includedFiles().size() + " files.");
    }

    private void printPromptPreviewLocations() {
        Path readmePreview = output.toAbsolutePath().normalize().resolve(README_PROMPT_FILE);
        Path architecturePreview = output.toAbsolutePath().normalize().resolve(ARCHITECTURE_PROMPT_FILE);
        System.out.println("Prompt previews written (no provider was contacted, nothing left this machine):");
        System.out.println("  " + SafeText.forTerminal(readmePreview.toString()));
        System.out.println("  " + SafeText.forTerminal(architecturePreview.toString()));
        System.out.println("Review them to see exactly what a real run would send.");
    }

    /**
     * Prints what is about to leave the machine and, when running on an
     * interactive console without --yes, requires an explicit confirmation.
     * Non-interactive runs require --yes as the explicit second consent signal.
     */
    private boolean confirmRemoteSend(DocumentationService.RemoteTransmission transmission) {
        System.err.println();
        System.err.println("WARNING: about to send data to "
                + SafeText.forTerminal(transmission.destination()) + ".");
        System.err.println("Data that leaves this machine: redacted content of " + transmission.fileCount()
                + " files (" + transmission.totalCharacters()
                + " chars), all included file paths, and the names of size-skipped files.");
        System.err.println("A normal run sends two prompts (README and architecture); Groq may make up to three attempts for each prompt after rate-limit or server errors.");
        System.err.println("Secret redaction is best-effort. Use --dry-run or --preview-prompt to inspect first.");
        if (assumeYes) {
            return true;
        }
        Console console = System.console();
        if (console == null) {
            System.err.println("Aborted: no interactive console is available. Re-run with both --allow-remote and --yes to consent non-interactively.");
            return false;
        }
        String answer = console.readLine("Continue? [y/N] ");
        if (answer == null) {
            return false;
        }
        String normalized = answer.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("y") || normalized.equals("yes");
    }

    private final class CliProgressListener implements DocumentationService.ProgressListener {
        @Override
        public void repositoryScanned(DocumentationResult.RepositoryAnalysis analysis) {
            System.out.println("Scanning: "
                    + SafeText.forTerminal(repository.toAbsolutePath().normalize().toString()));
            System.out.println("Files included: " + analysis.includedFiles().size());
        }

        @Override
        public void generationStarted(DocumentationService.Artifact artifact) {
            if (artifact == DocumentationService.Artifact.README) {
                System.out.println("Generating README.md...");
            } else {
                System.out.println("Generating ARCHITECTURE.md...");
            }
        }
    }
}
