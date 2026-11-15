package com.docgen.desktop;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Where DocGen keeps its data on each operating system (ADR 0001):
 *
 * <ul>
 *   <li>Linux (and other Unix): {@code $XDG_DATA_HOME/docgen}, or {@code ~/.local/share/docgen}. This
 *   was already the data directory of DocGen 2, so existing data stays valid.</li>
 *   <li>macOS: {@code ~/Library/Application Support/DocGen}.</li>
 *   <li>Windows: {@code %LOCALAPPDATA%\DocGen}. Not {@code %APPDATA%}: the roaming profile is copied to
 *   the domain server on managed machines, which would take conversations off the computer.</li>
 * </ul>
 *
 * <p>{@code DOCGEN_DATA_DIR} overrides the location for development and tests. Environment values are
 * honored only when absolute and already normalized (no "..", no relative tricks).</p>
 */
final class AppDirectories {
    static final String DATA_OVERRIDE = "DOCGEN_DATA_DIR";

    private AppDirectories() {
    }

    static Path dataDirectory(Platform platform, Map<String, String> environment, Path userHome) {
        String override = environment.get(DATA_OVERRIDE);
        if (override != null && !override.isBlank()) {
            return absolute(override).orElseThrow(() -> new IllegalStateException(
                    DATA_OVERRIDE + " must be an absolute, normalized path"));
        }
        Path home = userHome.toAbsolutePath().normalize();
        return switch (platform) {
            case WINDOWS -> absolute(environment.get("LOCALAPPDATA"))
                    .orElse(home.resolve("AppData").resolve("Local"))
                    .resolve("DocGen");
            case MACOS -> home.resolve("Library").resolve("Application Support").resolve("DocGen");
            case LINUX, OTHER -> absolute(environment.get("XDG_DATA_HOME"))
                    .orElse(home.resolve(".local").resolve("share"))
                    .resolve("docgen");
        };
    }

    static Path logDirectory(Path dataDirectory) {
        return dataDirectory.resolve("logs");
    }

    private static Optional<Path> absolute(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            Path path = Path.of(value);
            return path.isAbsolute() && path.equals(path.normalize()) ? Optional.of(path) : Optional.empty();
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }
}
