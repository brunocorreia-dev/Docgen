package com.docgen.launcher;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Private per-user directories, with the same rules the API applies to its
 * data directory: real directories (never symbolic links) and mode 0700 on
 * POSIX systems.
 */
final class SecureDirectories {
    static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rwx------");

    private SecureDirectories() {
    }

    static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    /** Creates the directory (and missing parents) privately, or verifies and tightens an existing one. */
    static Path createPrivate(Path directory) throws IOException {
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (posix()) {
                Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
            } else {
                Files.createDirectories(directory);
            }
        }
        BasicFileAttributes attributes = Files.readAttributes(directory, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
            throw new IOException("Not a real directory: " + directory);
        }
        if (posix()) {
            Files.setPosixFilePermissions(directory, OWNER_ONLY);
        }
        return directory;
    }

    /**
     * Read-only check used by diagnostics: returns {@code null} when the
     * directory exists and is writable, or can be created under a writable
     * ancestor; otherwise a user-facing reason.
     */
    static String problem(Path directory) {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(directory)) {
                return "é um link simbólico; use um diretório real.";
            }
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                return "existe, mas não é um diretório.";
            }
            if (!Files.isWritable(directory) || !Files.isReadable(directory)) {
                return "sem permissão de leitura e escrita.";
            }
            return null;
        }
        Path ancestor = directory.getParent();
        while (ancestor != null && Files.notExists(ancestor)) {
            ancestor = ancestor.getParent();
        }
        if (ancestor == null || !Files.isDirectory(ancestor)) {
            return "não pode ser criado.";
        }
        return Files.isWritable(ancestor) ? null : "não pode ser criado: sem permissão em " + ancestor + ".";
    }
}
