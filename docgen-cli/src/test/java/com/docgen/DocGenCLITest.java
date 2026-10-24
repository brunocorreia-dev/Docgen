package com.docgen;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocGenCLITest {
    @TempDir
    Path repo;

    @TempDir
    Path out;

    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void captureStreams() {
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @Test
    void dryRunListsSelectionAndContactsNoProvider() throws Exception {
        Files.writeString(repo.resolve("App.java"), "class App {}");
        Files.writeString(repo.resolve("notes.md"), "notes");
        Files.writeString(repo.resolve(".env"), "SECRET=1");
        Files.writeString(repo.resolve(".docgenignore"), "notes.md\n");

        int exitCode = new CommandLine(new DocGenCLI()).execute("--dry-run", repo.toString());

        assertEquals(0, exitCode, () -> "stderr: " + stderr.toString(StandardCharsets.UTF_8));
        String output = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("No provider was contacted"));
        assertTrue(output.contains("+ App.java"));
        assertTrue(output.contains("notes.md (excluded by .docgenignore)"));
        assertTrue(output.contains(".env (sensitive file)"));
    }

    @Test
    void previewPromptWritesPromptsLocallyWithoutAnyProvider() throws Exception {
        Files.writeString(repo.resolve("App.java"), "class App {}");

        int exitCode = new CommandLine(new DocGenCLI())
                .execute("--preview-prompt", "-o", out.toString(), repo.toString());

        assertEquals(0, exitCode, () -> "stderr: " + stderr.toString(StandardCharsets.UTF_8));
        String readmePrompt = Files.readString(out.resolve(DocGenCLI.README_PROMPT_FILE));
        String architecturePrompt = Files.readString(out.resolve(DocGenCLI.ARCHITECTURE_PROMPT_FILE));
        assertTrue(readmePrompt.contains("<repository_snapshot>"));
        assertTrue(readmePrompt.contains("App.java"));
        assertTrue(architecturePrompt.contains("ARCHITECTURE.md"));
        assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("no provider was contacted"));
    }

    @Test
    void generatesReadmeAndArchitectureWithTheLocalProvider() throws Exception {
        Files.writeString(repo.resolve("App.java"), "class App {}");
        List<String> models = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        DocumentationService service = new DocumentationService(model -> {
            models.add(model);
            return prompt -> calls.getAndIncrement() == 0 ? "# App\n\nReadme." : "# Architecture\n\nLayers.";
        });

        int exitCode = new CommandLine(new DocGenCLI(service))
                .execute("--model", "qwen2.5-coder:7b", "-o", out.toString(), repo.toString());

        assertEquals(0, exitCode, () -> "stderr: " + stderr.toString(StandardCharsets.UTF_8));
        assertEquals("# App\n\nReadme.\n", Files.readString(out.resolve("README.md")));
        assertEquals("# Architecture\n\nLayers.\n", Files.readString(out.resolve("ARCHITECTURE.md")));
        assertEquals(List.of("qwen2.5-coder:7b"), models);
        assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("Documentation written to:"));
    }

    @Test
    void aPromptTooLargeForTheModelExplainsHowToNarrowTheInput() throws Exception {
        Files.writeString(repo.resolve("App.java"), "class App {}");
        DocumentationService service = new DocumentationService(model -> prompt -> {
            throw new PromptTooLargeException("qwen2.5-coder:7b", 40_000, 32_768);
        });

        int exitCode = new CommandLine(new DocGenCLI(service)).execute("-o", out.toString(), repo.toString());

        assertEquals(1, exitCode);
        String error = stderr.toString(StandardCharsets.UTF_8);
        assertTrue(error.contains("accepts at most 32768"), error);
        assertTrue(error.contains("--include-ext"), error);
        assertFalse(Files.exists(out.resolve("README.md")));
    }

    @Test
    void rejectsEveryProviderOtherThanTheLocalOllama() throws Exception {
        Files.writeString(repo.resolve("App.java"), "class App {}");

        int exitCode = new CommandLine(new DocGenCLI())
                .execute("--provider", "remote-cloud", "-o", out.toString(), repo.toString());

        assertEquals(1, exitCode);
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("runs only on the local Ollama service"));
        assertFalse(Files.exists(out.resolve("README.md")));
        assertFalse(Files.exists(out.resolve("ARCHITECTURE.md")));
    }

    @Test
    void rejectsAnOllamaPortOutsideTheTcpRange() throws Exception {
        Files.writeString(repo.resolve("App.java"), "class App {}");

        int exitCode = new CommandLine(new DocGenCLI())
                .execute("--ollama-port", "70000", "-o", out.toString(), repo.toString());

        assertEquals(1, exitCode);
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("between 1 and 65535"));
        assertFalse(Files.exists(out.resolve("README.md")));
    }

    @Test
    void remoteTransmissionOptionsNoLongerExist() throws Exception {
        Files.writeString(repo.resolve("App.java"), "class App {}");

        for (String option : List.of("--allow-remote", "--yes", "-y")) {
            stderr.reset();
            int exitCode = new CommandLine(new DocGenCLI()).execute(option, "--dry-run", repo.toString());

            assertEquals(2, exitCode, option);
            assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("Unknown option"), option);
        }
    }

    @Test
    void previewPromptRefusesOverwriteWithoutForce() throws Exception {
        Files.writeString(repo.resolve("App.java"), "class App {}");
        Path existing = out.resolve(DocGenCLI.README_PROMPT_FILE);
        Files.writeString(existing, "keep-me");

        int exitCode = new CommandLine(new DocGenCLI())
                .execute("--preview-prompt", "-o", out.toString(), repo.toString());

        assertEquals(1, exitCode);
        assertEquals("keep-me", Files.readString(existing));
        assertFalse(Files.exists(out.resolve(DocGenCLI.ARCHITECTURE_PROMPT_FILE)));
    }

    @Test
    void refusesToRunWhenSelectionIsEmpty() throws Exception {
        Files.writeString(repo.resolve("binary.bin"), "data");

        int exitCode = new CommandLine(new DocGenCLI()).execute(repo.toString());

        assertEquals(1, exitCode);
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("no files matched"));
    }

    @Test
    void missingOutputFailsBeforeProviderConstruction() throws Exception {
        Files.writeString(repo.resolve("App.java"), "class App {}");
        Path missingOutput = out.resolve("missing");

        int exitCode = new CommandLine(new DocGenCLI()).execute(
                "--provider", "provider-that-does-not-exist",
                "-o", missingOutput.toString(),
                repo.toString()
        );

        assertEquals(1, exitCode);
        String error = stderr.toString(StandardCharsets.UTF_8);
        assertTrue(error.contains("Output directory must already exist"), error);
        assertFalse(error.contains("provider-that-does-not-exist"), error);
        assertFalse(Files.exists(missingOutput));
    }

    @Test
    void existingOutputWithoutForceFailsBeforeProviderConstruction() throws Exception {
        Files.writeString(repo.resolve("App.java"), "class App {}");
        Files.writeString(out.resolve("README.md"), "keep-me");

        int exitCode = new CommandLine(new DocGenCLI()).execute(
                "--provider", "provider-that-does-not-exist",
                "-o", out.toString(),
                repo.toString()
        );

        assertEquals(1, exitCode);
        String error = stderr.toString(StandardCharsets.UTF_8);
        assertTrue(error.contains("Use --force to overwrite"), error);
        assertFalse(error.contains("provider-that-does-not-exist"), error);
        assertEquals("keep-me", Files.readString(out.resolve("README.md")));
        assertFalse(Files.exists(out.resolve("ARCHITECTURE.md")));
    }

    @Test
    void dryRunDoesNotRequireOrCreateOutputDirectory() throws Exception {
        Files.writeString(repo.resolve("App.java"), "class App {}");
        Path missingOutput = out.resolve("missing-dry-run");

        int exitCode = new CommandLine(new DocGenCLI()).execute(
                "--dry-run", "-o", missingOutput.toString(), repo.toString());

        assertEquals(0, exitCode, () -> stderr.toString(StandardCharsets.UTF_8));
        assertFalse(Files.exists(missingOutput));
        assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("No provider was contacted"));
    }
}
