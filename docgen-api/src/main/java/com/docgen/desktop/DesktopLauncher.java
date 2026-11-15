package com.docgen.desktop;

import com.docgen.api.DesktopControl;
import com.docgen.api.DocGenApiApplication;
import com.docgen.api.LaunchTokenService;
import com.docgen.api.LocalDatabase;
import com.docgen.api.PrivateFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

/**
 * The DocGen desktop app: one process that is the server and opens the UI in
 * the browser (ADR 0001). Runs before Spring:
 *
 * <ol>
 *   <li>resolves the per-OS data directory and creates it privately;</li>
 *   <li>takes the single-instance lock; if another DocGen of this user holds it, asks that one to
 *   open a window (through {@code instance.json} and the reopen route) and ends;</li>
 *   <li>otherwise starts the server on 127.0.0.1 on a free port chosen by the OS, writes
 *   {@code instance.json} once the server is ready, and opens {@code /launch#<token>} with a fresh
 *   single-use launch token (ADR 0002).</li>
 * </ol>
 *
 * <p>The app stops through "Sair do DocGen", the tray icon, the idle shutdown or an OS signal.</p>
 */
public final class DesktopLauncher {
    private static final Logger LOG = LoggerFactory.getLogger(DesktopLauncher.class);
    private static final Duration SECOND_START_WAIT = Duration.ofSeconds(3);
    private static final Duration POLL = Duration.ofMillis(250);
    /** Lets the HTTP response of "Sair do DocGen" reach the browser before the server stops. */
    private static final Duration SHUTDOWN_GRACE = Duration.ofMillis(300);

    private DesktopLauncher() {
    }

    public static void main(String[] args) {
        Platform platform = Platform.current();
        Map<String, String> environment = System.getenv();
        Path home = Path.of(System.getProperty("user.home"));
        DesktopNotices notices = DesktopNotices.system();
        Path dataDirectory;
        try {
            dataDirectory = AppDirectories.dataDirectory(platform, environment, home);
        } catch (IllegalStateException invalid) {
            System.err.println("DocGen: " + invalid.getMessage());
            System.exit(2);
            return;
        }
        Settings settings = new Settings(dataDirectory, SystemBrowsers.opener(platform, environment, home), notices,
                true, true, true, System::exit, args, SECOND_START_WAIT);
        Outcome outcome = start(settings);
        switch (outcome) {
            case Outcome.Running running -> LOG.info("DocGen is running on port {}", running.instance().port());
            case Outcome.Reopened reopened -> System.exit(0);
            case Outcome.AlreadyStarting starting -> System.exit(0);
            case Outcome.Failed failed -> System.exit(1);
        }
    }

    /**
     * @param fileLogging       write the rotated log file in {@code <data>/logs}
     * @param tray              install the optional tray icon
     * @param shutdownHook      clean up on OS signals (the real process only)
     * @param exit              ends the process after a shutdown ({@code System::exit} in the app)
     * @param secondStartWait   how long a second start waits for the first one to answer
     */
    record Settings(
            Path dataDirectory,
            BrowserOpener browser,
            DesktopNotices notices,
            boolean fileLogging,
            boolean tray,
            boolean shutdownHook,
            IntConsumer exit,
            String[] arguments,
            Duration secondStartWait
    ) {
    }

    sealed interface Outcome {
        /** This process holds the lock and serves the UI. */
        record Running(Instance instance) implements Outcome {
        }

        /** Another DocGen was running and opened a new window. */
        record Reopened() implements Outcome {
        }

        /** Another DocGen is still starting and will open its own window. */
        record AlreadyStarting() implements Outcome {
        }

        record Failed() implements Outcome {
        }
    }

    static Outcome start(Settings settings) {
        Path data = settings.dataDirectory();
        try {
            PrivateFiles.createDirectory(data);
            Instant deadline = Instant.now().plus(settings.secondStartWait());
            while (true) {
                Optional<InstanceLock> lock = InstanceLock.tryAcquire(data);
                if (lock.isPresent()) {
                    return startPrimary(settings, lock.get());
                }
                // instance.json exists only while a started instance serves; a dead PID means a crash left it.
                Optional<InstanceFile> running = InstanceFile.read(data).filter(InstanceFile::processAlive);
                if (running.isPresent() && ReopenClient.reopen(running.get())) {
                    return new Outcome.Reopened();
                }
                if (Instant.now().isAfter(deadline)) {
                    if (running.isPresent()) {
                        settings.notices().alreadyRunningButUnresponsive();
                        return new Outcome.Failed();
                    }
                    return new Outcome.AlreadyStarting();
                }
                Thread.sleep(POLL.toMillis());
            }
        } catch (IOException failure) {
            LOG.error("The data directory is not usable", failure);
            settings.notices().startupFailed(AppDirectories.logDirectory(data));
            return new Outcome.Failed();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Outcome.Failed();
        }
    }

    private static Outcome startPrimary(Settings settings, InstanceLock lock) {
        Path data = settings.dataDirectory();
        Instance instance = new Instance(settings, lock);
        try {
            // Only the lock holder touches these: a stale instance file and a compaction left by a killed run.
            InstanceFile.delete(data);
            LocalDatabase.compactIfRequested(data);
            instance.startServer();
            new InstanceFile(instance.port(), ProcessHandle.current().pid(), instance.secret).write(data);
            if (settings.shutdownHook()) {
                Runtime.getRuntime().addShutdownHook(new Thread(instance::releaseOnSignal, "docgen-signal"));
            }
            if (settings.tray()) {
                instance.tray = DesktopTray.install(instance::openWindow, () -> instance.requestShutdown("tray"));
            }
            instance.openWindow();
            return new Outcome.Running(instance);
        } catch (IOException | SQLException | RuntimeException failure) {
            LOG.error("DocGen could not start", failure);
            instance.abort();
            settings.notices().startupFailed(AppDirectories.logDirectory(data));
            return new Outcome.Failed();
        }
    }

