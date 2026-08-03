package com.docgen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OutputWriterTest {
    @TempDir
    Path tempDir;

    @Test
    void refusesOverwriteWithoutForce() throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "existing");

        assertThrows(FileAlreadyExistsException.class,
                () -> new OutputWriter().write(tempDir, "new", "arch", false));

        assertEquals("existing", Files.readString(tempDir.resolve("README.md")));
        assertEquals(Set.of("README.md"), entriesIn(tempDir));
    }

    @Test
    void preflightIsReadOnlyAndWriteRevalidatesTheTargets() throws Exception {
        OutputWriter writer = new OutputWriter();

        writer.preflight(tempDir, false);
        assertEquals(Set.of(), entriesIn(tempDir));
        Files.writeString(tempDir.resolve("README.md"), "appeared-after-preflight");

        assertThrows(FileAlreadyExistsException.class,
                () -> writer.write(tempDir, "new", "architecture", false));
        assertEquals("appeared-after-preflight", Files.readString(tempDir.resolve("README.md")));
        assertFalse(Files.exists(tempDir.resolve("ARCHITECTURE.md")));
    }

    @Test
    void overwritesWithForceAndRemovesTransactionFiles() throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "existing");
        Files.writeString(tempDir.resolve("ARCHITECTURE.md"), "existing");

        new OutputWriter().write(tempDir, "new", "arch", true);

        assertEquals("new", Files.readString(tempDir.resolve("README.md")));
        assertEquals("arch", Files.readString(tempDir.resolve("ARCHITECTURE.md")));
        assertEquals(Set.of("README.md", "ARCHITECTURE.md"), entriesIn(tempDir));
        if (Files.getFileStore(tempDir).supportsFileAttributeView("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(tempDir.resolve("README.md"))));
            assertEquals("rw-------", PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(tempDir.resolve("ARCHITECTURE.md"))));
        }
    }

    @Test
    void restoresBothExistingFilesWhenSecondPublicationFails() throws Exception {
        Path readme = tempDir.resolve("README.md");
        Path architecture = tempDir.resolve("ARCHITECTURE.md");
        Files.writeString(readme, "old-readme");
        Files.writeString(architecture, "old-architecture");
        OutputWriter writer = new OutputWriter((event, target) -> {
            if (event == SecurePairWriter.Event.BEFORE_PUBLISH
                    && target.equals(Path.of("ARCHITECTURE.md"))) {
                throw new IOException("simulated second publication failure");
            }
        });

        IOException failure = assertThrows(IOException.class,
                () -> writer.write(tempDir, "new-readme", "new-architecture", true));

        assertEquals("simulated second publication failure", failure.getMessage());
        assertEquals("old-readme", Files.readString(readme));
        assertEquals("old-architecture", Files.readString(architecture));
        assertEquals(Set.of("README.md", "ARCHITECTURE.md"), entriesIn(tempDir));
    }

    @Test
    void rollbackPreservesOriginalPosixModes() throws Exception {
        assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView("posix"));
        Path readme = tempDir.resolve("README.md");
        Path architecture = tempDir.resolve("ARCHITECTURE.md");
        Files.writeString(readme, "old-readme");
        Files.writeString(architecture, "old-architecture");
        Files.setPosixFilePermissions(readme, PosixFilePermissions.fromString("rw-r--r--"));
        Files.setPosixFilePermissions(architecture, PosixFilePermissions.fromString("rw-r-----"));
        OutputWriter writer = new OutputWriter((event, target) -> {
            if (event == SecurePairWriter.Event.BEFORE_PUBLISH
                    && target.equals(Path.of("ARCHITECTURE.md"))) {
                throw new IOException("simulated failure");
            }
        });

        assertThrows(IOException.class,
                () -> writer.write(tempDir, "new-readme", "new-architecture", true));

        assertEquals("rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(readme)));
        assertEquals("rw-r-----", PosixFilePermissions.toString(
                Files.getPosixFilePermissions(architecture)));
    }

    @Test
    void postPublicationFailureRollsBackBothTargets() throws Exception {
        Path readme = tempDir.resolve("README.md");
        Path architecture = tempDir.resolve("ARCHITECTURE.md");
        Files.writeString(readme, "old-readme");
        Files.writeString(architecture, "old-architecture");
        AtomicInteger publications = new AtomicInteger();
        OutputWriter writer = new OutputWriter((event, target) -> {
            if (event == SecurePairWriter.Event.AFTER_PUBLISH
                    && publications.incrementAndGet() == 2) {
                throw new IOException("simulated post-publication failure");
            }
        });

        assertThrows(IOException.class,
                () -> writer.write(tempDir, "new-readme", "new-architecture", true));

        assertEquals("old-readme", Files.readString(readme));
        assertEquals("old-architecture", Files.readString(architecture));
        assertEquals(Set.of("README.md", "ARCHITECTURE.md"), entriesIn(tempDir));
    }

    @Test
    void preservesConcurrentTargetAndOriginalRecoveryCopy() throws Exception {
        Path readme = tempDir.resolve("README.md");
        Path architecture = tempDir.resolve("ARCHITECTURE.md");
        Files.writeString(readme, "old-readme");
        Files.writeString(architecture, "old-architecture");
        OutputWriter writer = new OutputWriter((event, target) -> {
            if (event == SecurePairWriter.Event.BEFORE_PUBLISH
                    && target.equals(Path.of("ARCHITECTURE.md"))) {
                Files.writeString(architecture, "third-party-content");
            }
        });

        IOException failure = assertThrows(IOException.class,
                () -> writer.write(tempDir, "new-readme", "new-architecture", true));

        assertEquals("old-readme", Files.readString(readme));
        assertEquals("third-party-content", Files.readString(architecture));
        assertTrue(entriesIn(tempDir).stream().anyMatch(name -> name.endsWith(".bak")));
        assertTrue(Set.of(failure.getSuppressed()).stream().anyMatch(suppressed ->
                "Documentation rollback was incomplete; recovery files may remain"
                        .equals(suppressed.getMessage())));
        assertTrue(Set.of(failure.getSuppressed()).stream().noneMatch(suppressed ->
                suppressed.getMessage().contains(tempDir.toString())));
    }

    @Test
    void quarantineRaceRestoresTheActuallyMovedThirdPartyEntry() throws Exception {
        Path readme = tempDir.resolve("README.md");
        Path architecture = tempDir.resolve("ARCHITECTURE.md");
        Files.writeString(readme, "old-readme");
        Files.writeString(architecture, "old-architecture");
        OutputWriter writer = new OutputWriter((event, target) -> {
            if (event == SecurePairWriter.Event.BEFORE_QUARANTINE_MOVE
                    && target.equals(Path.of("README.md"))) {
                Files.delete(readme);
                Files.writeString(readme, "third-party-content");
            }
        });

        IOException failure = assertThrows(IOException.class,
                () -> writer.write(tempDir, "new-readme", "new-architecture", true));

        assertEquals("Output target changed while it was being quarantined", failure.getMessage());
        assertEquals("third-party-content", Files.readString(readme));
        assertEquals("old-architecture", Files.readString(architecture));
        assertTrue(entriesIn(tempDir).stream().anyMatch(name -> name.endsWith(".bak")));
        assertTrue(Set.of(failure.getSuppressed()).stream().anyMatch(suppressed ->
                "A displaced entry was restored; its recovery quarantine was retained"
                        .equals(suppressed.getMessage())));
    }

    @Test
    void rollbackNeverDeletesPublishedTargetReplacedByAnotherWriter() throws Exception {
        Path readme = tempDir.resolve("README.md");
        Path architecture = tempDir.resolve("ARCHITECTURE.md");
        Files.writeString(readme, "old-readme");
        Files.writeString(architecture, "old-architecture");
        OutputWriter writer = new OutputWriter((event, target) -> {
            if (event == SecurePairWriter.Event.AFTER_PUBLISH
                    && target.equals(Path.of("ARCHITECTURE.md"))) {
                Files.delete(readme);
                Files.writeString(readme, "third-party-content");
                throw new IOException("simulated failure after replacement");
            }
        });

        IOException failure = assertThrows(IOException.class,
                () -> writer.write(tempDir, "new-readme", "new-architecture", true));

        assertEquals("third-party-content", Files.readString(readme));
        assertEquals("old-architecture", Files.readString(architecture));
        assertTrue(entriesIn(tempDir).stream().anyMatch(name -> name.endsWith(".bak")));
        assertTrue(Set.of(failure.getSuppressed()).stream().anyMatch(suppressed ->
                "Documentation rollback was incomplete; recovery files may remain"
                        .equals(suppressed.getMessage())));
    }

    @Test
    void targetSwapBetweenCreateAndIdentityCaptureCannotCommitOrDeleteThirdParty() throws Exception {
        Path readme = tempDir.resolve("README.md");
        Path architecture = tempDir.resolve("ARCHITECTURE.md");
        Files.writeString(readme, "old-readme");
        Files.writeString(architecture, "old-architecture");
        OutputWriter writer = new OutputWriter((event, target) -> {
            if (event == SecurePairWriter.Event.AFTER_PUBLICATION_CREATE
                    && target.equals(Path.of("README.md"))) {
                Files.delete(readme);
                Files.writeString(readme, "third-party-content");
            }
        });

        IOException failure = assertThrows(IOException.class,
                () -> writer.write(tempDir, "new-readme", "new-architecture", true));

        assertEquals("third-party-content", Files.readString(readme));
        assertEquals("old-architecture", Files.readString(architecture));
        assertTrue(entriesIn(tempDir).stream().anyMatch(name -> name.endsWith(".bak")));
        assertTrue(Set.of(failure.getSuppressed()).stream().anyMatch(suppressed ->
                "Documentation rollback was incomplete; recovery files may remain"
                        .equals(suppressed.getMessage())));
    }

    @Test
    void stagingSwapBetweenCreateAndIdentityCaptureIsDetectedByNonceDigest() throws Exception {
        OutputWriter writer = new OutputWriter((event, target) -> {
            if (event == SecurePairWriter.Event.AFTER_STAGE_CREATE) {
                Path staged = tempDir.resolve(target);
                Files.delete(staged);
                Files.writeString(staged, "third-party-staging-content");
            }
        });

        IOException failure = assertThrows(IOException.class,
                () -> writer.write(tempDir, "new-readme", "new-architecture", false));

        assertTrue(failure.getMessage().contains("Staging file changed"));
        assertFalse(Files.exists(tempDir.resolve("README.md")));
        assertFalse(Files.exists(tempDir.resolve("ARCHITECTURE.md")));
        assertTrue(entriesIn(tempDir).stream().anyMatch(name -> name.endsWith(".tmp")));
        try (var entries = Files.list(tempDir)) {
            Path attackerEntry = entries.filter(path -> path.getFileName().toString().endsWith(".tmp"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("third-party-staging-content", Files.readString(attackerEntry));
        }
    }

    @Test
    void validatesBothTargetsBeforeCreatingTransactionFiles() throws Exception {
        Path readme = tempDir.resolve("README.md");
        Path architecture = tempDir.resolve("ARCHITECTURE.md");
        Files.writeString(readme, "old-readme");
        Files.createDirectory(architecture);

        IOException failure = assertThrows(IOException.class,
                () -> new OutputWriter().write(tempDir, "new-readme", "new-architecture", true));

        assertTrue(failure.getMessage().contains("non-regular output target"));
        assertEquals("old-readme", Files.readString(readme));
        assertTrue(Files.isDirectory(architecture));
        assertEquals(Set.of("README.md", "ARCHITECTURE.md"), entriesIn(tempDir));
    }

    @Test
    void rejectsSymbolicLinkInAnAncestorWithoutWritingOutside() throws Exception {
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Path link = tempDir.resolve("linked-parent");
        createSymlinkOrSkip(link, outside);

        assertThrows(IOException.class,
                () -> new OutputWriter().write(link.resolve("nested"), "secret", "architecture", true));

        assertEquals(Set.of(), entriesIn(outside));
    }

    @Test
    void detectsDirectoryExchangeBeforeWritingSensitiveBytes() throws Exception {
        Path parent = Files.createDirectory(tempDir.resolve("parent"));
        Path output = Files.createDirectory(parent.resolve("output"));
        Path detached = parent.resolve("detached");
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        OutputWriter writer = new OutputWriter((event, target) -> {
            if (event == SecurePairWriter.Event.DIRECTORY_OPENED) {
                Files.move(output, detached);
                createSymlinkOrSkip(output, outside);
            }
        });

        assertThrows(IOException.class,
                () -> writer.write(output, "sensitive-readme", "sensitive-architecture", true));

        assertEquals(Set.of(), entriesIn(detached));
        assertEquals(Set.of(), entriesIn(outside));
    }

    @Test
    void directoryExchangeAfterPublicationCannotRedirectTheSecondWrite() throws Exception {
        Path parent = Files.createDirectory(tempDir.resolve("swap-parent"));
        Path output = Files.createDirectory(parent.resolve("output"));
        Path detached = parent.resolve("detached");
        Path outside = Files.createDirectory(tempDir.resolve("swap-outside"));
        Path symlinkProbe = parent.resolve("symlink-probe");
        createSymlinkOrSkip(symlinkProbe, outside);
        Files.delete(symlinkProbe);
        Files.writeString(output.resolve("README.md"), "old-readme");
        Files.writeString(output.resolve("ARCHITECTURE.md"), "old-architecture");
        OutputWriter writer = new OutputWriter((event, target) -> {
            if (event == SecurePairWriter.Event.AFTER_PUBLISH
                    && target.equals(Path.of("README.md"))) {
                Files.move(output, detached);
                Files.createSymbolicLink(output, outside);
            }
        });

        assertThrows(IOException.class,
                () -> writer.write(output, "sensitive-readme", "sensitive-architecture", true));

        assertEquals(Set.of(), entriesIn(outside));
        assertEquals("old-readme", Files.readString(detached.resolve("README.md")));
        assertEquals("old-architecture", Files.readString(detached.resolve("ARCHITECTURE.md")));
        assertEquals(Set.of("README.md", "ARCHITECTURE.md"), entriesIn(detached));
    }

    @Test
    void failsClosedInsteadOfCreatingDirectoryThroughAnAbsolutePath() throws Exception {
        Path output = tempDir.resolve("one").resolve("two");

        IOException failure = assertThrows(IOException.class,
                () -> new OutputWriter().write(output, "readme", "architecture", false));

        assertEquals("Output directory must already exist and remain unchanged", failure.getMessage());
        assertFalse(Files.exists(tempDir.resolve("one")));
    }

    @Test
    void failsClosedWhenFilesystemHasNoSecureDirectoryStream() throws Exception {
        Path archive = tempDir.resolve("output.zip");
        URI uri = URI.create("jar:" + archive.toUri());
        try (FileSystem zip = FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
            Path output = Files.createDirectory(zip.getPath("/docs"));

            IOException failure = assertThrows(IOException.class,
                    () -> new OutputWriter().write(output, "readme", "architecture", false));

            assertEquals("Secure directory operations are unavailable on this filesystem",
                    failure.getMessage());
            assertEquals(Set.of(), entriesIn(output));
        }
    }

    @Test
    void rejectsOutputDirectoryWritableByOtherUsers() throws Exception {
        assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView("posix"));
        Path shared = Files.createDirectory(tempDir.resolve("shared"));
        Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxrwx---"));

        IOException failure = assertThrows(IOException.class,
                () -> new OutputWriter().write(shared, "readme", "architecture", false));

        assertEquals("Output directory must not be writable by group or other users",
                failure.getMessage());
        assertEquals(Set.of(), entriesIn(shared));
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
