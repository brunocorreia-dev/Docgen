package com.docgen.launcher;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Log files of one launcher session: {@code launcher.log} (the launcher's own
 * events) and {@code backend.log} (the API's output). Both live in the private
 * log directory, are created fresh with mode 0600, and the previous
 * {@value #KEPT_GENERATIONS} sessions are kept as {@code *.1.log} ... so the
 * output of a failed start survives a retry. Every line passes through the
 * {@link LogRedactor}; files are capped in size, and the most recent backend
 * lines are kept in memory for the log viewer and failure classification.
 */
final class BackendLogManager implements AutoCloseable {
    static final int MAX_RECENT_LINES = 400;
    static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    static final int KEPT_GENERATIONS = 5;
    static final String LAUNCHER_LOG = "launcher";
    static final String BACKEND_LOG = "backend";

    private final Path directory;
    private final Clock clock;
    private final LogRedactor redactor = new LogRedactor();
    private final Sink launcherSink;
    private final Sink backendSink;
    private final Deque<String> recentBackend = new ArrayDeque<>();

    private BackendLogManager(Path directory, Clock clock, Sink launcherSink, Sink backendSink) {
        this.directory = directory;
        this.clock = clock;
        this.launcherSink = launcherSink;
        this.backendSink = backendSink;
    }

    static BackendLogManager open(Path directory, Clock clock) throws IOException {
        SecureDirectories.createPrivate(directory);
        rotate(directory, LAUNCHER_LOG);
        rotate(directory, BACKEND_LOG);
        return new BackendLogManager(directory, clock,
                Sink.create(directory.resolve(LAUNCHER_LOG + ".log")),
                Sink.create(directory.resolve(BACKEND_LOG + ".log")));
    }

    /** Logs kept only in memory; used when no log directory is usable and in tests. */
    static BackendLogManager inMemory(Clock clock) {
        return new BackendLogManager(null, clock, Sink.discarding(), Sink.discarding());
    }

    LogRedactor redactor() {
        return redactor;
    }

    void launcher(String message) {
        launcherSink.write(clock.instant() + " " + redactor.redact(message));
    }

    void backend(String line) {
        String safe = redactor.redact(line);
        synchronized (recentBackend) {
            recentBackend.addLast(safe);
            while (recentBackend.size() > MAX_RECENT_LINES) {
                recentBackend.removeFirst();
            }
        }
        backendSink.write(safe);
    }

    List<String> recentBackendLines() {
        synchronized (recentBackend) {
            return List.copyOf(recentBackend);
        }
    }

    Optional<Path> directory() {
        return Optional.ofNullable(directory);
    }

    Optional<Path> backendLogFile() {
        return directory().map(path -> path.resolve(BACKEND_LOG + ".log"));
    }

    Optional<Path> launcherLogFile() {
        return directory().map(path -> path.resolve(LAUNCHER_LOG + ".log"));
    }

    @Override
    public void close() {
        launcherSink.close();
        backendSink.close();
    }

    private static void rotate(Path directory, String name) throws IOException {
        Files.deleteIfExists(directory.resolve(name + "." + KEPT_GENERATIONS + ".log"));
        for (int generation = KEPT_GENERATIONS - 1; generation >= 1; generation--) {
            Path source = directory.resolve(name + "." + generation + ".log");
            if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                Files.move(source, directory.resolve(name + "." + (generation + 1) + ".log"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path current = directory.resolve(name + ".log");
        if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            Files.move(current, directory.resolve(name + ".1.log"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** One capped, line-oriented log file. Write failures never propagate into the caller. */
    private static final class Sink {
        private final Writer writer;
        private long written;
        private boolean full;
        private boolean broken;

        private Sink(Writer writer) {
            this.writer = writer;
        }

        static Sink create(Path file) throws IOException {
            // CREATE_NEW refuses an existing file or symbolic link planted at the path.
            if (SecureDirectories.posix()) {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } else {
                Files.createFile(file);
            }
            return new Sink(new BufferedWriter(new OutputStreamWriter(
                    Files.newOutputStream(file, StandardOpenOption.WRITE, StandardOpenOption.APPEND), StandardCharsets.UTF_8)));
        }

        static Sink discarding() {
            return new Sink(null);
        }

        synchronized void write(String line) {
            if (writer == null || full || broken) {
                return;
            }
            try {
                long size = line.getBytes(StandardCharsets.UTF_8).length + 1L;
                if (written + size > MAX_FILE_BYTES) {
                    full = true;
                    writer.write("[log truncado: limite de " + (MAX_FILE_BYTES / (1024 * 1024)) + " MiB atingido]\n");
                } else {
                    writer.write(line);
                    writer.write('\n');
                    written += size;
                }
                writer.flush();
            } catch (IOException failure) {
                broken = true;
            }
        }

        synchronized void close() {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                    // Nothing else can be done with a log that cannot be closed.
                }
            }
        }
    }
}
