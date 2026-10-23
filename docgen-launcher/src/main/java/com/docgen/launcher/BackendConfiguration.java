package com.docgen.launcher;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * How the backend process is started. The command is a list of separate
 * arguments handed to {@link ProcessBuilder}; no shell is involved and no
 * argument ever carries a credential (the session secret goes over stdin).
 *
 * @param command           executable and arguments
 * @param environment       complete environment of the child process
 * @param workingDirectory  the child's working directory (the private state directory)
 * @param requestedPort     fixed port, or 0 to let the backend bind an ephemeral port itself
 * @param startupTimeout    maximum time until the backend is ready
 * @param shutdownGrace     time to exit after the stdin lifeline closes
 * @param terminateGrace    time to exit after SIGTERM, before the process is killed
 * @param handshakeTimeout  maximum time for the HTTP readiness proof
 */
record BackendConfiguration(
        List<String> command,
        Map<String, String> environment,
        Path workingDirectory,
        int requestedPort,
        Duration startupTimeout,
        Duration shutdownGrace,
        Duration terminateGrace,
        Duration handshakeTimeout
) {
    static final Duration DEFAULT_STARTUP_TIMEOUT = Duration.ofSeconds(90);
    static final Duration DEFAULT_SHUTDOWN_GRACE = Duration.ofSeconds(15);
    static final Duration DEFAULT_TERMINATE_GRACE = Duration.ofSeconds(5);
    static final Duration DEFAULT_HANDSHAKE_TIMEOUT = Duration.ofSeconds(10);
    static final String DEFAULT_USERNAME = "desktop";
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    BackendConfiguration {
        command = List.copyOf(command);
        environment = Map.copyOf(environment);
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command is empty");
        }
        if (requestedPort < 0 || requestedPort > 65_535) {
            throw new IllegalArgumentException("invalid port");
        }
    }

    /**
     * The production command: the launcher's own runtime runs the installed
     * API jar in desktop mode, bound to loopback.
     */
    static BackendConfiguration forInstallation(
            RuntimeEnvironment runtime,
            InstallationLayout layout,
            Map<String, String> parentEnvironment,
            String systemUser,
            int requestedPort,
            Duration startupTimeout
    ) {
        if (layout.backendJar() == null || !layout.dataDirectory().valid() || !layout.stateDirectory().valid()) {
            throw new IllegalArgumentException("the installation layout is incomplete");
        }
        List<String> command = new ArrayList<>(List.of(
                runtime.javaExecutable().toString(),
                "-Djava.awt.headless=true",
                "-XX:+ExitOnOutOfMemoryError",
                // The launcher decodes the child's output as UTF-8 regardless of the system locale.
                "-Dstdout.encoding=UTF-8",
                "-Dstderr.encoding=UTF-8",
                "-jar",
                layout.backendJar().toString(),
                "--docgen.desktop.enabled=true",
                "--server.address=" + BackendEndpoint.HOST,
                "--server.port=" + requestedPort));
        return new BackendConfiguration(command,
                childEnvironment(parentEnvironment, username(parentEnvironment, systemUser), layout.dataDirectory().path()),
                layout.stateDirectory().path(), requestedPort, startupTimeout,
                DEFAULT_SHUTDOWN_GRACE, DEFAULT_TERMINATE_GRACE, DEFAULT_HANDSHAKE_TIMEOUT);
    }

    /**
     * The child inherits the user's environment (provider keys, the
     * DOCGEN_ALLOW_REMOTE gate and job settings stay the user's decision) minus
     * every way of creating password logins: in desktop mode the only path to
     * a session is a pairing confirmed in the launcher window.
     */
    static Map<String, String> childEnvironment(Map<String, String> parent, String username, Path dataDirectory) {
        Map<String, String> environment = new LinkedHashMap<>();
        parent.forEach((name, value) -> {
            String upper = name.toUpperCase(Locale.ROOT);
            boolean credential = upper.equals("DOCGEN_API_PASSWORD") || upper.startsWith("DOCGEN_API_ADDITIONAL");
            boolean launcherControlled = upper.equals("DOCGEN_API_PORT") || upper.startsWith("DOCGEN_DESKTOP");
            if (!credential && !launcherControlled) {
                environment.put(name, value);
            }
        });
        environment.put("DOCGEN_API_USERNAME", username);
        environment.put("DOCGEN_DATA_DIR", dataDirectory.toString());
        return environment;
    }

    /**
     * The desktop user owns the projects in the database, so it must be stable
     * across launches: DOCGEN_API_USERNAME when valid, else the system account
     * name reduced to the API's username alphabet.
     */
    static String username(Map<String, String> environment, String systemUser) {
        String configured = environment.get("DOCGEN_API_USERNAME");
        if (configured != null && USERNAME.matcher(configured).matches()) {
            return configured;
        }
        if (systemUser == null) {
            return DEFAULT_USERNAME;
        }
        String sanitized = systemUser.replaceAll("[^A-Za-z0-9._-]", "-");
        sanitized = sanitized.length() > 64 ? sanitized.substring(0, 64) : sanitized;
        return sanitized.isEmpty() || sanitized.chars().allMatch(c -> c == '-') ? DEFAULT_USERNAME : sanitized;
    }

    /** Port requested through DOCGEN_API_PORT, or 0 (ephemeral) when unset. */
    static int portFromEnvironment(Map<String, String> environment) {
        String value = environment.get("DOCGEN_API_PORT");
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            int port = Integer.parseInt(value.strip());
            if (port >= 1 && port <= 65_535) {
                return port;
            }
        } catch (NumberFormatException ignored) {
            // Reported below.
        }
        throw new IllegalArgumentException("DOCGEN_API_PORT precisa ser um número entre 1 e 65535.");
    }
}
