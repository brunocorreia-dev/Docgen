package com.docgen.api;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Local persistence: an embedded H2 database inside a private data directory
 * (mode 0700 on POSIX systems) plus a private staging directory for uploads.
 * H2 runs in-process only; no TCP server or web console is started.
 *
 * <p>The data directory is resolved per operating system by the desktop
 * launcher ({@code com.docgen.desktop.AppDirectories}), which also holds the
 * single-instance lock there, and passed in as
 * {@code docgen.storage.data-directory}.</p>
 */
@Configuration(proxyBeanMethods = false)
class StorageConfig {
    record StoragePaths(Path dataDirectory, Path staging, boolean temporary) {
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "")
    StoragePaths storagePaths(DocGenApiProperties properties) throws IOException {
        DocGenApiProperties.Storage storage = properties.storage();
        Path data;
        boolean temporary = storage.inMemory();
        if (temporary) {
            data = PrivateFiles.posix()
                    ? Files.createTempDirectory("docgen-",
                            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                    : Files.createTempDirectory("docgen-");
        } else {
            data = configuredDataDirectory(storage.dataDirectory());
            PrivateFiles.createDirectory(data);
        }
        Path staging = data.resolve("staging");
        PrivateFiles.createDirectory(staging);
        clearStaleStagingFiles(staging);
        StoragePaths paths = new StoragePaths(data, staging, temporary);
        if (temporary) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteRecursively(paths.dataDirectory())));
        }
        return paths;
    }

    @Bean(destroyMethod = "close")
    DataSource dataSource(DocGenApiProperties properties, StoragePaths paths) {
        HikariConfig config = new HikariConfig();
        if (paths.temporary()) {
            config.setJdbcUrl("jdbc:h2:mem:docgen-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        } else {
            config.setJdbcUrl(LocalDatabase.jdbcUrl(paths.dataDirectory()));
        }
        config.setUsername("docgen");
        config.setPassword("");
        config.setMaximumPoolSize(8);
        config.setPoolName("docgen-h2");
        return new HikariDataSource(config);
    }

    static Path configuredDataDirectory(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("No data directory is configured; start DocGen through its launcher");
        }
        Path path = Path.of(configured);
        if (!path.isAbsolute() || !path.equals(path.normalize())) {
            throw new IllegalStateException("The data directory must be an absolute, normalized path");
        }
        return path;
    }

    static void clearStaleStagingFiles(Path staging) throws IOException {
        try (Stream<Path> entries = Files.list(staging)) {
            for (Path entry : entries.toList()) {
                if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(entry);
                }
            }
        }
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort on shutdown; the directory is private and temporary.
                }
            });
        } catch (IOException ignored) {
            // Best effort on shutdown.
        }
    }
}
