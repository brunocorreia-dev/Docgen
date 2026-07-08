package com.docgen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepoScannerTest {
    @TempDir
    Path tempDir;

    @Test
    void scansDeterministicallyRedactsSecretsAndSkipsSensitiveFiles() throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java/app"));
        Files.writeString(tempDir.resolve("src/main/java/app/App.java"), "class App { String token = \"" + "ghp_" + "abcdefghijklmnopqrstuvwxyz123456" + "\"; }");
        Files.writeString(tempDir.resolve(".env"), "PASSWORD=do-not-read");
        Files.writeString(tempDir.resolve("README.md"), "# Existing");

        RepoContext context = new RepoScanner().scan(tempDir);

        assertEquals(2, context.files().size());
        String joined = context.files().toString();
        assertFalse(joined.contains("ghp_" + "abcdefghijklmnopqrstuvwxyz123456"));
        assertFalse(joined.contains("do-not-read"));
        assertTrue(joined.contains("README.md"));
        assertEquals(context.files().stream().map(f -> f.relativePath().toString()).sorted().toList(),
                context.files().stream().map(f -> f.relativePath().toString()).toList());
        assertTrue(context.excludedFiles().stream().anyMatch(item -> item.startsWith(".env (sensitive file)")));
    }

    @Test
    void docgenignoreExcludesFilesWithoutLeakingThemIntoThePrompt() throws Exception {
        Files.createDirectories(tempDir.resolve("docs"));
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve(".docgenignore"), "# internal material\ndocs/\n*.sql\n");
        Files.writeString(tempDir.resolve("docs/internal.md"), "internal notes");
        Files.writeString(tempDir.resolve("schema.sql"), "CREATE TABLE users;");
        Files.writeString(tempDir.resolve("src/App.java"), "class App {}");

        RepoContext context = new RepoScanner().scan(tempDir);

        assertEquals(List.of("src/App.java"),
                context.files().stream().map(f -> f.relativePath().toString().replace('\\', '/')).toList());
        assertTrue(context.excludedFiles().contains("docs/internal.md (excluded by .docgenignore)"));
        assertTrue(context.excludedFiles().contains("schema.sql (excluded by .docgenignore)"));
        // Ignored entries must stay out of everything a prompt is built from.
        assertTrue(context.skippedFiles().isEmpty());
        assertFalse(context.fileTree().contains("internal.md"));
        assertFalse(context.fileTree().contains("schema.sql"));
    }

    @Test
    void docgenignoreMatchesBareNamesAtAnyDepth() throws Exception {
        Files.createDirectories(tempDir.resolve("a/b"));
        Files.writeString(tempDir.resolve(".docgenignore"), "notes.md\n");
        Files.writeString(tempDir.resolve("a/b/notes.md"), "deep");
        Files.writeString(tempDir.resolve("notes.md"), "shallow");
        Files.writeString(tempDir.resolve("keep.md"), "keep");

        RepoContext context = new RepoScanner().scan(tempDir);

        assertEquals(List.of("keep.md"),
                context.files().stream().map(f -> f.relativePath().toString().replace('\\', '/')).toList());
    }

    @Test
    void docgenignoreRejectsNegationPatterns() throws Exception {
        Files.writeString(tempDir.resolve(".docgenignore"), "!keep-this.md\n");
        Files.writeString(tempDir.resolve("keep-this.md"), "content");

        IOException error = assertThrows(IOException.class, () -> new RepoScanner().scan(tempDir));
        assertTrue(error.getMessage().contains("negation"));
    }

    @Test
    void extensionAllowlistNarrowsSelectionButCannotReincludeSensitiveFiles() throws Exception {
        Files.writeString(tempDir.resolve("App.java"), "class App {}");
        Files.writeString(tempDir.resolve("notes.md"), "notes");
        Files.writeString(tempDir.resolve("server.pem"), "-----BEGIN PRIVATE KEY-----x-----END PRIVATE KEY-----");
        Files.writeString(tempDir.resolve(".env"), "SECRET=1");

        RepoScanner.Options options = RepoScanner.Options.withAllowedExtensions(List.of("java", ".PEM", "env"));
        RepoContext context = new RepoScanner().scan(tempDir, options);

        assertEquals(List.of("App.java"),
                context.files().stream().map(f -> f.relativePath().toString().replace('\\', '/')).toList());
        assertTrue(context.excludedFiles().contains("notes.md (not in --include-ext allowlist)"));
        assertTrue(context.excludedFiles().contains("server.pem (sensitive file)"));
        assertTrue(context.excludedFiles().contains(".env (sensitive file)"));
    }
}
