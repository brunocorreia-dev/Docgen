package com.docgen.api;

import com.docgen.desktop.DesktopBootstrap;
import com.docgen.desktop.DesktopMessage;
import com.docgen.desktop.DesktopProtocol;
import com.docgen.desktop.DesktopProtocolException;
import com.docgen.desktop.DesktopSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The launcher's half of the desktop channel, in memory: it writes the
 * bootstrap line and commands into the API's "stdin" pipe and verifies the
 * events the API prints to its "stdout".
 */
final class FakeLauncherSide {
    private final DesktopBootstrap bootstrap = DesktopBootstrap.generate(new SecureRandom());
    private final DesktopSession session;
    private final OutputStream toBackend;
    private final ByteArrayOutputStream fromBackend = new ByteArrayOutputStream();
    private final DesktopLauncherLink.Streams streams;
    private final List<DesktopMessage> events = new ArrayList<>();
    private final AtomicInteger shutdowns = new AtomicInteger();
    private int consumedLines;

    FakeLauncherSide() {
        this(true);
    }

    FakeLauncherSide(boolean sendBootstrap) {
        try {
            session = DesktopBootstrap.parse(bootstrap.encode()).open();
            Pipe pipe = Pipe.open();
            toBackend = Channels.newOutputStream(pipe.sink());
            streams = new DesktopLauncherLink.Streams(Channels.newInputStream(pipe.source()),
                    new PrintStream(fromBackend, true, StandardCharsets.UTF_8));
            if (sendBootstrap) {
                writeLine(bootstrap.encode());
            }
        } catch (IOException | DesktopProtocolException failure) {
            throw new IllegalStateException(failure);
        }
    }

    DesktopLauncherLink.Streams streams() {
        return streams;
    }

    DesktopSession session() {
        return session;
    }

    /** The secret part of the bootstrap line, to assert it never leaks. */
    String secret() {
        String line = bootstrap.encode();
        return line.substring(line.lastIndexOf(' ') + 1);
    }

    void recordShutdown() {
        shutdowns.incrementAndGet();
    }

    int shutdowns() {
        return shutdowns.get();
    }

    void writeLine(String line) {
        try {
            toBackend.write((line + "\n").getBytes(StandardCharsets.US_ASCII));
            toBackend.flush();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    void send(String type, String... fields) {
        writeLine(session.encode(DesktopMessage.Direction.COMMAND, type, fields));
    }

    void closeLifeline() {
        try {
            toBackend.close();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    String output() {
        return fromBackend.toString(StandardCharsets.UTF_8);
    }

    /** Waits for the next authenticated event of {@code type}; every line is verified exactly once. */
    synchronized DesktopMessage awaitEvent(String type, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String[] lines = output().split("\n", -1);
            for (; consumedLines < lines.length - 1; consumedLines++) {
                String line = lines[consumedLines];
                if (DesktopProtocol.isMessageLine(line)) {
                    try {
                        events.add(session.accept(line, DesktopMessage.Direction.EVENT));
                    } catch (DesktopProtocolException rejected) {
                        throw new AssertionError("the API printed an unverifiable protocol line", rejected);
                    }
                }
            }
            for (int index = 0; index < events.size(); index++) {
                if (events.get(index).type().equals(type)) {
                    return events.remove(index);
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no " + type + " event within " + timeout);
    }
}
