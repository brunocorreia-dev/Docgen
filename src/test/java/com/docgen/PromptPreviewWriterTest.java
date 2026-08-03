package com.docgen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PromptPreviewWriterTest {
    @TempDir
    Path tempDir;

    @Test
    void refusesOverwriteWithoutForceAndPreservesBothFiles() throws Exception {
        Path readme = tempDir.resolve(DocGenCLI.README_PROMPT_FILE);
        Path architecture = tempDir.resolve(DocGenCLI.ARCHITECTURE_PROMPT_FILE);
        Files.writeString(readme, "old-readme");
        Files.writeString(architecture, "old-architecture");

        assertThrows(FileAlreadyExistsException.class,
                () -> new PromptPreviewWriter().write(tempDir, "new-readme", "new-architecture", false));

        assertEquals("old-readme", Files.readString(readme));
        assertEquals("old-architecture", Files.readString(architecture));
    }

    @Test
    void forceReplacesBothPreviewsAndUsesOwnerOnlyModeOnPosix() throws Exception {
        Path readme = tempDir.resolve(DocGenCLI.README_PROMPT_FILE);
        Path architecture = tempDir.resolve(DocGenCLI.ARCHITECTURE_PROMPT_FILE);
        Files.writeString(readme, "old-readme");
        Files.writeString(architecture, "old-architecture");

        new PromptPreviewWriter().write(tempDir, "new-readme", "new-architecture", true);

        assertEquals("new-readme", Files.readString(readme));
        assertEquals("new-architecture", Files.readString(architecture));
        if (Files.getFileStore(tempDir).supportsFileAttributeView("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(readme)));
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(architecture)));
        }
        assertEquals(Set.of(DocGenCLI.README_PROMPT_FILE, DocGenCLI.ARCHITECTURE_PROMPT_FILE),
                entriesIn(tempDir));
    }

    @Test
    void validatesBothTargetsBeforeChangingEitherOne() throws Exception {
        Path readme = tempDir.resolve(DocGenCLI.README_PROMPT_FILE);
        Path architecture = tempDir.resolve(DocGenCLI.ARCHITECTURE_PROMPT_FILE);
        Files.writeString(readme, "old-readme");
        Files.createDirectory(architecture);

        assertThrows(IOException.class,
                () -> new PromptPreviewWriter().write(tempDir, "new-readme", "new-architecture", true));

        assertEquals("old-readme", Files.readString(readme));
        assertFalse(Files.isRegularFile(architecture));
        assertEquals(Set.of(DocGenCLI.README_PROMPT_FILE, DocGenCLI.ARCHITECTURE_PROMPT_FILE),
                entriesIn(tempDir));
    }

    @Test
    void refusesSymlinkTargetsWithoutChangingTheirDestination() throws Exception {
        Path outside = tempDir.resolve("outside.txt");
        Files.writeString(outside, "outside-content");
        Path readme = tempDir.resolve(DocGenCLI.README_PROMPT_FILE);
        createSymlinkOrSkip(readme, outside);

        assertThrows(IOException.class,
                () -> new PromptPreviewWriter().write(tempDir, "new-readme", "new-architecture", true));

        assertEquals("outside-content", Files.readString(outside));
        assertFalse(Files.exists(tempDir.resolve(DocGenCLI.ARCHITECTURE_PROMPT_FILE)));
    }

    @Test
    void failedSecondPublicationRestoresBothOriginals() throws Exception {
        Path readme = tempDir.resolve(DocGenCLI.README_PROMPT_FILE);
        Path architecture = tempDir.resolve(DocGenCLI.ARCHITECTURE_PROMPT_FILE);
        Files.writeString(readme, "old-readme");
        Files.writeString(architecture, "old-architecture");
        PromptPreviewWriter writer = new PromptPreviewWriter((event, target) -> {
            if (event == SecurePairWriter.Event.BEFORE_PUBLISH
                    && target.equals(Path.of(DocGenCLI.ARCHITECTURE_PROMPT_FILE))) {
                throw new IOException("simulated architecture publication failure");
            }
        });

        IOException failure = assertThrows(IOException.class,
                () -> writer.write(tempDir, "new-readme", "new-architecture", true));

        assertEquals("simulated architecture publication failure", failure.getMessage());
        assertEquals("old-readme", Files.readString(readme));
        assertEquals("old-architecture", Files.readString(architecture));
        assertEquals(Set.of(DocGenCLI.README_PROMPT_FILE, DocGenCLI.ARCHITECTURE_PROMPT_FILE),
                entriesIn(tempDir));
    }

    @Test
    void postPublicationFailureRollsBackBothPreviews() throws Exception {
        Path readme = tempDir.resolve(DocGenCLI.README_PROMPT_FILE);
        Path architecture = tempDir.resolve(DocGenCLI.ARCHITECTURE_PROMPT_FILE);
        Files.writeString(readme, "old-readme");
        Files.writeString(architecture, "old-architecture");
        PromptPreviewWriter writer = new PromptPreviewWriter((event, target) -> {
            if (event == SecurePairWriter.Event.AFTER_PUBLISH
                    && target.equals(Path.of(DocGenCLI.ARCHITECTURE_PROMPT_FILE))) {
                throw new IOException("simulated post-publication failure");
            }
        });

        assertThrows(IOException.class,
                () -> writer.write(tempDir, "new-readme", "new-architecture", true));

        assertEquals("old-readme", Files.readString(readme));
        assertEquals("old-architecture", Files.readString(architecture));
        assertEquals(Set.of(DocGenCLI.README_PROMPT_FILE, DocGenCLI.ARCHITECTURE_PROMPT_FILE),
                entriesIn(tempDir));
    }

    @Test
    void rejectsSymbolicLinkInOutputAncestor() throws Exception {
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Path link = tempDir.resolve("output-link");
        createSymlinkOrSkip(link, outside);

        assertThrows(IOException.class,
                () -> new PromptPreviewWriter().write(
                        link.resolve("nested"), "sensitive-readme", "sensitive-architecture", true));

        assertEquals(Set.of(), entriesIn(outside));
    }

    @Test
    void concurrentPreviewIsNeverOverwrittenAndRecoveryIsRetained() throws Exception {
        Path readme = tempDir.resolve(DocGenCLI.README_PROMPT_FILE);
        Path architecture = tempDir.resolve(DocGenCLI.ARCHITECTURE_PROMPT_FILE);
        Files.writeString(readme, "old-readme");
        Files.writeString(architecture, "old-architecture");
        PromptPreviewWriter writer = new PromptPreviewWriter((event, target) -> {
            if (event == SecurePairWriter.Event.BEFORE_PUBLISH
                    && target.equals(Path.of(DocGenCLI.ARCHITECTURE_PROMPT_FILE))) {
                Files.writeString(architecture, "third-party-prompt");
            }
        });

        IOException failure = assertThrows(IOException.class,
                () -> writer.write(tempDir, "new-readme", "new-architecture", true));

        assertEquals("old-readme", Files.readString(readme));
        assertEquals("third-party-prompt", Files.readString(architecture));
        assertTrue(entriesIn(tempDir).stream().anyMatch(name -> name.endsWith(".bak")));
        assertTrue(Set.of(failure.getSuppressed()).stream().anyMatch(suppressed ->
                "Prompt preview rollback was incomplete; recovery files may remain"
                        .equals(suppressed.getMessage())));
    }

    private void createSymlinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException failure) {
            assumeTrue(false, "symbolic links are unavailable: " + failure.getMessage());
        }
    }

    private Set<String> entriesIn(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            return entries
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toSet());
        }
    }
}