    /** The running app; the API reaches it through {@link DesktopControl}. */
    static final class Instance implements DesktopControl {
        private final Settings settings;
        private final InstanceLock lock;
        private final String secret = randomSecret();
        private final ExecutorService windows = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "docgen-window");
            thread.setDaemon(true);
            return thread;
        });
        private final AtomicBoolean stopping = new AtomicBoolean();
        private final CountDownLatch stopped = new CountDownLatch(1);
        private volatile ConfigurableApplicationContext context;
        private volatile LaunchTokenService launchTokens;
        private volatile int port;
        private volatile Optional<DesktopTray> tray = Optional.empty();
        private volatile Thread shutdownThread;

        private Instance(Settings settings, InstanceLock lock) {
            this.settings = settings;
            this.lock = lock;
        }

        private void startServer() throws IOException {
            Path data = settings.dataDirectory();
            SpringApplication application = new SpringApplication(DocGenApiApplication.class);
            // Spring Boot would force headless mode; keep what the session really is (dialogs, tray, browser).
            application.setHeadless(GraphicsEnvironment.isHeadless());
            if (settings.fileLogging()) {
                Path logs = PrivateFiles.createDirectory(AppDirectories.logDirectory(data));
                application.setDefaultProperties(Map.of(
                        "logging.file.name", logs.resolve("docgen.log").toString(),
                        "logging.logback.rollingpolicy.max-file-size", "5MB",
                        "logging.logback.rollingpolicy.max-history", "7",
                        "logging.logback.rollingpolicy.total-size-cap", "50MB"));
            }
            application.addInitializers(starting -> {
                // Highest precedence: the database must live where the lock is.
                starting.getEnvironment().getPropertySources().addFirst(new MapPropertySource("docgenDesktop", Map.of(
                        "docgen.storage.data-directory", data.toString(),
                        "docgen.storage.in-memory", "false")));
                starting.getBeanFactory().registerSingleton("desktopControl", this);
            });
            context = application.run(settings.arguments());
            port = ((WebServerApplicationContext) context).getWebServer().getPort();
            launchTokens = context.getBean(LaunchTokenService.class);
        }

        int port() {
            return port;
        }

        /** A new single-use launch address: {@code http://127.0.0.1:<port>/launch#<token>}. */
        URI launchAddress() {
            try {
                return new URI("http", null, ReopenClient.HOST, port, "/launch", null, launchTokens.issue());
            } catch (URISyntaxException impossible) {
                throw new IllegalStateException("Invalid launch address", impossible);
            }
        }

        @Override
        public boolean acceptsInstanceSecret(String candidate) {
            return candidate != null && MessageDigest.isEqual(
                    candidate.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void openWindow() {
            if (stopping.get()) {
                return;
            }
            windows.execute(() -> {
                if (!settings.browser().open(launchAddress())) {
                    LOG.warn("No browser could be opened");
                    settings.notices().browserUnavailable(() -> launchAddress().toString());
                }
            });
        }

        @Override
        public void requestShutdown(String reason) {
            if (!stopping.compareAndSet(false, true)) {
                return;
            }
            LOG.info("Stopping DocGen ({})", reason);
            Thread stopper = new Thread(() -> {
                try {
                    Thread.sleep(SHUTDOWN_GRACE.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                stop();
                settings.exit().accept(0);
            }, "docgen-shutdown");
            // Requests come from Tomcat's daemon threads, and a thread inherits that flag: a daemon stopper
            // would let the JVM exit as soon as Tomcat stops, before the instance file and the lock are cleaned.
            stopper.setDaemon(false);
            stopper.start();
            shutdownThread = stopper;
        }

        /** The thread that stops the app once a shutdown was requested; for tests. */
        Optional<Thread> shutdownThread() {
            return Optional.ofNullable(shutdownThread);
        }

        /** Waits until a requested shutdown has finished; for tests. */
        boolean awaitStopped(Duration timeout) throws InterruptedException {
            return stopped.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void stop() {
            try {
                tray.ifPresent(DesktopTray::remove);
                windows.shutdownNow();
                ConfigurableApplicationContext running = context;
                if (running != null) {
                    SpringApplication.exit(running);
                }
                InstanceFile.delete(settings.dataDirectory());
                // The pool is closed now, so nothing else has the database open.
                if (LocalDatabase.compactIfRequested(settings.dataDirectory())) {
                    LOG.info("The database file was compacted after a data erasure");
                }
            } catch (IOException | SQLException | RuntimeException failure) {
                LOG.warn("DocGen did not stop cleanly", failure);
            } finally {
                lock.close();
                stopped.countDown();
            }
        }

        /** OS signal or logout: Spring closes its context in its own hook; a pending compaction waits for the next start. */
        private void releaseOnSignal() {
            if (!stopping.compareAndSet(false, true)) {
                return;
            }
            try {
                InstanceFile.delete(settings.dataDirectory());
            } catch (IOException ignored) {
                // A stale file is recognized by its dead PID and replaced at the next start.
            }
            lock.close();
        }

        private void abort() {
            stopping.set(true);
            windows.shutdownNow();
            try {
                if (context != null) {
                    context.close();
                }
                InstanceFile.delete(settings.dataDirectory());
            } catch (IOException | RuntimeException cleanup) {
                LOG.warn("Cleanup after a failed start was incomplete", cleanup);
            } finally {
                lock.close();
            }
        }

        private static String randomSecret() {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }
    }
}
