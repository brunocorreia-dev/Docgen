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
    void remoteProviderStillRequiresAllowRemote() throws Exception {
        Files.writeString(repo.resolve("App.java"), "class App {}");

        int exitCode = new CommandLine(new DocGenCLI())
                .execute("-o", out.toString(), repo.toString());

        assertEquals(1, exitCode);
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("--allow-remote"));
        assertFalse(Files.exists(repo.resolve("ARCHITECTURE.md")), "nothing must be generated without consent");
    }

    @Test
    void yesNeverReplacesTheAllowRemoteBoundary() throws Exception {
        Files.writeString(repo.resolve("App.java"), "class App {}");

        int exitCode = new CommandLine(new DocGenCLI())
                .execute("--yes", "-o", out.toString(), repo.toString());

        assertEquals(1, exitCode);
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("--allow-remote"));
        assertFalse(Files.exists(repo.resolve("README.md")));
    }

    @Test
    void nonInteractiveRemoteRunRequiresExplicitYes() throws Exception {
        Files.writeString(repo.resolve("App.java"), "class App {}");

        int exitCode = new CommandLine(new DocGenCLI())
                .execute("--allow-remote", "-o", out.toString(), repo.toString());

        assertEquals(1, exitCode);
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("--yes"));
        assertFalse(Files.exists(repo.resolve("README.md")), "no provider may be contacted without explicit non-interactive consent");
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

        int exitCode = new CommandLine(new DocGenCLI()).execute("--allow-remote", repo.toString());

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
