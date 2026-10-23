package com.docgen.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Properties;

/**
 * Entry point of the DocGen desktop launcher: a small native window that
 * starts the loopback DocGen API as a child process, verifies that the API
 * answering on the port is that child, and opens the web workspace.
 */
public final class DocGenLauncher {
    static final int EXIT_OK = 0;
    static final int EXIT_FAILURE = 1;
    static final int EXIT_USAGE = 2;

    private DocGenLauncher() {
    }

    public static void main(String[] args) {
        System.exit(run(List.of(args), System.out, System.err));
    }

    static int run(List<String> args, PrintStream out, PrintStream err) {
        LauncherOptions options;
        try {
            options = LauncherOptions.parse(args);
        } catch (IllegalArgumentException invalid) {
            err.println("docgen-launcher: " + invalid.getMessage());
            err.print(LauncherOptions.USAGE);
            return EXIT_USAGE;
        }
        return switch (options.mode()) {
            case VERSION -> {
                out.println("DocGen Launcher " + version());
                yield EXIT_OK;
            }
            case DIAGNOSE -> diagnose(options, out);
            case HELP, DESKTOP -> {
                out.print(LauncherOptions.USAGE);
                yield EXIT_OK;
            }
        };
    }

    private static int diagnose(LauncherOptions options, PrintStream out) {
        PlatformDetector.Platform platform = PlatformDetector.current();
        LauncherDiagnostics.Report report = LauncherDiagnostics.run(platform, RuntimeEnvironment.current(platform),
                InstallationLayout.current(platform, options.backendJar()));
        out.println("DocGen Launcher " + version() + " — diagnóstico");
        out.println();
        out.print(report.toText());
        out.println();
        out.println(report.ok() ? "Ambiente pronto para iniciar o DocGen." : "O DocGen não pode ser iniciado neste ambiente.");
        return report.ok() ? EXIT_OK : EXIT_FAILURE;
    }

    static String version() {
        try (InputStream input = DocGenLauncher.class.getResourceAsStream("launcher.properties")) {
            if (input == null) {
                return "dev";
            }
            Properties properties = new Properties();
            properties.load(input);
            String version = properties.getProperty("version", "dev");
            return version.isBlank() || version.startsWith("${") ? "dev" : version;
        } catch (IOException unreadable) {
            return "dev";
        }
    }
}
