package com.docgen.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Set;

/**
 * Private per-user files: real directories and files (never symbolic links),
 * mode 0700/0600 on POSIX systems. On Windows the per-user profile directories
 * (%LOCALAPPDATA%) are already restricted to the user by inherited ACLs.
 */
public final class PrivateFiles {
    private static final Set<PosixFilePermission> DIRECTORY = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE = PosixFilePermissions.fromString("rw-------");

    private PrivateFiles() {
    }

    public static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    /** Creates the directory (and missing parents) privately, or verifies and tightens an existing one. */
    public static Path createDirectory(Path directory) throws IOException {
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (posix()) {
                Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(DIRECTORY));
            } else {
                Files.createDirectories(directory);
            }
        }
        BasicFileAttributes attributes = Files.readAttributes(
                directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
            throw new IOException("Storage paths must be real directories, not symbolic links");
        }
        if (posix()) {
            Files.setPosixFilePermissions(directory, DIRECTORY);
        }
        return directory;
    }

    /**
     * Replaces {@code target} with {@code content} so readers see either the
     * old or the new file, never a partial one. The temporary file is created
     * private before any byte is written.
     */
    public static void writeAtomically(Path target, byte[] content) throws IOException {
        Path directory = target.getParent();
        Path temporary = posix()
                ? Files.createTempFile(directory, ".tmp-", ".part", PosixFilePermissions.asFileAttribute(FILE))
                : Files.createTempFile(directory, ".tmp-", ".part");
        try {
            Files.write(temporary, content, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Reads a small regular file, refusing symbolic links and anything larger
     * than {@code maxBytes}; empty when the file does not exist.
     */
    public static Optional<byte[]> readSmallFile(Path file, int maxBytes) throws IOException {
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Not a regular file");
        }
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] content = input.readNBytes(maxBytes + 1);
            if (content.length > maxBytes) {
                throw new IOException("File exceeds its size limit");
            }
            return Optional.of(content);
        }
    }
}
