package com.docgen.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalDatabaseTest {
    @TempDir
    Path data;

    @Test
    void compactsOnlyWhenAskedAndForgetsTheRequestAfterwards() throws Exception {
        try (Connection connection = DriverManager.getConnection(LocalDatabase.jdbcUrl(data), "docgen", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE t (v VARCHAR(20))");
        }
        assertFalse(LocalDatabase.compactIfRequested(data), "nothing was asked");

        LocalDatabase.requestCompaction(data);
        assertTrue(Files.exists(data.resolve(LocalDatabase.COMPACTION_MARKER)));
        assertTrue(LocalDatabase.compactIfRequested(data));

        assertFalse(Files.exists(data.resolve(LocalDatabase.COMPACTION_MARKER)));
        assertFalse(LocalDatabase.compactIfRequested(data));
    }

    @Test
    void aRequestWithoutADatabaseFileIsSimplyCleared() throws Exception {
        LocalDatabase.requestCompaction(data);

        assertTrue(LocalDatabase.compactIfRequested(data));
        assertFalse(Files.exists(data.resolve(LocalDatabase.FILE_PREFIX + ".mv.db")), "no empty database is created");
    }

    @Test
    void pathsH2CannotParseAreRefused() {
        assertThrows(IllegalStateException.class, () -> LocalDatabase.jdbcUrl(Path.of("/tmp/a;INIT=RUNSCRIPT")));
    }
}
