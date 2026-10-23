package com.docgen.launcher;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import javax.swing.SwingUtilities;

/**
 * Entry point of the DocGen desktop launcher: a small native window that
 * starts the loopback DocGen API as a child process, verifies that the API
 * answering on the port is that child, and opens the web workspace.
 */
public final class DocGenLauncher {
    static final int EXIT_OK = 0;
    static final int EXIT_FAILURE = 1;
    static final int EXIT_USAGE = 2;
    /** The window is running; the controller exits the JVM when the person quits. */
    static final int RUNNING = -1;

    private DocGenLauncher() {
    }

    public static void main(String[] args) {
        int status = run(List.of(args), System.out, System.err);
        if (status != RUNNING) {
            System.exit(status);
        }
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
            case HELP -> {
                out.print(LauncherOptions.USAGE);
                yield EXIT_OK;
            }
            case DIAGNOSE -> diagnose(options, out);
            case CHECK -> check(options, out, err);
            case DESKTOP -> desktop(options, err);
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

    /**
     * Headless smoke test of the real startup path: diagnostics, instance
     * lock, backend start, authenticated READY, HTTP readiness proof, stop.
     */
    static int check(LauncherOptions options, PrintStream out, PrintStream err) {
        PlatformDetector.Platform platform = PlatformDetector.current();
        RuntimeEnvironment runtime = RuntimeEnvironment.current(platform);
        InstallationLayout layout = InstallationLayout.current(platform, options.backendJar());
        LauncherDiagnostics.Report report = LauncherDiagnostics.run(platform, runtime, layout);
        out.print(report.toText());
        if (!report.ok()) {
            err.println("docgen-launcher: o ambiente não permite iniciar o DocGen.");
            return EXIT_FAILURE;
        }
        BackendConfiguration configuration;
        try {
            configuration = configuration(options, runtime, layout);
        } catch (IllegalArgumentException invalid) {
            err.println("docgen-launcher: " + invalid.getMessage());
            return EXIT_FAILURE;
        }
        try (SingleInstanceLock lock = lock(layout).orElse(null)) {
            if (lock == null) {
                err.println("docgen-launcher: " + UserMessages.alreadyRunning().describe());
                return EXIT_FAILURE;
            }
            try (BackendLogManager logs = BackendLogManager.open(layout.logDirectory().path(), Clock.systemUTC());
                 BackendProcessManager backend = new BackendProcessManager(configuration, logs, new SecureRandom())) {
                backend.addListener(new BackendProcessManager.Listener() {
                    @Override
                    public void startupStep(BackendProcessManager.StartupStep step) {
                        out.println(switch (step) {
                            case PROCESS_STARTED -> "Backend iniciado; aguardando o anúncio autenticado...";
                            case PORT_ANNOUNCED -> "Porta anunciada; verificando a identidade por HTTP...";
                            case IDENTITY_VERIFIED -> "Identidade verificada (prova HMAC da sessão).";
                        });
                    }
                });
                long pid;
                try {
                    BackendEndpoint endpoint = backend.start();
                    pid = endpoint.pid();
                    out.println("DocGen pronto em " + endpoint.baseUri() + " (pid " + pid + ").");
                } catch (BackendStartupException failure) {
                    UserMessages.Notice notice = UserMessages.startup(failure, configuration.startupTimeout().toSeconds());
                    err.println("docgen-launcher: " + notice.title() + ". " + notice.describe());
                    logs.backendLogFile().ifPresent(file -> err.println("Logs: " + file));
                    return EXIT_FAILURE;
                }
                backend.stop();
                boolean gone = ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true);
                out.println(gone ? "Backend encerrado; nenhum processo filho permanece."
                        : "ATENÇÃO: o processo " + pid + " ainda está ativo.");
                logs.backendLogFile().ifPresent(file -> out.println("Logs: " + file));
                return gone ? EXIT_OK : EXIT_FAILURE;
            }
        } catch (IOException unusable) {
            err.println("docgen-launcher: não foi possível preparar os diretórios do DocGen ("
                    + unusable.getClass().getSimpleName() + ").");
            return EXIT_FAILURE;
        }
    }

    private static int desktop(LauncherOptions options, PrintStream err) {
        if (GraphicsEnvironment.isHeadless()) {
            err.println("docgen-launcher: nenhum ambiente gráfico disponível. Use --diagnose ou --check.");
            return EXIT_FAILURE;
        }
        if (System.getProperty("awt.useSystemAAFontSettings") == null) {
            System.setProperty("awt.useSystemAAFontSettings", "on");
        }
        PlatformDetector.Platform platform = PlatformDetector.current();
        RuntimeEnvironment runtime = RuntimeEnvironment.current(platform);
        InstallationLayout layout = InstallationLayout.current(platform, options.backendJar());
        Optional<SingleInstanceLock> lock;
        try {
            lock = lock(layout);
        } catch (IOException unusable) {
            showAndWait(UserMessages.lockUnavailable("O diretório de estado do DocGen não pode ser usado."));
            return EXIT_FAILURE;
        }
        if (lock.isEmpty()) {
            showAndWait(UserMessages.alreadyRunning());
            return EXIT_FAILURE;
        }
        SwingLauncherUi ui = new SwingLauncherUi();
        LauncherController controller = new LauncherController(
                () -> LauncherDiagnostics.run(platform, runtime, layout),
                layout,
                installed -> configuration(options, runtime, installed),
                ui,
                log -> BrowserOpener.system(platform, log),
                Clock.systemUTC(),
                System::exit,
                List.of(lock.get()));
        Runtime.getRuntime().addShutdownHook(new Thread(controller::emergencyStop, "docgen-launcher-exit"));
        try {
            SwingUtilities.invokeAndWait(() -> ui.attach(controller));
        } catch (InterruptedException | InvocationTargetException failure) {
            controller.emergencyStop();
            err.println("docgen-launcher: a janela não pôde ser aberta.");
            return EXIT_FAILURE;
        }
        controller.start();
        return RUNNING;
    }

    static BackendConfiguration configuration(LauncherOptions options, RuntimeEnvironment runtime, InstallationLayout layout) {
        Map<String, String> environment = System.getenv();
        int port = options.port() != null ? options.port() : BackendConfiguration.portFromEnvironment(environment);
        return BackendConfiguration.forInstallation(runtime, layout, environment, System.getProperty("user.name"),
                port, options.startupTimeout());
    }

    private static Optional<SingleInstanceLock> lock(InstallationLayout layout) throws IOException {
        if (!layout.stateDirectory().valid()) {
            throw new IOException("invalid state directory");
        }
        return SingleInstanceLock.acquire(layout.stateDirectory().path());
    }

    private static void showAndWait(UserMessages.Notice notice) {
        try {
            SwingUtilities.invokeAndWait(() -> LauncherWindow.message(null, notice));
        } catch (InterruptedException | InvocationTargetException ignored) {
            // Nothing else can be shown; the exit status reports the failure.
        }
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
