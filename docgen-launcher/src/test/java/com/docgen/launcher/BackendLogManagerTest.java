package com.docgen.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendLogManagerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path temp;

    @Test
    void writesPrivateLogFilesInAPrivateDirectory() throws IOException {
        Path directory = temp.resolve("state/logs");
        try (BackendLogManager logs = BackendLogManager.open(directory, CLOCK)) {
            logs.launcher("hello");
            logs.backend("backend line");
        }
        assertEquals("2026-10-23T12:00:00Z hello\n", Files.readString(directory.resolve("launcher.log")));
        assertEquals("backend line\n", Files.readString(directory.resolve("backend.log")));
        if (SecureDirectories.posix()) {
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)));
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.resolve("backend.log"))));
        }
    }

    @Test
    void keepsThePreviousSessionsForTroubleshooting() throws IOException {
        Path directory = temp.resolve("logs");
        for (int session = 1; session <= BackendLogManager.KEPT_GENERATIONS + 2; session++) {
            try (BackendLogManager logs = BackendLogManager.open(directory, CLOCK)) {
                logs.backend("session " + session);
            }
        }
        int last = BackendLogManager.KEPT_GENERATIONS + 2;
        assertEquals("session " + last + "\n", Files.readString(directory.resolve("backend.log")));
        assertEquals("session " + (last - 1) + "\n", Files.readString(directory.resolve("backend.1.log")));
        assertTrue(Files.exists(directory.resolve("backend." + BackendLogManager.KEPT_GENERATIONS + ".log")));
        assertFalse(Files.exists(directory.resolve("backend." + (BackendLogManager.KEPT_GENERATIONS + 1) + ".log")));
    }

    @Test
    void refusesALogDirectoryThatIsASymbolicLink() throws IOException {
        Path real = Files.createDirectories(temp.resolve("real"));
        Path link = Files.createSymbolicLink(temp.resolve("link"), real);
        assertThrows(IOException.class, () -> BackendLogManager.open(link, CLOCK));
    }

    @Test
    void redactsAndBoundsTheInMemoryTail() {
        try (BackendLogManager logs = BackendLogManager.inMemory(CLOCK)) {
            logs.redactor().addSecret("the-session-secret-value");
            for (int line = 0; line < BackendLogManager.MAX_RECENT_LINES + 10; line++) {
                logs.backend("line " + line + " the-session-secret-value");
            }
            assertEquals(BackendLogManager.MAX_RECENT_LINES, logs.recentBackendLines().size());
            assertEquals("line 10 [REDACTED]", logs.recentBackendLines().getFirst());
            assertTrue(logs.backendLogFile().isEmpty());
        }
    }
}
