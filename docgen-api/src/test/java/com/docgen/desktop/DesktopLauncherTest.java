package com.docgen.desktop;

import com.docgen.api.PrivateFiles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real launcher, in process: a real server on a free loopback port, a real
 * H2 file in a temporary data directory, a recording browser and recording
 * notices instead of windows. Acceptance criteria of ADR 0001 and ADR 0002.
 */
class DesktopLauncherTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path data;

    private final BlockingQueue<URI> opened = new LinkedBlockingQueue<>();
    private final List<String> notices = new CopyOnWriteArrayList<>();
    private final AtomicInteger exits = new AtomicInteger();
    private final List<DesktopLauncher.Instance> running = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    private final DesktopNotices recordingNotices = new DesktopNotices() {
        @Override
        public void browserUnavailable(Supplier<String> freshAddress) {
            notices.add("no-browser " + freshAddress.get() + " " + freshAddress.get());
        }

        @Override
        public void startupFailed(Path logDirectory) {
            notices.add("failed");
        }

        @Override
        public void alreadyRunningButUnresponsive() {
            notices.add("unresponsive");
        }
    };

    private DesktopLauncher.Settings settings(BrowserOpener browser, Duration secondStartWait) {
        return new DesktopLauncher.Settings(data, browser, recordingNotices, false, false, false,
                code -> exits.incrementAndGet(), new String[0], secondStartWait);
    }

    private DesktopLauncher.Outcome start() {
        DesktopLauncher.Outcome outcome = DesktopLauncher.start(settings(address -> opened.add(address),
                Duration.ofSeconds(3)));
        if (outcome instanceof DesktopLauncher.Outcome.Running started) {
            running.add(started.instance());
        }
        return outcome;
    }

    @AfterEach
    void stopEverything() throws InterruptedException {
        for (DesktopLauncher.Instance instance : running) {
            instance.requestShutdown("test");
            instance.awaitStopped(Duration.ofSeconds(20));
        }
    }

    private URI nextWindow() throws InterruptedException {
        URI address = opened.poll(20, TimeUnit.SECONDS);
        assertNotNull(address, "a window was opened");
        return address;
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static URI api(int port, String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private String exchange(URI launchAddress) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(api(launchAddress.getPort(), "/api/v1/session"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"launchToken\":\"" + launchAddress.getFragment() + "\"}")));
        assertEquals(201, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Set-Cookie").isEmpty(), "no cookie, ever");
        return JSON.readTree(response.body()).path("accessToken").asString();
    }

    @Test
    void startsWithoutAnyConfigurationOnAFreeLoopbackPortAndOpensAnAuthenticatedWindow() throws Exception {
        DesktopLauncher.Instance instance = assertInstanceOf(DesktopLauncher.Outcome.Running.class, start()).instance();
        URI window = nextWindow();

        assertTrue(instance.port() > 0 && instance.port() != 8080, "a port chosen by the OS");
        assertTrue(BrowserOpener.acceptable(window), window.toString());
        assertEquals(instance.port(), window.getPort());
        InstanceFile file = InstanceFile.read(data).orElseThrow();
        assertEquals(instance.port(), file.port());
        assertEquals(ProcessHandle.current().pid(), file.pid());
        if (PrivateFiles.posix()) {
            assertEquals("rw-------", PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(data.resolve(InstanceFile.FILE))));
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(data)));
        }
        assertTrue(Files.exists(data.resolve("docgen.mv.db")), "the database lives in the data directory");

        HttpResponse<String> page = send(HttpRequest.newBuilder(api(instance.port(), "/launch")));
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("/js/launch.js"), "the launch page is served at /launch");
        assertEquals("HTTP/1.1 400", statusLine(instance.port(), "attacker.example:" + instance.port()).substring(0, 12),
                "a page re-resolved to 127.0.0.1 by DNS rebinding sends its own Host");

        String session = exchange(window);
        assertEquals(200, send(HttpRequest.newBuilder(api(instance.port(), "/api/v1/projects"))
                .header("Authorization", "Bearer " + session)).statusCode());
        assertEquals(401, send(HttpRequest.newBuilder(api(instance.port(), "/api/v1/projects"))).statusCode(),
                "another local process without the token cannot use the API");
        assertEquals(409, send(HttpRequest.newBuilder(api(instance.port(), "/api/v1/session"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"launchToken\":\"" + window.getFragment() + "\"}")))
                .statusCode(), "the launch token does not work a second time");
        assertEquals(401, send(HttpRequest.newBuilder(api(instance.port(), "/api/v1/projects"))
                .header("Authorization", "Bearer " + session)).statusCode(), "and its reuse ended that session");
    }

    @Test
    void aSecondStartReopensTheRunningInstanceInsteadOfStartingAnotherServer() throws Exception {
        DesktopLauncher.Instance instance = assertInstanceOf(DesktopLauncher.Outcome.Running.class, start()).instance();
        URI first = nextWindow();

        assertInstanceOf(DesktopLauncher.Outcome.Reopened.class, start());
        URI second = nextWindow();

        assertEquals(first.getPort(), second.getPort(), "the same server");
        assertNotEquals(first.getFragment(), second.getFragment(), "a fresh single-use token");
        exchange(second);
        assertEquals(instance.port(), InstanceFile.read(data).orElseThrow().port());
    }

    @Test
    void reopeningWithAWrongSecretOrFromABrowserIsRefused() throws Exception {
        DesktopLauncher.Instance instance = assertInstanceOf(DesktopLauncher.Outcome.Running.class, start()).instance();
        nextWindow();
        String secret = InstanceFile.read(data).orElseThrow().secret();
        HttpRequest.Builder reopen = HttpRequest.newBuilder(ReopenClient.address(instance.port()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"));

        assertEquals(403, send(reopen.copy().header("X-DocGen-Instance-Secret", "W".repeat(43))).statusCode());
        assertEquals(403, send(reopen.copy()).statusCode());
        assertEquals(403, send(reopen.copy().header("X-DocGen-Instance-Secret", secret)
                .header("Origin", "http://127.0.0.1:" + instance.port())).statusCode());
        assertEquals(403, send(reopen.copy().header("X-DocGen-Instance-Secret", secret)
                .header("Sec-Fetch-Site", "cross-site")).statusCode());
        assertTrue(opened.isEmpty(), "no window was opened");
        assertEquals(202, send(reopen.copy().header("X-DocGen-Instance-Secret", secret)).statusCode());
        nextWindow();
    }

    @Test
    void leavingThroughTheUiStopsTheServerReleasesTheLockAndTheNextStartIsClean() throws Exception {
        DesktopLauncher.Instance instance = assertInstanceOf(DesktopLauncher.Outcome.Running.class, start()).instance();
        String session = exchange(nextWindow());

        HttpResponse<String> quit = send(HttpRequest.newBuilder(api(instance.port(), "/api/v1/desktop/shutdown"))
                .header("Authorization", "Bearer " + session)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")));
        assertEquals(202, quit.statusCode());
        assertFalse(instance.shutdownThread().orElseThrow().isDaemon(),
                "requested from a daemon Tomcat thread, the stopper must still keep the JVM alive until it is done");
        assertTrue(instance.awaitStopped(Duration.ofSeconds(20)));

        assertEquals(1, exits.get(), "the process is asked to exit");
        assertTrue(InstanceFile.read(data).isEmpty(), "instance.json is removed");
        assertTrue(InstanceLock.tryAcquire(data).map(lock -> {
            lock.close();
            return true;
        }).orElse(false), "the lock is released");
        assertFalse(listening(instance.port()), "the server stopped");

        DesktopLauncher.Instance again = assertInstanceOf(DesktopLauncher.Outcome.Running.class, start()).instance();
        exchange(nextWindow());
        assertTrue(again.port() > 0);
    }

    @Test
    void erasedDataDoesNotLingerInTheDatabaseFileAfterExit() throws Exception {
        DesktopLauncher.Instance instance = assertInstanceOf(DesktopLauncher.Outcome.Running.class, start()).instance();
        String session = exchange(nextWindow());
        String marker = "MarcadorSecretoDoUsuario" + System.nanoTime();
        HttpResponse<String> created = send(HttpRequest.newBuilder(api(instance.port(), "/api/v1/projects"))
                .header("Authorization", "Bearer " + session)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"name": "%s", "description": "Projeto com um marcador para provar que a remoção é real.",
                         "functionalRequirements": [], "nonFunctionalRequirements": [], "preferredTechnologies": [],
                         "constraints": [], "provider": "ollama", "outputLanguage": "pt-BR"}
                        """.formatted(marker))));
        assertEquals(201, created.statusCode(), created.body());
        // Precondition, or the test proves nothing: H2 has written the row to the file.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!fileContains(marker) && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertTrue(fileContains(marker), "the project reached the database file");

        assertEquals(204, send(HttpRequest.newBuilder(api(instance.port(), "/api/v1/data"))
                .header("Authorization", "Bearer " + session).DELETE()).statusCode());
        // H2 alone compacts for at most 200 ms when it closes, which covers a small database like this one but
        // not a large one; the erasure therefore asks for a full SHUTDOWN COMPACT at exit.
        assertTrue(Files.exists(data.resolve("compact-on-exit")), "the erasure asked for a full compaction");
        instance.requestShutdown("test");
        assertTrue(instance.awaitStopped(Duration.ofSeconds(20)));

        assertFalse(fileContains(marker), "the deleted project must not survive in the database file");
        assertFalse(Files.exists(data.resolve("compact-on-exit")), "the compaction ran at exit");
    }

    @Test
    void whenNoBrowserOpensTheUserCanCopyAFreshLink() throws Exception {
        DesktopLauncher.Outcome outcome = DesktopLauncher.start(settings(address -> false, Duration.ofSeconds(3)));
        running.add(assertInstanceOf(DesktopLauncher.Outcome.Running.class, outcome).instance());

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (notices.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        String[] parts = notices.getFirst().split(" ");
        assertEquals("no-browser", parts[0]);
        assertTrue(BrowserOpener.acceptable(URI.create(parts[1])));
        assertNotEquals(parts[1], parts[2], "each copy is a new single-use link");
    }

    @Test
    void aSecondStartWhileTheFirstIsStillStartingLeavesTheWindowToIt() throws Exception {
        try (InstanceLock starting = InstanceLock.tryAcquire(data).orElseThrow()) {
            DesktopLauncher.Outcome outcome = DesktopLauncher.start(settings(opened::add, Duration.ofMillis(600)));

            assertInstanceOf(DesktopLauncher.Outcome.AlreadyStarting.class, outcome);
            assertTrue(opened.isEmpty());
        }
    }

    @Test
    void aRunningInstanceThatDoesNotAnswerIsReportedNotDuplicated() throws Exception {
        try (InstanceLock hung = InstanceLock.tryAcquire(data).orElseThrow()) {
            new InstanceFile(freePort(), ProcessHandle.current().pid(), "S".repeat(43)).write(data);
            DesktopLauncher.Outcome outcome = DesktopLauncher.start(settings(opened::add, Duration.ofMillis(600)));

            assertInstanceOf(DesktopLauncher.Outcome.Failed.class, outcome);
            assertEquals(List.of("unresponsive"), notices);
        }
    }

    @Test
    void aStaleInstanceFileFromACrashIsReplaced() throws Exception {
        long deadPid = ProcessHandle.allProcesses().mapToLong(ProcessHandle::pid).max().orElse(1) + 100_000;
        new InstanceFile(1, deadPid, "S".repeat(43)).write(data);

        DesktopLauncher.Instance instance = assertInstanceOf(DesktopLauncher.Outcome.Running.class, start()).instance();
        nextWindow();

        assertEquals(instance.port(), InstanceFile.read(data).orElseThrow().port());
    }

    private boolean fileContains(String marker) throws IOException {
        byte[] database = Files.readAllBytes(data.resolve("docgen.mv.db"));
        return new String(database, StandardCharsets.ISO_8859_1).contains(marker);
    }

    /** Java's HttpClient refuses to send a forged Host header, so this one is written by hand. */
    private static String statusLine(int port, String host) throws IOException {
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
            socket.getOutputStream().write(("GET /launch HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            return new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),
                    StandardCharsets.US_ASCII)).readLine();
        }
    }

    private static int freePort() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static boolean listening(int port) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException refused) {
            return false;
        }
    }
}
