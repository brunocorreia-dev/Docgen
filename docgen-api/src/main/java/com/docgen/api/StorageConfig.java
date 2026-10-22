package com.docgen.api;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Local persistence: an embedded H2 database inside a private data directory
 * (mode 0700 on POSIX systems) plus a private staging directory for uploads.
 * H2 runs in-process only; no TCP server or web console is started.
 */
@Configuration(proxyBeanMethods = false)
class StorageConfig {
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rwx------");

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
            data = posix()
                    ? Files.createTempDirectory("docgen-", PosixFilePermissions.asFileAttribute(OWNER_ONLY))
                    : Files.createTempDirectory("docgen-");
        } else {
            data = resolveDataDirectory(storage.dataDirectory());
            createPrivateDirectory(data);
        }
        Path staging = data.resolve("staging");
        createPrivateDirectory(staging);
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
            Path database = paths.dataDirectory().resolve("docgen");
            String location = database.toString();
            if (location.indexOf(';') >= 0 || location.indexOf('?') >= 0) {
                throw new IllegalStateException("The data directory path contains characters H2 cannot accept");
            }
            config.setJdbcUrl("jdbc:h2:file:" + location + ";DB_CLOSE_ON_EXIT=FALSE");
        }
        config.setUsername("docgen");
        config.setPassword("");
        config.setMaximumPoolSize(8);
        config.setPoolName("docgen-h2");
        return new HikariDataSource(config);
    }

    static Path resolveDataDirectory(String configured) {
        Path path;
        if (configured != null && !configured.isBlank()) {
            path = Path.of(configured);
        } else {
            String xdg = System.getenv("XDG_DATA_HOME");
            Path base = xdg != null && !xdg.isBlank() && Path.of(xdg).isAbsolute()
                    ? Path.of(xdg)
                    : Path.of(System.getProperty("user.home"), ".local", "share");
            path = base.resolve("docgen");
        }
        if (!path.isAbsolute() || !path.equals(path.normalize())) {
            throw new IllegalStateException("The data directory must be an absolute, normalized path");
        }
        return path;
    }

    static void createPrivateDirectory(Path directory) throws IOException {
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (posix()) {
                Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
            } else {
                Files.createDirectories(directory);
            }
        }
        BasicFileAttributes attributes = Files.readAttributes(
                directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
            throw new IllegalStateException("Storage paths must be real directories, not symbolic links");
        }
        if (posix()) {
            Files.setPosixFilePermissions(directory, OWNER_ONLY);
        }
    }

    private static void clearStaleStagingFiles(Path staging) throws IOException {
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

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }
}
