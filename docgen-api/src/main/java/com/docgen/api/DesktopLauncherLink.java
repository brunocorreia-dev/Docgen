package com.docgen.api;

import com.docgen.desktop.DesktopBootstrap;
import com.docgen.desktop.DesktopMessage;
import com.docgen.desktop.DesktopProtocol;
import com.docgen.desktop.DesktopProtocolException;
import com.docgen.desktop.DesktopSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The API's end of the private channel to the desktop launcher that started it.
 *
 * <ul>
 *   <li>stdin, first line: the per-launch session secret ({@link DesktopBootstrap});
 *   without a valid line within the timeout the API refuses to start.</li>
 *   <li>stdin, later lines: launcher commands, accepted only with a valid MAC
 *   and an increasing sequence number.</li>
 *   <li>stdin EOF: the launcher closed the channel or died, so the API shuts
 *   down instead of lingering as an orphan.</li>
 *   <li>stdout: authenticated events (READY with the bound port and pid, and
 *   pairing notifications) interleaved with ordinary log output.</li>
 * </ul>
 *
 * <p>Neither the secret nor command fields are ever logged.</p>
 */
final class DesktopLauncherLink implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(DesktopLauncherLink.class);

    /** This process's stdin and stdout; tests substitute in-memory pipes. */
    record Streams(InputStream input, PrintStream output) {
    }

    /** Invoked once when the launcher's channel closes. */
    @FunctionalInterface
    interface Shutdown {
        void lifelineClosed();
    }

    /** Receives verified launcher commands; returns true when it handled the command. */
    @FunctionalInterface
    interface CommandListener {
        boolean handle(DesktopMessage command);
    }

    private final DesktopSession session;
    private final PrintStream output;
    private final Shutdown shutdown;
    private final List<CommandListener> listeners = new CopyOnWriteArrayList<>();
    private final CountDownLatch constructed = new CountDownLatch(1);
    private volatile boolean closed;

    DesktopLauncherLink(Streams streams, Duration bootstrapTimeout, Shutdown shutdown) {
        this.output = streams.output();
        this.shutdown = shutdown;
        CompletableFuture<String> bootstrapLine = new CompletableFuture<>();
        Thread.ofPlatform().daemon().name("docgen-desktop-link").start(() -> read(streams.input(), bootstrapLine));
        DesktopSession opened = null;
        try {
            opened = DesktopBootstrap.parse(bootstrapLine.get(bootstrapTimeout.toMillis(), TimeUnit.MILLISECONDS)).open();
        } catch (TimeoutException | ExecutionException missing) {
            throw abandon("Desktop mode requires the DocGen launcher: no session was received on stdin");
        } catch (DesktopProtocolException invalid) {
            throw abandon("Desktop mode requires the DocGen launcher: the session line on stdin is invalid");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw abandon("Interrupted while waiting for the DocGen launcher session");
        } finally {
            this.session = opened;
            constructed.countDown();
        }
        LOG.info("Desktop mode: launcher session {} established", session.sessionId());
    }

    private IllegalStateException abandon(String message) {
        closed = true;
        return new IllegalStateException(message);
    }

    DesktopSession session() {
        return session;
    }

    void addListener(CommandListener listener) {
        listeners.add(listener);
    }

    /** Tells the launcher the port this process is serving on; it then verifies the claim over HTTP. */
    void announceReady(int port) {
        if (port > 0) {
            emit("READY", String.valueOf(port), String.valueOf(ProcessHandle.current().pid()));
            LOG.info("Desktop mode: ready on 127.0.0.1:{}", port);
        }
    }

    /** Writes one authenticated event line, atomically with respect to log output on the same stream. */
    void emit(String type, String... fields) {
        String line = session.encode(DesktopMessage.Direction.EVENT, type, fields) + "\n";
        synchronized (output) {
            output.print(line);
            output.flush();
        }
    }

    @Override
    public void close() {
        closed = true;
    }

    private void read(InputStream input, CompletableFuture<String> bootstrapLine) {
        try {
            String first = readLine(input);
            if (first == null) {
                bootstrapLine.completeExceptionally(new IOException("stdin closed"));
                return;
            }
            bootstrapLine.complete(first);
            constructed.await();
            if (closed || session == null) {
                return;
            }
            String line;
            while ((line = readLine(input)) != null) {
                handle(line);
            }
        } catch (IOException unreadable) {
            bootstrapLine.completeExceptionally(unreadable);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!closed && session != null) {
            closed = true;
            LOG.info("Desktop mode: the launcher closed the session channel; shutting down");
            shutdown.lifelineClosed();
        }
    }

    private void handle(String line) {
        DesktopMessage command;
        try {
            command = session.accept(line, DesktopMessage.Direction.COMMAND);
        } catch (DesktopProtocolException rejected) {
            LOG.warn("Desktop mode: ignored an unauthenticated launcher command ({})", rejected.getMessage());
            return;
        }
        for (CommandListener listener : listeners) {
            try {
                if (listener.handle(command)) {
                    return;
                }
            } catch (RuntimeException failure) {
                LOG.warn("Desktop mode: launcher command {} failed ({})", command.type(), failure.getClass().getSimpleName());
                return;
            }
        }
        LOG.warn("Desktop mode: ignored unsupported launcher command {}", command.type());
    }

    /** ASCII lines of bounded length; an overlong line is consumed and returned as an invalid marker. */
    private static String readLine(InputStream input) throws IOException {
        byte[] buffer = new byte[DesktopProtocol.MAX_LINE_LENGTH];
        int length = 0;
        boolean overflow = false;
        int next;
        while ((next = input.read()) != -1) {
            if (next == '\n') {
                return overflow ? "" : new String(buffer, 0, length, StandardCharsets.US_ASCII);
            }
            if (length < buffer.length) {
                buffer[length++] = (byte) next;
            } else {
                overflow = true;
            }
        }
        return length == 0 && !overflow ? null : "";
    }
}
