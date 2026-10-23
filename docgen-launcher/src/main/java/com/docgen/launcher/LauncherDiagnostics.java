package com.docgen.launcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pre-flight checks run before the backend is started. Diagnostics are
 * read-only: they never create directories or start processes, so
 * {@code --diagnose} is safe to run at any time.
 */
final class LauncherDiagnostics {
    enum Status {
        OK("OK"),
        WARNING("AVISO"),
        FAILURE("FALHA");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    /** What kind of problem a failed check represents; the window chooses its headline from it. */
    enum Category {
        PLATFORM,
        RUNTIME,
        MISSING_FILES,
        PERMISSION
    }

    record Check(String id, String label, Status status, Category category, String message) {
    }

    record Report(List<Check> checks) {
        Report {
            checks = List.copyOf(checks);
        }

        boolean ok() {
            return firstFailure().isEmpty();
        }

        Optional<Check> firstFailure() {
            return checks.stream().filter(check -> check.status() == Status.FAILURE).findFirst();
        }

        String toText() {
            StringBuilder text = new StringBuilder();
            for (Check check : checks) {
                text.append(String.format("[%-5s] %s: %s%n", check.status().label(), check.label(), check.message()));
            }
            return text.toString();
        }
    }

    private LauncherDiagnostics() {
    }

    static Report run(PlatformDetector.Platform platform, RuntimeEnvironment runtime, InstallationLayout layout) {
        List<Check> checks = new ArrayList<>();
        checks.add(platform(platform));
        checks.add(architecture(platform));
        checks.add(runtime(runtime));
        checks.add(backend(layout));
        checks.add(directory("data-directory", "Diretório de dados", layout.dataDirectory()));
        checks.add(directory("log-directory", "Diretório de logs", layout.logDirectory()));
        checks.add(directory("state-directory", "Diretório de estado", layout.stateDirectory()));
        return new Report(checks);
    }

    private static Check platform(PlatformDetector.Platform platform) {
        String description = platform.os().label() + " (" + platform.osName() + ")";
        return switch (platform.os()) {
            case LINUX -> new Check("platform", "Sistema operacional", Status.OK, Category.PLATFORM, description);
            case WINDOWS, MACOS -> new Check("platform", "Sistema operacional", Status.WARNING, Category.PLATFORM,
                    description + "; suporte preparado, mas ainda não validado nesta plataforma.");
            case OTHER -> new Check("platform", "Sistema operacional", Status.WARNING, Category.PLATFORM,
                    description + "; sistema não reconhecido, usando convenções POSIX.");
        };
    }

    private static Check architecture(PlatformDetector.Platform platform) {
        return platform.arch() == PlatformDetector.Architecture.UNKNOWN
                ? new Check("architecture", "Arquitetura", Status.WARNING, Category.PLATFORM,
                "arquitetura não reconhecida (" + platform.architecture() + ").")
                : new Check("architecture", "Arquitetura", Status.OK, Category.PLATFORM, platform.arch().label());
    }

    private static Check runtime(RuntimeEnvironment runtime) {
        String description = "Java " + runtime.version() + " (" + runtime.vendor() + ") em " + runtime.javaHome();
        if (!runtime.supportedVersion()) {
            return new Check("runtime", "Runtime Java", Status.FAILURE, Category.RUNTIME,
                    description + "; é necessário Java " + RuntimeEnvironment.MINIMUM_FEATURE_VERSION + " ou superior.");
        }
        if (!runtime.executableAvailable()) {
            return new Check("runtime", "Runtime Java", Status.FAILURE, Category.RUNTIME,
                    "o executável " + runtime.javaExecutable() + " não existe ou não pode ser executado.");
        }
        return new Check("runtime", "Runtime Java", Status.OK, Category.RUNTIME, description);
    }

    private static Check backend(InstallationLayout layout) {
        if (layout.backendJar() == null) {
            return new Check("backend", "Backend do DocGen", Status.FAILURE, Category.MISSING_FILES,
                    "docgen-api.jar não encontrado. Locais verificados: " + layout.backendCandidates() + ".");
        }
        String problem = BackendArtifact.problem(layout.backendJar());
        return problem == null
                ? new Check("backend", "Backend do DocGen", Status.OK, Category.MISSING_FILES,
                layout.backendJar().toString())
                : new Check("backend", "Backend do DocGen", Status.FAILURE, Category.MISSING_FILES,
                layout.backendJar() + ": " + problem);
    }

    private static Check directory(String id, String label, InstallationLayout.Location location) {
        if (!location.valid()) {
            return new Check(id, label, Status.FAILURE, Category.PERMISSION, location.problem());
        }
        String problem = SecureDirectories.problem(location.path());
        return problem == null
                ? new Check(id, label, Status.OK, Category.PERMISSION, location.path().toString())
                : new Check(id, label, Status.FAILURE, Category.PERMISSION, location.path() + " " + problem);
    }
}
