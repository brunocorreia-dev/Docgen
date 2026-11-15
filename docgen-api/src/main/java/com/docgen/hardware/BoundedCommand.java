package com.docgen.hardware;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs a diagnostic program the safe way: a resolved absolute executable, a
 * fixed argument list and no shell, a short timeout after which the process is
 * killed, stderr discarded, stdin closed, and at most {@code maxBytes} of
 * stdout kept. A failure of any kind is an empty result, never an exception.
 */
final class BoundedCommand {
    private BoundedCommand() {
    }

    static Optional<String> run(List<String> command, Duration timeout, int maxBytes) {
        if (command.isEmpty() || !Path.of(command.getFirst()).isAbsolute()) {
            return Optional.empty();
        }
        Process started = null;
        try {
            started = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            started.getOutputStream().close();
        } catch (IOException | RuntimeException notStartable) {
            if (started != null) {
                started.destroyForcibly();
            }
            return Optional.empty();
        }
        Process process = started;
        CompletableFuture<byte[]> output = new CompletableFuture<>();
        Thread.ofVirtual().name("docgen-diagnostic-output").start(() -> {
            try (InputStream stdout = process.getInputStream()) {
                byte[] bytes = stdout.readNBytes(maxBytes + 1);
                stdout.transferTo(OutputStream.nullOutputStream());
                output.complete(bytes);
            } catch (IOException unreadable) {
                output.complete(null);
            }
        });
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            byte[] bytes = output.get(1, TimeUnit.SECONDS);
            if (process.exitValue() != 0 || bytes == null || bytes.length > maxBytes) {
                return Optional.empty();
            }
            return Optional.of(new String(bytes, StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception failed) {
            process.destroyForcibly();
            return Optional.empty();
        }
    }

    /** The first executable called {@code name} in an absolute {@code PATH} entry (relative entries are ignored). */
    static Optional<Path> onPath(String name, String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        for (String entry : path.split(File.pathSeparator)) {
            try {
                Path directory = Path.of(entry);
                if (!directory.isAbsolute()) {
                    continue;
                }
                Path candidate = directory.normalize().resolve(name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return Optional.of(candidate);
                }
            } catch (InvalidPathException ignored) {
                // Not a usable PATH entry.
            }
        }
        return Optional.empty();
    }
}
