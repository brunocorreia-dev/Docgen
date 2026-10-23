package com.docgen.launcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The complete desktop flow against simulated backends, with a recording UI instead of a window. */
class LauncherControllerTest {
    private static final PlatformDetector.Platform LINUX = PlatformDetector.detect("Linux", "amd64");
    private static final Duration WAIT = Duration.ofSeconds(20);

    @TempDir
    Path temp;

    private final RecordingUi ui = new RecordingUi();
    private final List<URI> opened = new CopyOnWriteArrayList<>();
    private final CompletableFuture<Integer> exitCode = new CompletableFuture<>();
    private final AtomicBoolean resourceClosed = new AtomicBoolean();
    private boolean browserWorks = true;
    private LauncherController controller;

    @AfterEach
    void stop() {
        if (controller != null) {
            controller.emergencyStop();
        }
    }

    private LauncherController controller(String mode, Supplier<LauncherDiagnostics.Report> diagnostics) throws IOException {
        Path backendJar = LauncherDiagnosticsTest.backendJar(temp.resolve("app/backend/docgen-api.jar"), BackendArtifact.START_CLASS);
        InstallationLayout layout = InstallationLayout.resolve(LINUX, Map.of(
                        "XDG_DATA_HOME", temp.resolve("data").toString(), "XDG_STATE_HOME", temp.resolve("state").toString()),
                temp.resolve("home"), temp.resolve("app/docgen-launcher.jar"), backendJar, null);
        Duration timeout = mode.equals("silent") ? Duration.ofSeconds(60) : Duration.ofSeconds(20);
        controller = new LauncherController(diagnostics, layout,
                installed -> new BackendConfiguration(FakeBackend.command(mode), new HashMap<>(System.getenv()),
                        temp, 0, timeout, Duration.ofSeconds(3), Duration.ofSeconds(2), Duration.ofSeconds(5)),
                ui,
                log -> address -> {
                    opened.add(address);
                    return browserWorks;
                },
                Clock.systemUTC(),
                exitCode::complete,
                List.of(() -> resourceClosed.set(true)));
        return controller;
    }

    private LauncherController controller(String mode) throws IOException {
        return controller(mode, () -> new LauncherDiagnostics.Report(List.of()));
    }

