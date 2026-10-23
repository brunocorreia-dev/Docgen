package com.docgen.launcher;

import com.docgen.desktop.DesktopBootstrap;
import com.docgen.desktop.DesktopMessage;
import com.docgen.desktop.DesktopProtocolException;
import com.docgen.desktop.DesktopSession;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simulated backend used by the lifecycle tests. It runs as a real child
 * process and speaks the desktop protocol, with modes that inject faults:
 * <ul>
 *   <li>{@code ready}: behaves like the real API (exits when stdin closes)</li>
 *   <li>{@code exit:<code>:<text>}: prints the text and exits during startup</li>
 *   <li>{@code silent}: never announces readiness</li>
 *   <li>{@code wrong-proof}, {@code forged-json}, {@code health-only}: bad readiness answers</li>
 *   <li>{@code wrong-pid}, {@code foreign:<port>}, {@code bad-mac}: bad announcements</li>
 *   <li>{@code crash-after-ready}: exits on its own after becoming ready</li>
 *   <li>{@code ignore-eof}: ignores the lifeline but honours SIGTERM</li>
 *   <li>{@code stubborn}: ignores the lifeline and hangs on SIGTERM</li>
 *   <li>{@code echo-secret}: prints its bootstrap line and a bearer token</li>
 *   <li>{@code with-child}: starts a grandchild process, then behaves like {@code ignore-eof}</li>
 * </ul>
 */
public final class FakeBackend {
    private FakeBackend() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "ready";
        if (mode.equals("sleeper")) {
            Thread.sleep(300_000);
            return;
        }
        if (mode.startsWith("exit:")) {
            String[] parts = mode.split(":", 3);
            System.out.println(parts[2]);
            System.out.flush();
            System.exit(Integer.parseInt(parts[1]));
        }
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.US_ASCII));
        String bootstrapLine = stdin.readLine();
        DesktopSession session = DesktopBootstrap.parse(bootstrapLine).open();
        DesktopSession signer = mode.equals("wrong-proof") ? DesktopBootstrap.generate(new SecureRandom()).open() : session;
        System.out.println("fake backend starting");
        if (mode.equals("echo-secret")) {
            System.out.println("leaked " + bootstrapLine + " Authorization: Bearer abcdefghijklmnopqrstuvwxyz");
        }
        if (mode.equals("silent")) {
            while (stdin.readLine() != null) {
                // Waits for the lifeline to close.
            }
            return;
        }
        long pid = ProcessHandle.current().pid();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/desktop/handshake", exchange -> {
            String body = new String(exchange.getRequestBody().readNBytes(4096), StandardCharsets.UTF_8);
            Matcher nonce = Pattern.compile("\"nonce\":\"([A-Za-z0-9_-]{43})\"").matcher(body);
            String response = switch (mode) {
                case "forged-json" -> "{\"protocol\":\"docgen-desktop/1\",\"proof\":\"" + "A".repeat(43) + "\"}";
                case "health-only" -> "{\"status\":\"available\"}";
                default -> nonce.find()
                        ? "{\"protocol\":\"docgen-desktop/1\",\"proof\":\""
                        + signer.readinessProof(nonce.group(1), pid, exchange.getLocalAddress().getPort()) + "\"}"
                        : "{}";
            };
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String port = mode.startsWith("foreign:") ? mode.substring("foreign:".length())
                : String.valueOf(server.getAddress().getPort());
        String announcedPid = String.valueOf(mode.equals("wrong-pid") ? pid + 1 : pid);
        String ready = session.encode(DesktopMessage.Direction.EVENT, "READY", port, announcedPid);
        if (mode.equals("bad-mac")) {
            ready = ready.substring(0, ready.length() - 4) + (ready.endsWith("AAAA") ? "BBBB" : "AAAA");
        }
        if (mode.equals("with-child")) {
            Process child = new ProcessBuilder(javaExecutable(), "-cp", System.getProperty("java.class.path"),
                    FakeBackend.class.getName(), "sleeper").start();
            System.out.println("grandchild " + child.pid());
        }
        System.out.println(ready);
        System.out.flush();
        switch (mode) {
            case "crash-after-ready" -> {
                Thread.sleep(1_500);
                System.exit(3);
            }
            case "stubborn" -> {
                Runtime.getRuntime().addShutdownHook(new Thread(FakeBackend::sleepQuietly));
                Thread.sleep(Long.MAX_VALUE);
            }
            case "ignore-eof", "with-child" -> Thread.sleep(Long.MAX_VALUE);
            default -> {
                String line;
                while ((line = stdin.readLine()) != null) {
                    try {
                        DesktopMessage command = session.accept(line, DesktopMessage.Direction.COMMAND);
                        System.out.println(session.encode(DesktopMessage.Direction.EVENT, "ACK", command.type()));
                    } catch (DesktopProtocolException rejected) {
                        System.out.println("rejected command");
                    }
                    System.out.flush();
                }
                server.stop(0);
                System.out.println("lifeline closed; exiting");
                System.exit(0);
            }
        }
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(60_000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** Test classes plus the protocol classes, located from the classes themselves. */
    static String classpath() {
        return String.join(File.pathSeparator, List.of(location(FakeBackend.class), location(DesktopBootstrap.class)));
    }

    private static String location(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (Exception unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    static List<String> command(String mode) {
        return List.of(javaExecutable(), "-cp", classpath(), FakeBackend.class.getName(), mode);
    }
}
