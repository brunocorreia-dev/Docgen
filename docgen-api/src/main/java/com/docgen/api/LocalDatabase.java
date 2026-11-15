package com.docgen.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The embedded H2 database file inside the private data directory.
 *
 * <p>Deleting rows does not overwrite them: H2 keeps freed pages in the file
 * until they are reused. After "Apagar todos os meus dados" a marker file asks
 * for {@code SHUTDOWN COMPACT}, which rewrites the database into a new file.
 * It runs only while no other connection is open: when the desktop app exits
 * (after the Spring context closed its pool) or, if the app was killed before
 * that, at the next start, before the pool opens.</p>
 */
public final class LocalDatabase {
    static final String FILE_PREFIX = "docgen";
    static final String COMPACTION_MARKER = "compact-on-exit";

    private LocalDatabase() {
    }

    static String jdbcUrl(Path dataDirectory) {
        String location = dataDirectory.resolve(FILE_PREFIX).toString();
        if (location.indexOf(';') >= 0 || location.indexOf('?') >= 0) {
            throw new IllegalStateException("The data directory path contains characters H2 cannot accept");
        }
        return "jdbc:h2:file:" + location + ";DB_CLOSE_ON_EXIT=FALSE";
    }

    static void requestCompaction(Path dataDirectory) throws IOException {
        Path marker = dataDirectory.resolve(COMPACTION_MARKER);
        if (Files.notExists(marker, LinkOption.NOFOLLOW_LINKS)) {
            PrivateFiles.writeAtomically(marker, new byte[0]);
        }
    }

    /**
     * Compacts the database when a compaction was requested. Must be called
     * only while nothing else has the database open.
     *
     * @return true when a compaction ran
     */
    public static boolean compactIfRequested(Path dataDirectory) throws IOException, SQLException {
        Path marker = dataDirectory.resolve(COMPACTION_MARKER);
        if (Files.notExists(marker, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (Files.exists(dataDirectory.resolve(FILE_PREFIX + ".mv.db"), LinkOption.NOFOLLOW_LINKS)) {
            try {
                Class.forName("org.h2.Driver", true, LocalDatabase.class.getClassLoader());
            } catch (ClassNotFoundException missing) {
                throw new SQLException("The H2 driver is not available", missing);
            }
            try (Connection connection = DriverManager.getConnection(jdbcUrl(dataDirectory), "docgen", "");
                 Statement statement = connection.createStatement()) {
                statement.execute("SHUTDOWN COMPACT");
            }
        }
        Files.deleteIfExists(marker);
        return true;
    }
}