    private LauncherViewState await(Predicate<LauncherViewState> condition) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            for (LauncherViewState state : ui.states) {
                if (condition.test(state)) {
                    return state;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no matching state; last: " + (ui.states.isEmpty() ? null : ui.states.getLast()));
    }

    private static boolean ready(LauncherViewState state) {
        return state.canOpen() && "DocGen pronto!".equals(state.headline());
    }

    @Test
    void startupShowsEachRealStepThenReady() throws Exception {
        controller("ready").start();

        LauncherViewState ready = await(LauncherControllerTest::ready);

        assertTrue(ready.address().matches("http://127\\.0\\.0\\.1:\\d+/#/desktop"));
        List<String> headlines = ui.states.stream().map(LauncherViewState::headline).distinct().toList();
        assertEquals(List.of("Verificando ambiente...", "Inicializando backend...", "Verificando conexão...", "DocGen pronto!"),
                headlines);
    }

    @Test
    void aFailedEnvironmentCheckNeverStartsTheBackend() throws Exception {
        LauncherDiagnostics.Check runtime = new LauncherDiagnostics.Check("runtime", "Runtime Java",
                LauncherDiagnostics.Status.FAILURE, LauncherDiagnostics.Category.RUNTIME, "Java 17");
        controller("ready", () -> new LauncherDiagnostics.Report(List.of(runtime))).start();

        LauncherViewState failed = await(state -> state.canRetry());

        assertEquals("Runtime Java incompatível", failed.headline());
        assertEquals(LauncherViewState.StepStatus.FAILED, failed.steps().get(LauncherViewState.Step.ENVIRONMENT));
        assertTrue(controller.backend().isEmpty(), "no process was started");
    }

    @Test
    void openingArmsOnePairingOpensTheBrowserAndTheApprovalCompletesIt() throws Exception {
        controller("ready").start();
        LauncherViewState ready = await(LauncherControllerTest::ready);

        controller.openWorkspace();
        LauncherViewState asking = await(state -> state.pairing() != null);

        assertEquals(List.of(URI.create(ready.address())), opened);
        assertEquals(FakeBackend.PAIRING_CODE, asking.pairing().code());
        controller.approvePairing(asking.pairing().pairingId());
        LauncherViewState connected = await(state -> "Navegador conectado".equals(state.headline()));
        assertEquals(null, connected.pairing());
        assertTrue(connected.canOpen());
    }

    @Test
    void refusingClearsTheRequest() throws Exception {
        controller("ready").start();
        await(LauncherControllerTest::ready);
        controller.openWorkspace();
        LauncherViewState asking = await(state -> state.pairing() != null);

        controller.denyPairing(asking.pairing().pairingId());

        LauncherViewState refused = await(state -> "Acesso recusado".equals(state.headline()));
        assertEquals(null, refused.pairing());
    }

    @Test
    void aBrowserThatCannotOpenLeavesTheAddressToCopy() throws Exception {
        browserWorks = false;
        controller("ready").start();
        LauncherViewState ready = await(LauncherControllerTest::ready);

        controller.openWorkspace();

        LauncherViewState notice = await(state -> "Não foi possível abrir o navegador".equals(state.headline()));
        assertTrue(notice.detail().contains(ready.address()));
        assertTrue(notice.canOpen());
        controller.copyAddress(ready.address());
        assertEquals(ready.address(), ui.clipboard);
    }

    @Test
    void explainsStartupFailuresAndHandshakeFailures() throws Exception {
        controller("exit:1:Web server failed to start. Port 8080 was already in use.").start();
        assertEquals("Porta ocupada", await(LauncherViewState::canRetry).headline());
    }

    @Test
    void anIdentityFailureIsReportedAtTheConnectionStep() throws Exception {
        controller("wrong-proof").start();
        LauncherViewState failed = await(LauncherViewState::canRetry);
        assertEquals("Falha na verificação de segurança", failed.headline());
        assertEquals(LauncherViewState.StepStatus.FAILED, failed.steps().get(LauncherViewState.Step.CONNECTION));
    }

    @Test
    void aCrashOffersRetryAndRetryStartsAFreshBackend() throws Exception {
        controller("crash-after-ready").start();
        await(LauncherControllerTest::ready);
        LauncherViewState crashed = await(state -> "O backend foi encerrado inesperadamente".equals(state.headline()));
        assertTrue(crashed.canRetry());

        int readyBefore = (int) ui.states.stream().filter(LauncherControllerTest::ready).count();
        controller.retry();
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (ui.states.stream().filter(LauncherControllerTest::ready).count() <= readyBefore && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(ui.states.stream().filter(LauncherControllerTest::ready).count() > readyBefore, "ready again after retry");
    }

    @Test
    void quittingStopsTheBackendReleasesResourcesAndExits() throws Exception {
        controller("ready").start();
        await(LauncherControllerTest::ready);
        long pid = controller.backend().orElseThrow().endpoint().orElseThrow().pid();

        controller.quit();

        assertEquals(0, exitCode.get(20, TimeUnit.SECONDS));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        assertTrue(resourceClosed.get());
        assertTrue(ui.closed);
        assertEquals("Encerrando o DocGen...", ui.states.getLast().headline());
    }

    @Test
    void closingTheWindowAsksForConfirmationWhileRunning() throws Exception {
        controller("ready").start();
        await(LauncherControllerTest::ready);

        ui.confirm = false;
        controller.closeRequested();
        Thread.sleep(300);
        assertFalse(exitCode.isDone(), "cancelling keeps DocGen running");
        assertEquals(1, ui.confirmations);

        ui.confirm = true;
        controller.closeRequested();
        assertEquals(0, exitCode.get(20, TimeUnit.SECONDS));
    }

    @Test
    void quittingDuringStartupCancelsInsteadOfWaiting() throws Exception {
        controller("silent").start();
        await(state -> "Inicializando backend...".equals(state.headline()));
        Thread.sleep(500);

        long started = System.nanoTime();
        controller.quit();

        assertEquals(0, exitCode.get(20, TimeUnit.SECONDS));
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 15);
    }

    @Test
    void showsRedactedRecentLogs() throws Exception {
        controller("ready").start();
        await(LauncherControllerTest::ready);
        controller.showLogs();
        assertNotNull(ui.logDirectory);
        assertTrue(ui.logLines.contains("fake backend starting"));
    }

    static final class RecordingUi implements LauncherUi {
        final List<LauncherViewState> states = new CopyOnWriteArrayList<>();
        volatile boolean confirm = true;
        volatile int confirmations;
        volatile boolean closed;
        volatile String clipboard;
        volatile List<String> logLines;
        volatile Path logDirectory;

        @Override
        public void render(LauncherViewState state) {
            states.add(state);
        }

        @Override
        public boolean confirmQuit() {
            confirmations++;
            return confirm;
        }

        @Override
        public void showLogs(List<String> recentLines, Path directory) {
            logLines = recentLines;
            logDirectory = directory;
        }

        @Override
        public void showMessage(UserMessages.Notice notice) {
            states.add(LauncherViewState.initial().withNotice(LauncherViewState.Tone.WARNING, notice));
        }

        @Override
        public void copyToClipboard(String text) {
            clipboard = text;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
