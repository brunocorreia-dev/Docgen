package com.docgen.desktop;

import com.docgen.api.PrivateFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class InstanceFileTest {
    private static final String SECRET = "abcDEF0123456789_-abcDEF0123456789_-abcDEF0";

    @TempDir
    Path data;

    @Test
    void roundTripsPrivatelyAndAtomically() throws IOException {
        InstanceFile written = new InstanceFile(48_123, ProcessHandle.current().pid(), SECRET);
        written.write(data);

        assertEquals(written, InstanceFile.read(data).orElseThrow());
        if (PrivateFiles.posix()) {
            assertEquals("rw-------", PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(data.resolve(InstanceFile.FILE))));
        }
        try (var leftovers = Files.list(data)) {
            assertEquals(1, leftovers.count(), "no temporary file is left behind");
        }
        assertTrue(written.processAlive());
    }

    @Test
    void neverPrintsTheSecret() {
        assertFalse(new InstanceFile(1, 1, SECRET).toString().contains(SECRET));
    }

    @Test
    void malformedOrHostileFilesAreIgnored() throws IOException {
        Path file = data.resolve(InstanceFile.FILE);
        for (String content : new String[]{"not json", "{}", "{\"port\":0,\"pid\":1,\"secret\":\"" + SECRET + "\"}",
                "{\"port\":80,\"pid\":1,\"secret\":\"short\"}", "{\"port\":\"80\",\"pid\":1,\"secret\":\"" + SECRET + "\"}",
                "{\"port\":80,\"pid\":1,\"secret\":\"" + SECRET + "\"," + "\"x\":\"" + "a".repeat(2000) + "\"}"}) {
            Files.writeString(file, content);
            assertTrue(InstanceFile.read(data).isEmpty(), content);
        }
        Files.delete(file);
        assumeTrue(PrivateFiles.posix(), "symbolic links need privileges on Windows");
        Files.createSymbolicLink(file, Files.writeString(data.resolve("elsewhere"),
                "{\"port\":80,\"pid\":1,\"secret\":\"" + SECRET + "\"}"));
        assertTrue(InstanceFile.read(data).isEmpty(), "symbolic links are refused");
        assertTrue(InstanceFile.read(data.resolve("missing")).isEmpty());
    }

    @Test
    void aFileLeftByACrashedProcessIsRecognized() {
        long deadPid = ProcessHandle.allProcesses().mapToLong(ProcessHandle::pid).max().orElse(1) + 100_000;

        assertFalse(new InstanceFile(80, deadPid, SECRET).processAlive());
    }

    @Test
    void invalidValuesNeverBecomeAnInstanceFile() {
        assertThrows(IllegalArgumentException.class, () -> new InstanceFile(70_000, 1, SECRET));
        assertThrows(IllegalArgumentException.class, () -> new InstanceFile(80, 0, SECRET));
        assertThrows(IllegalArgumentException.class, () -> new InstanceFile(80, 1, "x"));
    }
}
