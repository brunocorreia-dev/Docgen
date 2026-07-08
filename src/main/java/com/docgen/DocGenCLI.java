package com.docgen;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.Console;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

@Command(name = "docgen", mixinStandardHelpOptions = true, version = "docgen 1.0.0",
        description = "Generate README.md and ARCHITECTURE.md from a repository snapshot.")
public final class DocGenCLI implements Callable<Integer> {
    static final String README_PROMPT_FILE = "docgen-readme.prompt.txt";
    static final String ARCHITECTURE_PROMPT_FILE = "docgen-architecture.prompt.txt";

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

    @Option(names = "--force", description = "Overwrite README.md and ARCHITECTURE.md if they already exist.")
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

    public static void main(String[] args) {
        int exitCode = new CommandLine(new DocGenCLI()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        try {
            RepoScanner scanner = new RepoScanner();
            RepoScanner.Options options = RepoScanner.Options.withAllowedExtensions(includeExtensions);
            RepoContext context = scanner.scan(repository, options);

            System.out.println("Scanning: " + context.root());
            System.out.println("Files included: " + context.files().size());

            if (dryRun) {
                printDryRun(context);
                return 0;
            }

            if (context.files().isEmpty()) {
                System.err.println("Error: no files matched the scan filters; nothing to document. Run with --dry-run to inspect the selection.");
                return 1;
            }

            PromptBuilder promptBuilder = new PromptBuilder();
            String readmePrompt = promptBuilder.buildReadmePrompt(context);
            String architecturePrompt = promptBuilder.buildArchitecturePrompt(context);

            if (previewPrompt) {
                writePromptPreviews(readmePrompt, architecturePrompt);
                return 0;
            }

            LLMProvider llmProvider = LLMProviderFactory.create(provider, model, allowRemote);
            if (llmProvider.isRemote() && !confirmRemoteSend(context, llmProvider)) {
                System.err.println("Aborted: no repository content was sent.");
                return 1;
            }

            System.out.println("Generating README.md...");
            String readme = llmProvider.generateDocumentation(readmePrompt);
            System.out.println("Generating ARCHITECTURE.md...");
            String architecture = llmProvider.generateDocumentation(architecturePrompt);

            new OutputWriter().write(output, readme, architecture, force);
            System.out.println("Documentation written to: " + output.toAbsolutePath().normalize());
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private void printDryRun(RepoContext context) {
        System.out.println();
        System.out.println("Dry run: showing the selection only. No provider was contacted and no content leaves this machine.");
        System.out.println();
        System.out.println("Included files (content would be redacted, then sent to the selected provider):");
        if (context.files().isEmpty()) {
            System.out.println("  (none)");
        }
        for (RepoContext.ScannedFile file : context.files()) {
            System.out.println("  + " + file.relativePath().toString().replace('\\', '/')
                    + " (" + file.content().length() + " chars" + (file.truncated() ? ", truncated" : "") + ")");
        }
        if (!context.skippedFiles().isEmpty()) {
            System.out.println();
            System.out.println("Skipped by size limits (names are listed inside the prompt, content is not sent):");
            context.skippedFiles().forEach(item -> System.out.println("  - " + item));
        }
        if (!context.excludedFiles().isEmpty()) {
            System.out.println();
            System.out.println("Excluded (neither name nor content is sent):");
            context.excludedFiles().forEach(item -> System.out.println("  - " + item));
        }
        System.out.println();
        System.out.println("Total prompt content: " + context.totalCharacters() + " chars from " + context.files().size() + " files.");
    }

    private void writePromptPreviews(String readmePrompt, String architecturePrompt) throws Exception {
        Files.createDirectories(output);
        Path readmePreview = output.resolve(README_PROMPT_FILE);
        Path architecturePreview = output.resolve(ARCHITECTURE_PROMPT_FILE);
        Files.writeString(readmePreview, readmePrompt);
        Files.writeString(architecturePreview, architecturePrompt);
        System.out.println("Prompt previews written (no provider was contacted, nothing left this machine):");
        System.out.println("  " + readmePreview.toAbsolutePath().normalize());
        System.out.println("  " + architecturePreview.toAbsolutePath().normalize());
        System.out.println("Review them to see exactly what a real run would send.");
    }

    /**
     * Prints what is about to leave the machine and, when running on an
     * interactive console without --yes, requires an explicit confirmation.
     * Non-interactive runs proceed on --allow-remote alone (already enforced by
     * the provider factory) but still get the warning on stderr.
     */
    private boolean confirmRemoteSend(RepoContext context, LLMProvider llmProvider) {
        System.err.println();
        System.err.println("WARNING: about to send data to " + llmProvider.describeDestination() + ".");
        System.err.println("Data that leaves this machine: redacted content of " + context.files().size()
                + " files (" + context.totalCharacters() + " chars), all included file paths, and the names of size-skipped files.");
        System.err.println("Secret redaction is best-effort. Use --dry-run or --preview-prompt to inspect first.");
        if (assumeYes) {
            return true;
        }
        Console console = System.console();
        if (console == null) {
            return true;
        }
        String answer = console.readLine("Continue? [y/N] ");
        if (answer == null) {
            return false;
        }
        String normalized = answer.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("y") || normalized.equals("yes");
    }
}
