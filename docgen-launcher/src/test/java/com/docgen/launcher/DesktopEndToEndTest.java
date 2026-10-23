package com.docgen.launcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The launcher against the real docgen-api.jar of this build: readiness
 * proof, browser pairing confirmed by the launcher, an authenticated API call
 * with the issued token, and fail-closed refusals of unsafe configurations.
 * Skipped when the API jar has not been packaged (for example {@code mvn test}).
 */
class DesktopEndToEndTest {
    private static final PlatformDetector.Platform PLATFORM = PlatformDetector.current();
    private static final Duration STARTUP = Duration.ofSeconds(120);

    @TempDir
    Path temp;

    private Path backendJar;
    private BackendLogManager logs;
    private final List<BackendProcessManager> managers = new ArrayList<>();

    @BeforeEach
    void requireTheBuiltApi() throws IOException {
        String configured = System.getProperty("docgen.backend.jar");
        assumeTrue(configured != null && !configured.isBlank(), "docgen.backend.jar is not configured");
        backendJar = Path.of(configured).toAbsolutePath().normalize();
        assumeTrue(Files.isRegularFile(backendJar) && BackendArtifact.problem(backendJar) == null,
                "docgen-api.jar has not been packaged in this build");
        logs = BackendLogManager.open(temp.resolve("state/docgen/logs"), Clock.systemUTC());
    }

    @AfterEach
    void stopEverything() {
        managers.forEach(BackendProcessManager::stop);
        if (logs != null) {
            logs.close();
        }
    }

