package com.docgen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OutputWriterTest {
    @TempDir
    Path tempDir;

    @Test
    void refusesToOverwriteWithoutForce() throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "existing");

        assertThrows(FileAlreadyExistsException.class,
                () -> new OutputWriter().write(tempDir, "new", "arch", false));
        assertEquals("existing", Files.readString(tempDir.resolve("README.md")));
    }

    @Test
    void overwritesWithForce() throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "existing");
        Files.writeString(tempDir.resolve("ARCHITECTURE.md"), "existing");

        new OutputWriter().write(tempDir, "new", "arch", true);

        assertEquals("new", Files.readString(tempDir.resolve("README.md")));
        assertEquals("arch", Files.readString(tempDir.resolve("ARCHITECTURE.md")));
    }

    @Test
    void restoresExistingFilesWhenSecondWriteFails() throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "old-readme");
        Files.createDirectory(tempDir.resolve("ARCHITECTURE.md"));

        assertThrows(Exception.class,
                () -> new OutputWriter().write(tempDir, "new-readme", "new-architecture", true));
        assertEquals("old-readme", Files.readString(tempDir.resolve("README.md")));
    }

}
