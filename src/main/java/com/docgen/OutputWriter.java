package com.docgen;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class OutputWriter {
    public void write(Path outputDir, String readme, String architecture, boolean force) throws IOException {
        Files.createDirectories(outputDir);
        Path readmePath = outputDir.resolve("README.md");
        Path architecturePath = outputDir.resolve("ARCHITECTURE.md");

        if (!force) {
            failIfExists(readmePath);
            failIfExists(architecturePath);
        }

        Path readmeBackup = null;
        Path architectureBackup = null;
        try {
            readmeBackup = backupIfExists(readmePath);
            architectureBackup = backupIfExists(architecturePath);
            safeWrite(readmePath, readme);
            safeWrite(architecturePath, architecture);
            deleteIfPresent(readmeBackup);
            deleteIfPresent(architectureBackup);
        } catch (IOException | RuntimeException e) {
            restoreBackup(readmePath, readmeBackup);
            restoreBackup(architecturePath, architectureBackup);
            throw e;
        }
    }

    private static void failIfExists(Path path) throws FileAlreadyExistsException {
        if (Files.exists(path)) {
            throw new FileAlreadyExistsException(path.toString(), null, "Use --force to overwrite");
        }
    }

    private static Path backupIfExists(Path target) throws IOException {
        if (!Files.exists(target)) {
            return null;
        }
        Path backup = Files.createTempFile(target.toAbsolutePath().getParent(), target.getFileName().toString(), ".bak");
        move(target, backup, true);
        return backup;
    }

    private static void restoreBackup(Path target, Path backup) throws IOException {
        if (backup == null || !Files.exists(backup)) {
            Files.deleteIfExists(target);
            return;
        }
        move(backup, target, true);
    }

    private static void safeWrite(Path target, String content) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path temp = Files.createTempFile(dir, target.getFileName().toString(), ".tmp");
        Files.writeString(temp, content == null ? "" : content);
        try {
            move(temp, target, true);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void move(Path source, Path target, boolean replaceExisting) throws IOException {
        StandardCopyOption[] options = replaceExisting
                ? new StandardCopyOption[]{StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING}
                : new StandardCopyOption[]{StandardCopyOption.ATOMIC_MOVE};
        try {
            Files.move(source, target, options);
        } catch (AtomicMoveNotSupportedException ignored) {
            if (replaceExisting) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, target);
            }
        }
    }

    private static void deleteIfPresent(Path path) throws IOException {
        if (path != null) {
            Files.deleteIfExists(path);
        }
    }
}
