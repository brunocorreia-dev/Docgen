package com.docgen.launcher;

import com.docgen.desktop.DesktopMessage;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendProcessManagerTest {
    private static final SecureRandom RANDOM = new SecureRandom();

    @TempDir
    Path temp;

    private final List<BackendProcessManager> managers = new ArrayList<>();
    private final List<HttpServer> foreignServers = new ArrayList<>();
    private BackendLogManager logs;

    @AfterEach
    void cleanUp() {
        managers.forEach(BackendProcessManager::stop);
        foreignServers.forEach(server -> server.stop(0));
        if (logs != null) {
            logs.close();
        }
    }

    private BackendProcessManager manager(List<String> command, int port, Duration startupTimeout) throws IOException {
        if (logs == null) {
            logs = BackendLogManager.open(temp.resolve("logs"), Clock.systemUTC());
        }
        BackendConfiguration configuration = new BackendConfiguration(command, new HashMap<>(System.getenv()), temp,
                port, startupTimeout, Duration.ofSeconds(3), Duration.ofSeconds(2), Duration.ofSeconds(5));
        BackendProcessManager manager = new BackendProcessManager(configuration, logs, RANDOM);
        managers.add(manager);
        return manager;
    }

    private BackendProcessManager manager(String mode) throws IOException {
        return manager(FakeBackend.command(mode), 0, Duration.ofSeconds(20));
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static void awaitDeath(long pid, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (alive(pid) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
    }

    private String launcherLog() throws IOException {
        return Files.readString(logs.launcherLogFile().orElseThrow());
    }

    @Test
    void startsVerifiesAndStopsALegitimateBackend() throws Exception {
        BackendProcessManager manager = manager("ready");
        List<BackendLifecycleState> states = new CopyOnWriteArrayList<>();
        List<BackendProcessManager.StartupStep> steps = new CopyOnWriteArrayList<>();
        manager.addListener(new BackendProcessManager.Listener() {
            @Override
            public void stateChanged(BackendLifecycleState state, BackendStartupException failure) {
                states.add(state);
            }

            @Override
            public void startupStep(BackendProcessManager.StartupStep step) {
                steps.add(step);
            }
        });

        BackendEndpoint endpoint = manager.start();

        assertEquals(BackendLifecycleState.READY, manager.state());
        assertTrue(endpoint.port() > 0);
        assertTrue(alive(endpoint.pid()));
        assertEquals(URI.create("http://127.0.0.1:" + endpoint.port() + "/#/desktop"), endpoint.workspaceUri());
        assertEquals(List.of(BackendProcessManager.StartupStep.PROCESS_STARTED, BackendProcessManager.StartupStep.PORT_ANNOUNCED,
                BackendProcessManager.StartupStep.IDENTITY_VERIFIED), steps);

        manager.stop();

        assertEquals(BackendLifecycleState.STOPPED, manager.state());
        assertFalse(alive(endpoint.pid()), "the child process must be gone after stop()");
        assertEquals(List.of(BackendLifecycleState.STARTING, BackendLifecycleState.READY, BackendLifecycleState.STOPPING,
                BackendLifecycleState.STOPPED), states);
        assertTrue(logs.recentBackendLines().contains("lifeline closed; exiting"), "graceful exit through the lifeline");
        assertFalse(launcherLog().contains("SIGTERM"));
    }

    @Test
    void refusesADuplicateStartWhileRunning() throws Exception {
        BackendProcessManager manager = manager("ready");
        BackendEndpoint endpoint = manager.start();

        assertThrows(IllegalStateException.class, manager::start);
        assertEquals(BackendLifecycleState.READY, manager.state());
        assertEquals(endpoint, manager.endpoint().orElseThrow());
    }

    @Test
    void classifiesBackendsThatExitDuringStartup() throws Exception {
        Map<String, BackendStartupException.Reason> cases = Map.of(
                "exit:1:Web server failed to start. Port 8080 was already in use.", BackendStartupException.Reason.PORT_IN_USE,
                "exit:1:org.h2.jdbc.JdbcSQLNonTransientConnectionException: Database may be already in use", BackendStartupException.Reason.DATA_IN_USE,
                "exit:1:java.lang.UnsupportedClassVersionError: class file version 70.0", BackendStartupException.Reason.INCOMPATIBLE_RUNTIME,
                "exit:1:java.nio.file.AccessDeniedException: /data", BackendStartupException.Reason.PERMISSION_DENIED,
                "exit:7:unexpected failure", BackendStartupException.Reason.EXITED);
        for (Map.Entry<String, BackendStartupException.Reason> entry : cases.entrySet()) {
            logs = null;
            temp = Files.createTempDirectory(temp, "case");
            BackendProcessManager manager = manager(entry.getKey());
            BackendStartupException failure = assertThrows(BackendStartupException.class, manager::start, entry.getKey());
            assertEquals(entry.getValue(), failure.reason(), entry.getKey());
            assertNotNull(failure.exitCode());
            assertEquals(BackendLifecycleState.FAILED, manager.state());
            assertEquals(failure, manager.failure().orElseThrow());
            logs.close();
        }
        logs = null;
    }

    @Test
    void timesOutAndTerminatesABackendThatNeverBecomesReady() throws Exception {
        BackendProcessManager manager = manager(FakeBackend.command("silent"), 0, Duration.ofMillis(1_500));
        BackendStartupException failure = assertThrows(BackendStartupException.class, manager::start);

        assertEquals(BackendStartupException.Reason.TIMEOUT, failure.reason());
        assertEquals(BackendLifecycleState.FAILED, manager.state());
        assertTrue(launcherLog().contains("backend stopped with exit code"));
    }

    @Test
    void rejectsReadinessProofsMadeWithAnotherSecret() throws Exception {
        assertHandshakeFails("wrong-proof");
    }

    @Test
    void rejectsJsonThatOnlyLooksLikeAProof() throws Exception {
        assertHandshakeFails("forged-json");
        assertHandshakeFails("health-only");
    }

    @Test
    void rejectsAnnouncementsForAnotherProcess() throws Exception {
        assertHandshakeFails("wrong-pid");
    }

    private void assertHandshakeFails(String mode) throws Exception {
        BackendProcessManager manager = manager(mode);
        BackendStartupException failure = assertThrows(BackendStartupException.class, manager::start, mode);
        assertEquals(BackendStartupException.Reason.HANDSHAKE_FAILED, failure.reason(), mode);
        assertEquals(BackendLifecycleState.FAILED, manager.state());
        assertTrue(manager.endpoint().isEmpty());
    }

    @Test
    void aForeignServiceOnTheAnnouncedPortFailsTheHandshakeAndIsLeftRunning() throws Exception {
        HttpServer foreign = foreignService();
        int port = foreign.getAddress().getPort();

        BackendProcessManager manager = manager("foreign:" + port);
        BackendStartupException failure = assertThrows(BackendStartupException.class, manager::start);

        assertEquals(BackendStartupException.Reason.HANDSHAKE_FAILED, failure.reason());
        assertEquals(200, get(port).statusCode(), "the launcher must never stop a service it did not start");
    }

    @Test
    void aFixedPortThatAlreadyAnswersIsNeverMistakenForDocGen() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            Path marker = temp.resolve("started");
            // If the launcher ran anything, this command would fail differently (LAUNCH_FAILED).
            BackendProcessManager manager = manager(List.of(temp.resolve("must-not-run").toString(), marker.toString()),
                    occupied.getLocalPort(), Duration.ofSeconds(5));

            BackendStartupException failure = assertThrows(BackendStartupException.class, manager::start);

            assertEquals(BackendStartupException.Reason.PORT_IN_USE, failure.reason());
            assertFalse(Files.exists(marker));
            assertFalse(occupied.isClosed());
        }
    }

    @Test
    void ignoresProtocolLinesWithoutAValidMac() throws Exception {
        BackendProcessManager manager = manager(FakeBackend.command("bad-mac"), 0, Duration.ofMillis(2_500));
        BackendStartupException failure = assertThrows(BackendStartupException.class, manager::start);

        assertEquals(BackendStartupException.Reason.TIMEOUT, failure.reason());
        assertTrue(launcherLog().contains("ignored an unauthenticated protocol line"));
        assertFalse(String.join("\n", logs.recentBackendLines()).contains("@@docgen-desktop"));
    }

    @Test
    void sendsSigtermWhenTheLifelineIsIgnored() throws Exception {
        BackendProcessManager manager = manager("ignore-eof");
        long pid = manager.start().pid();

        manager.stop();

        assertFalse(alive(pid));
        assertTrue(launcherLog().contains("sending SIGTERM"));
        assertFalse(launcherLog().contains("forcing termination"));
    }

    @Test
    void forcesTerminationOfABackendThatHangsOnSigterm() throws Exception {
        BackendProcessManager manager = manager("stubborn");
        long pid = manager.start().pid();

        long started = System.nanoTime();
        manager.stop();

        assertFalse(alive(pid));
        assertTrue(launcherLog().contains("forcing termination"));
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 20);
        assertEquals(BackendLifecycleState.STOPPED, manager.state());
    }

    @Test
    void reportsABackendThatExitsOnItsOwnAndCanBeRestarted() throws Exception {
        BackendProcessManager manager = manager("crash-after-ready");
        CompletableFuture<BackendStartupException> crash = new CompletableFuture<>();
        manager.addListener(new BackendProcessManager.Listener() {
            @Override
            public void stateChanged(BackendLifecycleState state, BackendStartupException failure) {
                if (state == BackendLifecycleState.FAILED) {
                    crash.complete(failure);
                }
            }
        });
        manager.start();

        BackendStartupException failure = crash.get(10, TimeUnit.SECONDS);
        assertEquals(BackendStartupException.Reason.CRASHED, failure.reason());
        assertEquals(3, failure.exitCode());
        assertEquals(BackendLifecycleState.FAILED, manager.state());

        BackendEndpoint restarted = manager.start();
        assertTrue(alive(restarted.pid()));
    }

    @Test
    void deliversAuthenticatedCommandsAndEvents() throws Exception {
        BackendProcessManager manager = manager("ready");
        BlockingQueue<DesktopMessage> events = new LinkedBlockingQueue<>();
        manager.addListener(new BackendProcessManager.Listener() {
            @Override
            public void event(DesktopMessage event) {
                events.add(event);
            }
        });
        assertFalse(manager.send("ARM"), "nothing is sent before the backend is verified");
        manager.start();

        assertTrue(manager.send("ARM"));
        DesktopMessage ack = events.poll(5, TimeUnit.SECONDS);
        assertNotNull(ack);
        assertEquals("ACK", ack.type());
        assertEquals(List.of("ARM"), ack.fields());
        assertFalse(logs.recentBackendLines().contains("rejected command"));
    }

    @Test
    void neverWritesTheSessionSecretOrTokensToTheLogs() throws Exception {
        BackendProcessManager manager = manager("echo-secret");
        manager.start();
        manager.stop();

        String backendLog = Files.readString(logs.backendLogFile().orElseThrow());
        String leaked = logs.recentBackendLines().stream().filter(line -> line.startsWith("leaked")).findFirst().orElseThrow();
        assertTrue(leaked.contains(LogRedactor.MASK));
        assertFalse(leaked.contains("abcdefghijklmnopqrstuvwxyz"));
        Matcher secret = Pattern.compile("docgen-desktop/1 bootstrap \\S+ (\\S{43})").matcher(backendLog);
        assertFalse(secret.find(), "the bootstrap secret reached the log file");
        assertFalse(backendLog.contains("abcdefghijklmnopqrstuvwxyz"));
    }

    @Test
    void reportsCommandsThatCannotBeExecuted() throws Exception {
        BackendProcessManager manager = manager(List.of(temp.resolve("missing-binary").toString()), 0, Duration.ofSeconds(5));
        BackendStartupException failure = assertThrows(BackendStartupException.class, manager::start);
        assertEquals(BackendStartupException.Reason.LAUNCH_FAILED, failure.reason());
        assertEquals(BackendLifecycleState.FAILED, manager.state());
    }

    @Test
    void stopsTheDescendantsOfItsOwnBackend() throws Exception {
        BackendProcessManager manager = manager("with-child");
        manager.start();
        long grandchild = logs.recentBackendLines().stream().filter(line -> line.startsWith("grandchild "))
                .map(line -> Long.parseLong(line.substring("grandchild ".length()))).findFirst().orElseThrow();
        assertTrue(alive(grandchild));

        manager.stop();

        awaitDeath(grandchild, Duration.ofSeconds(5));
        assertFalse(alive(grandchild));
    }

    @Test
    void stoppingDuringStartupCancelsInsteadOfFailing() throws Exception {
        BackendProcessManager manager = manager(FakeBackend.command("silent"), 0, Duration.ofSeconds(30));
        CompletableFuture<Void> processStarted = new CompletableFuture<>();
        manager.addListener(new BackendProcessManager.Listener() {
            @Override
            public void startupStep(BackendProcessManager.StartupStep step) {
                processStarted.complete(null);
            }
        });
        CompletableFuture<BackendStartupException> outcome = CompletableFuture.supplyAsync(() -> {
            try {
                manager.start();
                return null;
            } catch (BackendStartupException failure) {
                return failure;
            }
        });
        processStarted.get(10, TimeUnit.SECONDS);

        manager.stop();

        BackendStartupException failure = outcome.get(15, TimeUnit.SECONDS);
        assertNotNull(failure);
        assertEquals(BackendStartupException.Reason.CANCELLED, failure.reason());
        assertEquals(BackendLifecycleState.STOPPED, manager.state());
        assertTrue(manager.failure().isEmpty());
    }

    @Test
    void stopWithoutAProcessIsANoOp() throws Exception {
        BackendProcessManager manager = manager("ready");
        manager.stop();
        manager.stop();
        assertEquals(BackendLifecycleState.STOPPED, manager.state());
    }

    private HttpServer foreignService() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "{\"status\":\"available\",\"protocol\":\"docgen-desktop/1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        foreignServers.add(server);
        return server;
    }

    private static HttpResponse<String> get(int port) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/health")).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }
}
