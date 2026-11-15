package com.docgen.launcher;

import com.docgen.desktop.DesktopMessage;

import java.io.IOException;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * The desktop flow: check the environment, start and verify the backend,
 * open the workspace on request, relay browser pairings, and shut down.
 *
 * <p>Long operations run on one control thread, never on the UI thread.
 * Behavior is explicit for every way the app can end or fail:</p>
 * <ul>
 *   <li>"Encerrar": stops the backend (graceful, then SIGTERM, then kill) and exits.</li>
 *   <li>Closing the window while the backend runs: asks for confirmation first.</li>
 *   <li>The launcher process being terminated: a shutdown hook stops the
 *   backend; if the launcher is killed outright, the backend sees its stdin
 *   close and exits on its own.</li>
 *   <li>A backend that fails or crashes: the window explains it and offers
 *   "Tentar novamente", which starts a new, freshly verified backend.</li>
 *   <li>A browser that cannot be opened: the window shows the address to
 *   open manually (it carries no credential).</li>
 *   <li>Opening DocGen again: a second launcher finds the instance lock held
 *   and only says that DocGen is already open.</li>
 * </ul>
 */
final class LauncherController implements LauncherPanel.Actions {
    private final Supplier<LauncherDiagnostics.Report> diagnostics;
    private final InstallationLayout layout;
    private final Function<InstallationLayout, BackendConfiguration> configurations;
    private final LauncherUi ui;
    private final Function<Consumer<String>, BrowserOpener> browsers;
    private final Clock clock;
    private final IntConsumer exit;
    private final List<AutoCloseable> resources;
    private final SecureRandom random = new SecureRandom();
    private final ExecutorService control = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("docgen-launcher-control").factory());

    private volatile LauncherViewState view = LauncherViewState.initial();
    private volatile BackendLogManager logs;
    private volatile BackendProcessManager backend;
    private volatile SessionPairingCoordinator pairing;
    private volatile BrowserOpener browser;
    private volatile long startupTimeoutSeconds = BackendConfiguration.DEFAULT_STARTUP_TIMEOUT.toSeconds();
    private volatile boolean shuttingDown;

    LauncherController(
            Supplier<LauncherDiagnostics.Report> diagnostics,
            InstallationLayout layout,
            Function<InstallationLayout, BackendConfiguration> configurations,
            LauncherUi ui,
            Function<Consumer<String>, BrowserOpener> browsers,
            Clock clock,
            IntConsumer exit,
            List<AutoCloseable> resources
    ) {
        this.diagnostics = diagnostics;
        this.layout = layout;
        this.configurations = configurations;
        this.ui = ui;
        this.browsers = browsers;
        this.clock = clock;
        this.exit = exit;
        this.resources = List.copyOf(resources);
    }

    LauncherViewState view() {
        return view;
    }

    Optional<BackendProcessManager> backend() {
        return Optional.ofNullable(backend);
    }

    /** Begins the startup sequence in the background. */
    void start() {
        control.execute(this::startup);
    }

    private void startup() {
        if (shuttingDown) {
            return;
        }
        BackendProcessManager current = backend;
        if (current != null && (current.state() == BackendLifecycleState.STARTING
                || current.state() == BackendLifecycleState.READY)) {
            return;
        }
        show(state -> LauncherViewState.initial());
        LauncherDiagnostics.Report report = diagnostics.get();
        Optional<LauncherDiagnostics.Check> failure = report.firstFailure();
        if (failure.isPresent()) {
            log("environment check failed: " + failure.get().id());
            show(state -> LauncherViewState.failed(LauncherViewState.Step.ENVIRONMENT,
                    UserMessages.diagnostics(failure.get())));
            return;
        }
        try {
            prepare();
        } catch (IOException | RuntimeException unusable) {
            show(state -> LauncherViewState.failed(LauncherViewState.Step.ENVIRONMENT,
                    UserMessages.lockUnavailable("Os diretórios do DocGen não puderam ser preparados.")));
            return;
        }
        show(state -> LauncherViewState.progress(LauncherViewState.Step.BACKEND, "Inicializando backend...",
                "Iniciando a API local do DocGen, acessível apenas por este computador (127.0.0.1)."));
        try {
            BackendEndpoint endpoint = backend.start();
            show(state -> LauncherViewState.ready(endpoint.workspaceUri().toString()));
        } catch (BackendStartupException startup) {
            if (startup.reason() != BackendStartupException.Reason.CANCELLED) {
                LauncherViewState.Step step = startup.reason() == BackendStartupException.Reason.HANDSHAKE_FAILED
                        ? LauncherViewState.Step.CONNECTION : LauncherViewState.Step.BACKEND;
                show(state -> LauncherViewState.failed(step, UserMessages.startup(startup, startupTimeoutSeconds)));
            }
        } catch (IllegalStateException alreadyRunning) {
            log("ignored a duplicate start request");
        }
    }

    /** Creates the log directory, logs and the backend manager once; later starts reuse them. */
    private void prepare() throws IOException {
        if (logs == null) {
            if (layout.stateDirectory().valid()) {
                SecureDirectories.createPrivate(layout.stateDirectory().path());
            }
            BackendLogManager opened;
            try {
                opened = BackendLogManager.open(layout.logDirectory().path(), clock);
            } catch (IOException | RuntimeException unwritable) {
                opened = BackendLogManager.inMemory(clock);
                opened.launcher("log directory unavailable; keeping logs in memory");
            }
            logs = opened;
            browser = browsers.apply(this::log);
        }
        if (backend == null) {
            BackendConfiguration configuration = configurations.apply(layout);
            startupTimeoutSeconds = configuration.startupTimeout().toSeconds();
            BackendProcessManager manager = new BackendProcessManager(configuration, logs, random);
            SessionPairingCoordinator coordinator = new SessionPairingCoordinator(manager::send, clock, logs);
            manager.addListener(coordinator);
            manager.addListener(new BackendProcessManager.Listener() {
                @Override
                public void stateChanged(BackendLifecycleState lifecycle, BackendStartupException failure) {
                    if (lifecycle == BackendLifecycleState.FAILED && failure != null
                            && failure.reason() == BackendStartupException.Reason.CRASHED) {
                        show(current -> LauncherViewState.failed(LauncherViewState.Step.READY,
                                UserMessages.startup(failure, startupTimeoutSeconds)));
                    }
                }

                @Override
                public void startupStep(BackendProcessManager.StartupStep step) {
                    if (step == BackendProcessManager.StartupStep.PORT_ANNOUNCED) {
                        show(state -> LauncherViewState.progress(LauncherViewState.Step.CONNECTION, "Verificando conexão...",
                                "Confirmando que a API que respondeu é o processo iniciado por este aplicativo."));
                    }
                }

                @Override
                public void event(DesktopMessage event) {
                    // Pairing events are handled by the coordinator.
                }
            });
            coordinator.addListener(new SessionPairingCoordinator.Listener() {
                @Override
                public void pairingRequested(SessionPairingCoordinator.PairingRequest request) {
                    show(state -> state.withPairing(new LauncherViewState.Pairing(request.pairingId(), request.code())));
                }

                @Override
                public void pairingClosed(String pairingId, String reason) {
                    show(state -> {
                        LauncherViewState cleared = state.pairing() != null && state.pairing().pairingId().equals(pairingId)
                                ? state.withPairing(null) : state;
                        return switch (reason) {
                            case "replaced", "stopped" -> cleared;
                            case "completed" -> cleared.withNotice(LauncherViewState.Tone.SUCCESS,
                                    UserMessages.pairingClosed(reason));
                            default -> cleared.withNotice(LauncherViewState.Tone.WARNING, UserMessages.pairingClosed(reason));
                        };
                    });
                }
            });
            pairing = coordinator;
            backend = manager;
        }
    }

    @Override
    public void openWorkspace() {
        control.execute(() -> {
            BackendProcessManager current = backend;
            Optional<BackendEndpoint> endpoint = current == null ? Optional.empty() : current.endpoint();
            if (endpoint.isEmpty() || shuttingDown) {
                return;
            }
            if (!pairing.arm()) {
                show(state -> state.withNotice(LauncherViewState.Tone.WARNING, UserMessages.armFailed()));
                return;
            }
            URI address = endpoint.get().workspaceUri();
            boolean opened = browser.open(address);
            log(opened ? "workspace opened in the browser" : "the browser could not be opened");
            show(state -> opened
                    ? state.withNotice(LauncherViewState.Tone.SUCCESS, UserMessages.browserOpened())
                    : state.withNotice(LauncherViewState.Tone.WARNING, UserMessages.browserFailed(address.toString())));
        });
    }

    @Override
    public void approvePairing(String pairingId) {
        control.execute(() -> {
            if (!pairing.approve(pairingId)) {
                show(state -> state.withPairing(null).withNotice(LauncherViewState.Tone.WARNING,
                        UserMessages.pairingClosed("expired")));
            }
        });
    }

    @Override
    public void denyPairing(String pairingId) {
        control.execute(() -> pairing.deny(pairingId));
    }

    @Override
    public void retry() {
        start();
    }

    @Override
    public void showLogs() {
        BackendLogManager current = logs;
        ui.showLogs(current == null ? List.of() : current.recentBackendLines(),
                current == null ? null : current.directory().orElse(null));
    }

    @Override
    public void copyAddress(String address) {
        ui.copyToClipboard(address);
    }

    /** "Encerrar": an explicit request, no confirmation needed. */
    @Override
    public void quit() {
        shutdown();
    }

    /** The window's close button: confirm while the backend is running. Called on the UI thread. */
    void closeRequested() {
        BackendProcessManager current = backend;
        boolean running = current != null && (current.state() == BackendLifecycleState.READY
                || current.state() == BackendLifecycleState.STARTING);
        if (running && !ui.confirmQuit()) {
            return;
        }
        shutdown();
    }

    /**
     * Stops everything this launcher owns and exits. Runs on its own thread so
     * that a startup in progress on the control thread is cancelled, not awaited.
     */
    void shutdown() {
        synchronized (this) {
            if (shuttingDown) {
                return;
            }
            shuttingDown = true;
        }
        view = LauncherViewState.stopping();
        ui.render(view);
        Thread.ofPlatform().name("docgen-launcher-shutdown").start(() -> {
            int status = 0;
            try {
                releaseAll();
            } catch (RuntimeException failure) {
                status = 1;
            } finally {
                control.shutdownNow();
                ui.close();
                exit.accept(status);
            }
        });
    }

    /** For the JVM shutdown hook: stop the backend even if the window never got to. */
    void emergencyStop() {
        BackendProcessManager current = backend;
        if (current != null) {
            current.stop();
        }
    }

    private void releaseAll() {
        try {
            BackendProcessManager current = backend;
            if (current != null) {
                current.stop();
            }
        } finally {
            log("launcher exiting");
            BackendLogManager current = logs;
            if (current != null) {
                current.close();
            }
            for (AutoCloseable resource : resources) {
                try {
                    resource.close();
                } catch (Exception ignored) {
                    // Every resource is released by the OS at exit anyway.
                }
            }
        }
    }

    private void log(String message) {
        BackendLogManager current = logs;
        if (current != null) {
            current.launcher(message);
        }
    }

    /** Serializes view updates from the control, output and exit threads, and drops them once shutting down. */
    private synchronized void show(UnaryOperator<LauncherViewState> change) {
        if (shuttingDown) {
            return;
        }
        view = change.apply(view);
        ui.render(view);
    }
}
