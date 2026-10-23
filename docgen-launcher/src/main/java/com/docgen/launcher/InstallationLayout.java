package com.docgen.launcher;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where the launcher, the backend jar and the per-user directories live.
 *
 * <p>Everything is derived from the launcher's own code location and from
 * per-user conventions, never from the working directory, so starting the
 * app from a menu, a terminal or an IDE resolves the same files. Persistent
 * data, state and logs always live in the user's profile, never inside the
 * (possibly read-only) installation directory.</p>
 *
 * @param launcherLocation      the launcher jar, or its classes directory in development
 * @param installationDirectory root of the packaged app image, or the launcher's directory
 * @param packaged              started from a jpackage launcher
 * @param backendCandidates     locations searched for the backend jar, in order
 * @param backendJar            the first candidate that is a regular file, or {@code null}
 */
record InstallationLayout(
        Path launcherLocation,
        Path installationDirectory,
        boolean packaged,
        List<Path> backendCandidates,
        Path backendJar,
        Location dataDirectory,
        Location stateDirectory,
        Location logDirectory
) {
    static final String BACKEND_JAR = "docgen-api.jar";

    /** A resolved directory, or the reason no safe directory could be derived. */
    record Location(Path path, String problem) {
        static Location of(Path path) {
            return new Location(path, null);
        }

        static Location invalid(String problem) {
            return new Location(null, problem);
        }

        boolean valid() {
            return problem == null;
        }
    }

    InstallationLayout {
        backendCandidates = List.copyOf(backendCandidates);
    }

    static InstallationLayout current(PlatformDetector.Platform platform, Path backendOverride) {
        return resolve(platform, System.getenv(), Path.of(System.getProperty("user.home")),
                launcherCodeLocation(), backendOverride, System.getProperty("jpackage.app-path"));
    }

    static Path launcherCodeLocation() {
        try {
            return Path.of(DocGenLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath().normalize();
        } catch (URISyntaxException | RuntimeException unavailable) {
            throw new IllegalStateException("The launcher location cannot be determined", unavailable);
        }
    }

    static InstallationLayout resolve(
            PlatformDetector.Platform platform,
            Map<String, String> environment,
            Path userHome,
            Path launcherLocation,
            Path backendOverride,
            String jpackageAppPath
    ) {
        Path launcher = launcherLocation.toAbsolutePath().normalize();
        Path launcherDirectory = Files.isDirectory(launcher) ? launcher : launcher.getParent();
        boolean packaged = jpackageAppPath != null && !jpackageAppPath.isBlank();
        Path installation = packaged ? packagedRoot(platform, Path.of(jpackageAppPath)) : launcherDirectory;

        List<Path> candidates = new ArrayList<>();
        if (backendOverride != null) {
            candidates.add(backendOverride.toAbsolutePath().normalize());
        } else {
            candidates.add(launcherDirectory.resolve("backend").resolve(BACKEND_JAR));
            candidates.add(launcherDirectory.resolve(BACKEND_JAR));
            if (!packaged) {
                developmentBackend(launcherDirectory).ifPresent(candidates::add);
            }
        }
        Path backend = null;
        for (Path candidate : candidates) {
            // Automatic candidates must be real files; only an explicit override may be a symbolic link.
            boolean usable = backendOverride != null
                    ? Files.isRegularFile(candidate)
                    : Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS);
            if (usable) {
                backend = candidate;
                break;
            }
        }

        Path home = userHome.toAbsolutePath().normalize();
        Location data;
        Location state;
        Location logs;
        switch (platform.os()) {
            case WINDOWS -> {
                Path base = absolute(environment.get("LOCALAPPDATA"))
                        .orElse(home.resolve("AppData").resolve("Local")).resolve("DocGen");
                data = Location.of(base.resolve("data"));
                state = Location.of(base.resolve("launcher"));
                logs = Location.of(base.resolve("logs"));
            }
            case MACOS -> {
                Path support = home.resolve("Library").resolve("Application Support").resolve("DocGen");
                data = Location.of(support.resolve("data"));
                state = Location.of(support.resolve("launcher"));
                logs = Location.of(home.resolve("Library").resolve("Logs").resolve("DocGen"));
            }
            default -> {
                // Same default as the API's StorageConfig, so desktop and docgen-server share projects.
                Path dataHome = absolute(environment.get("XDG_DATA_HOME"))
                        .orElse(home.resolve(".local").resolve("share"));
                Path stateHome = absolute(environment.get("XDG_STATE_HOME"))
                        .orElse(home.resolve(".local").resolve("state"));
                data = Location.of(dataHome.resolve("docgen"));
                state = Location.of(stateHome.resolve("docgen"));
                logs = Location.of(stateHome.resolve("docgen").resolve("logs"));
            }
        }
        String configuredData = environment.get("DOCGEN_DATA_DIR");
        if (configuredData != null && !configuredData.isBlank()) {
            data = absolute(configuredData).map(Location::of).orElse(Location.invalid(
                    "DOCGEN_DATA_DIR precisa ser um caminho absoluto e normalizado."));
        }
        if (packaged) {
            data = outsideInstallation(data, installation);
            state = outsideInstallation(state, installation);
            logs = outsideInstallation(logs, installation);
        }
        return new InstallationLayout(launcher, installation, packaged, candidates, backend, data, state, logs);
    }

    private static Path packagedRoot(PlatformDetector.Platform platform, Path appPath) {
        Path executable = appPath.toAbsolutePath().normalize();
        // Linux: <root>/bin/DocGen · Windows: <root>\DocGen.exe · macOS: DocGen.app/Contents/MacOS/DocGen
        Path root = platform.os() == PlatformDetector.OperatingSystem.WINDOWS
                ? executable.getParent()
                : executable.getParent() == null ? null : executable.getParent().getParent();
        return root == null ? executable : root;
    }

    /** In a source checkout the launcher lives in docgen-launcher/target; the API jar is its sibling module's. */
    private static Optional<Path> developmentBackend(Path launcherDirectory) {
        Path current = launcherDirectory;
        for (int depth = 0; depth < 3 && current != null; depth++) {
            Path name = current.getFileName();
            if (name != null && name.toString().equals("docgen-launcher") && current.getParent() != null) {
                return Optional.of(current.getParent().resolve("docgen-api").resolve("target").resolve(BACKEND_JAR));
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    /** Environment paths are accepted only when absolute and already normalized (no "..", no relative tricks). */
    private static Optional<Path> absolute(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            Path path = Path.of(value);
            return path.isAbsolute() && path.equals(path.normalize())
                    ? Optional.of(path)
                    : Optional.empty();
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private static Location outsideInstallation(Location location, Path installation) {
        if (location.valid() && location.path().startsWith(installation)) {
            return Location.invalid("O diretório " + location.path()
                    + " fica dentro da instalação do DocGen, que pode ser somente leitura.");
        }
        return location;
    }
}