    private BackendConfiguration configuration(Map<String, String> extraEnvironment) {
        InstallationLayout layout = InstallationLayout.resolve(PLATFORM, Map.of(
                        "DOCGEN_DATA_DIR", temp.resolve("data").toString(), "XDG_STATE_HOME", temp.resolve("state").toString()),
                temp.resolve("home"), temp.resolve("app/docgen-launcher.jar"), backendJar, null);
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.remove("GROQ_API_KEY");
        environment.putAll(extraEnvironment);
        BackendConfiguration configuration = BackendConfiguration.forInstallation(RuntimeEnvironment.current(PLATFORM),
                layout, environment, "e2e-user", 0, STARTUP);
        try {
            Files.createDirectories(configuration.workingDirectory());
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
        return configuration;
    }

    private BackendProcessManager manager(BackendConfiguration configuration) {
        BackendProcessManager manager = new BackendProcessManager(configuration, logs, new SecureRandom());
        managers.add(manager);
        return manager;
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build()) {
            return client.send(request.timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    /** A request exactly as the workspace page sends it. */
    private static HttpRequest.Builder fromWorkspace(BackendEndpoint endpoint, String path, String json) {
        return HttpRequest.newBuilder(endpoint.baseUri().resolve(path))
                .header("Origin", "http://127.0.0.1:" + endpoint.port())
                .header("Sec-Fetch-Site", "same-origin")
                .header("X-DocGen-Client", "web")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
    }

    @Test
    void theWholeDesktopFlowWorksAgainstTheRealApi() throws Exception {
        BackendProcessManager manager = manager(configuration(Map.of("DOCGEN_API_PASSWORD", "operator-password-123")));
        SessionPairingCoordinator coordinator = new SessionPairingCoordinator(manager::send, Clock.systemUTC(), logs);
        manager.addListener(coordinator);
        BlockingQueue<SessionPairingCoordinator.PairingRequest> shown = new LinkedBlockingQueue<>();
        coordinator.addListener(new SessionPairingCoordinator.Listener() {
            @Override
            public void pairingRequested(SessionPairingCoordinator.PairingRequest request) {
                shown.add(request);
            }

            @Override
            public void pairingClosed(String pairingId, String reason) {
            }
        });

        BackendEndpoint endpoint = manager.start();

        // Before "Abrir DocGen", nobody can even ask for a session.
        HttpResponse<String> unarmed = send(fromWorkspace(endpoint, "/api/v1/desktop/pairings", "{}"));
        assertEquals(409, unarmed.statusCode());
        assertTrue(unarmed.body().contains("pairing_unavailable"));

        // A foreign page cannot use the pairing routes at all.
        HttpResponse<String> foreign = send(HttpRequest.newBuilder(endpoint.baseUri().resolve("/api/v1/desktop/pairings"))
                .header("Origin", "https://evil.example").header("X-DocGen-Client", "web")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")));
        assertEquals(403, foreign.statusCode());

        // "Abrir DocGen": the launcher arms, the tab asks, the window shows the same code.
        assertTrue(coordinator.arm());
        Map<String, String> pairing = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (pairing == null && System.nanoTime() < deadline) {
            HttpResponse<String> created = send(fromWorkspace(endpoint, "/api/v1/desktop/pairings", "{}"));
            if (created.statusCode() == 201) {
                pairing = FlatJson.parseObject(created.body());
            } else {
                Thread.sleep(50);
            }
        }
        assertNotNull(pairing, "the armed API accepts exactly one pairing");
        SessionPairingCoordinator.PairingRequest request = shown.poll(10, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals(pairing.get("pairingId"), request.pairingId());
        assertEquals(pairing.get("code"), request.code());

        String pollPath = "/api/v1/desktop/pairings/" + pairing.get("pairingId") + "/token";
        String pollBody = "{\"pollSecret\":\"" + pairing.get("pollSecret") + "\"}";
        assertEquals(202, send(fromWorkspace(endpoint, pollPath, pollBody)).statusCode(), "nothing before approval");

        assertTrue(coordinator.approve(request.pairingId()));
        HttpResponse<String> issued = send(fromWorkspace(endpoint, pollPath, pollBody));
        for (int attempt = 0; attempt < 100 && issued.statusCode() == 202; attempt++) {
            Thread.sleep(50);
            issued = send(fromWorkspace(endpoint, pollPath, pollBody));
        }
        assertEquals(200, issued.statusCode());
        Map<String, String> session = FlatJson.parseObject(issued.body());
        String token = session.get("accessToken");
        assertEquals("e2e-user", session.get("username"));

        HttpResponse<String> me = send(HttpRequest.newBuilder(endpoint.baseUri().resolve("/api/v1/auth/session"))
                .header("Authorization", "Bearer " + token).header("X-DocGen-Client", "web").GET());
        assertEquals(200, me.statusCode());
        assertEquals("e2e-user", FlatJson.parseObject(me.body()).get("username"));
        assertEquals(404, send(fromWorkspace(endpoint, pollPath, pollBody)).statusCode(), "the pairing is single use");

        // The operator password from the environment never reaches desktop mode.
        HttpResponse<String> password = send(HttpRequest.newBuilder(endpoint.baseUri().resolve("/api/v1/auth/token"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"e2e-user\",\"password\":\"operator-password-123\"}")));
        assertEquals(401, password.statusCode());

        manager.stop();
        assertFalse(ProcessHandle.of(endpoint.pid()).map(ProcessHandle::isAlive).orElse(false));
        String allLogs = Files.readString(logs.backendLogFile().orElseThrow())
                + Files.readString(logs.launcherLogFile().orElseThrow());
        assertFalse(allLogs.contains(token), "the session token never reaches the logs");
        assertFalse(allLogs.contains(pairing.get("pollSecret")), "the poll secret never reaches the logs");
        assertTrue(allLogs.contains("Desktop mode: launcher session"));
    }

    @Test
    void theRealApiRefusesDesktopModeWithoutTheLauncherSecret() throws Exception {
        BackendConfiguration configuration = configuration(Map.of());
        List<String> command = new ArrayList<>(configuration.command());
        command.add("--docgen.desktop.bootstrap-timeout=2s");
        ProcessBuilder builder = new ProcessBuilder(command).directory(configuration.workingDirectory().toFile())
                .redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().putAll(configuration.environment());
        Process process = builder.start();
        process.getOutputStream().close();
        String output = readAll(process.getInputStream());
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assertTrue(process.exitValue() != 0);
        assertTrue(output.contains("Desktop mode requires the DocGen launcher"), output);
    }

    @Test
    void theRealApiRefusesToListenBeyondLoopbackInDesktopMode() throws Exception {
        BackendConfiguration safe = configuration(Map.of());
        List<String> command = new ArrayList<>(safe.command());
        command.replaceAll(argument -> argument.startsWith("--server.address=") ? "--server.address=0.0.0.0" : argument);
        BackendProcessManager manager = manager(new BackendConfiguration(command, safe.environment(), safe.workingDirectory(),
                0, STARTUP, safe.shutdownGrace(), safe.terminateGrace(), safe.handshakeTimeout()));

        BackendStartupException failure = assertThrows(BackendStartupException.class, manager::start);

        assertEquals(BackendStartupException.Reason.EXITED, failure.reason());
        assertTrue(String.join("\n", logs.recentBackendLines()).contains("loopback"));
    }

    @Test
    void theRealApiRefusesPasswordUsersInDesktopMode() throws Exception {
        BackendConfiguration safe = configuration(Map.of());
        List<String> command = new ArrayList<>(safe.command());
        command.add("--docgen.api.additional-users.eve=eve-password-123");
        BackendProcessManager manager = manager(new BackendConfiguration(command, safe.environment(), safe.workingDirectory(),
                0, STARTUP, safe.shutdownGrace(), safe.terminateGrace(), safe.handshakeTimeout()));

        BackendStartupException failure = assertThrows(BackendStartupException.class, manager::start);

        assertEquals(BackendStartupException.Reason.EXITED, failure.reason());
        assertTrue(String.join("\n", logs.recentBackendLines()).contains("single-user"));
    }

    private static String readAll(InputStream input) throws IOException {
        return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
}
