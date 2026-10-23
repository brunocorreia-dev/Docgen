package com.docgen.launcher;

import com.docgen.desktop.DesktopBootstrap;
import com.docgen.desktop.DesktopMessage;
import com.docgen.desktop.DesktopProtocol;
import com.docgen.desktop.DesktopProtocolException;
import com.docgen.desktop.DesktopSession;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Owns at most one backend process at a time.
 *
 * <ul>
 *   <li>Start: refuses a fixed port that already answers (never mistaking a
 *   foreign service for DocGen), starts the process with separate arguments,
 *   writes a fresh session secret to its stdin, waits for its authenticated
 *   READY announcement and then for the HTTP readiness proof.</li>
 *   <li>Supervision: output is read on a daemon thread, logged with
 *   redaction and never blocks the caller; a ready backend that exits on its
 *   own is reported as {@link BackendStartupException.Reason#CRASHED}.</li>
 *   <li>Stop: closes stdin (the backend shuts down gracefully on EOF), then
 *   escalates to SIGTERM and finally to a forced kill, always and only for
 *   the process this manager started and its descendants. Java reaps the
 *   child, so no zombie remains; if the launcher itself dies, the closed
 *   stdin makes the backend exit instead of lingering as an orphan.</li>
 * </ul>
 */
final class BackendProcessManager implements AutoCloseable {
    enum StartupStep {
        PROCESS_STARTED,
        PORT_ANNOUNCED,
        IDENTITY_VERIFIED
    }

    interface Listener {
        default void stateChanged(BackendLifecycleState state, BackendStartupException failure) {
        }

        default void startupStep(StartupStep step) {
        }

        /** Authenticated backend events other than READY (for example pairing requests). */
        default void event(DesktopMessage event) {
        }
    }

    static final int MAX_OUTPUT_LINE = 8192;
    private static final int PORT_PROBE_MILLIS = 500;
    private static final Duration OUTPUT_DRAIN = Duration.ofSeconds(2);

    private final BackendConfiguration configuration;
    private final BackendLogManager logs;
    private final ReadinessVerifier verifier;
    private final SecureRandom random;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();

    private BackendLifecycleState state = BackendLifecycleState.STOPPED;
    private BackendStartupException failure;
    private Process process;
    private DesktopSession session;
    private Writer commands;
    private Thread outputReader;
    private BackendEndpoint endpoint;
    private boolean stopRequested;

    BackendProcessManager(BackendConfiguration configuration, BackendLogManager logs, SecureRandom random) {
        this.configuration = configuration;
        this.logs = logs;
        this.random = random;
        this.verifier = new ReadinessVerifier(random);
    }

    void addListener(Listener listener) {
        listeners.add(listener);
    }

    BackendLifecycleState state() {
        synchronized (lock) {
            return state;
        }
    }

    Optional<BackendEndpoint> endpoint() {
        synchronized (lock) {
            return Optional.ofNullable(endpoint);
        }
    }

    Optional<BackendStartupException> failure() {
        synchronized (lock) {
            return Optional.ofNullable(failure);
        }
    }

    /**
     * Starts the backend and blocks until it is verified, fails or is
     * cancelled by {@link #stop()}. Call it off the UI thread.
     *
     * @throws IllegalStateException when this manager already runs a backend
     */
    BackendEndpoint start() throws BackendStartupException {
        synchronized (lock) {
            if (!EnumSet.of(BackendLifecycleState.STOPPED, BackendLifecycleState.FAILED).contains(state)) {
                throw new IllegalStateException("The backend is already " + state);
            }
            stopRequested = false;
            failure = null;
            endpoint = null;
            state = BackendLifecycleState.STARTING;
        }
        fireState(BackendLifecycleState.STARTING, null);
        try {
            BackendEndpoint verified = launchAndVerify();
            Process owned;
            synchronized (lock) {
                if (stopRequested) {
                    throw new BackendStartupException(BackendStartupException.Reason.CANCELLED, "startup cancelled");
                }
                endpoint = verified;
                state = BackendLifecycleState.READY;
                owned = process;
            }
            logs.launcher("backend ready on " + verified.baseUri() + " (pid " + verified.pid() + ")");
            fireState(BackendLifecycleState.READY, null);
            owned.onExit().thenAccept(this::exitedOnItsOwn);
            return verified;
        } catch (BackendStartupException startup) {
            abandonStart(startup);
            throw startup;
        } catch (RuntimeException unexpected) {
            BackendStartupException wrapped = new BackendStartupException(BackendStartupException.Reason.LAUNCH_FAILED,
                    "unexpected launcher failure: " + unexpected.getClass().getSimpleName(), null, unexpected);
            abandonStart(wrapped);
            throw wrapped;
        }
    }

    private BackendEndpoint launchAndVerify() throws BackendStartupException {
        int requestedPort = configuration.requestedPort();
        if (requestedPort > 0 && acceptsConnections(requestedPort)) {
            throw new BackendStartupException(BackendStartupException.Reason.PORT_IN_USE,
                    "port " + requestedPort + " already accepts connections; nothing was started");
        }
        DesktopBootstrap bootstrap = DesktopBootstrap.generate(random);
        String bootstrapLine = bootstrap.encode();
        logs.redactor().addSecret(bootstrapLine);
        logs.redactor().addSecret(bootstrapLine.substring(bootstrapLine.lastIndexOf(' ') + 1));
        DesktopSession opened = bootstrap.open();

        ProcessBuilder builder = new ProcessBuilder(configuration.command())
                .directory(configuration.workingDirectory().toFile())
                .redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().putAll(configuration.environment());
        Process started;
        try {
            started = builder.start();
        } catch (IOException | RuntimeException unavailable) {
            throw new BackendStartupException(BackendStartupException.Reason.LAUNCH_FAILED,
                    "the backend process could not be executed", null, unavailable);
        }
        CompletableFuture<long[]> announced = new CompletableFuture<>();
        Writer stdin = new BufferedWriter(new OutputStreamWriter(started.getOutputStream(), StandardCharsets.US_ASCII));
        Thread reader = Thread.ofPlatform().daemon().name("docgen-backend-output").unstarted(
                () -> readOutput(started, opened, announced));
        boolean cancelled;
        synchronized (lock) {
            process = started;
            session = opened;
            commands = stdin;
            outputReader = reader;
            cancelled = stopRequested;
        }
        if (cancelled) {
            // stop() ran before there was a process to stop; do not wait for readiness or the timeout.
            throw new BackendStartupException(BackendStartupException.Reason.CANCELLED, "startup cancelled");
        }
        logs.launcher("backend process started (pid " + started.pid() + ", requested port "
                + (requestedPort == 0 ? "ephemeral" : requestedPort) + ")");
        reader.start();
        fireStep(StartupStep.PROCESS_STARTED);
        try {
            // The secret travels only through this pipe, which stays open as the backend's lifeline.
            stdin.write(bootstrapLine);
            stdin.write('\n');
            stdin.flush();
        } catch (IOException closed) {
            logs.launcher("the backend closed its input before receiving the session");
        }

        long[] portAndPid = awaitAnnouncement(started, announced);
        fireStep(StartupStep.PORT_ANNOUNCED);
        int port = (int) portAndPid[0];
        if (portAndPid[1] != started.pid()) {
            throw new BackendStartupException(BackendStartupException.Reason.HANDSHAKE_FAILED,
                    "the announced process id does not match the started process");
        }
        if (requestedPort > 0 && port != requestedPort) {
            throw new BackendStartupException(BackendStartupException.Reason.HANDSHAKE_FAILED,
                    "the backend announced a port other than the requested one");
        }
        verifier.verify(opened, port, started.pid(), configuration.handshakeTimeout());
        if (!started.isAlive()) {
            throw exitFailure(started.exitValue());
        }
        fireStep(StartupStep.IDENTITY_VERIFIED);
        return new BackendEndpoint(port, started.pid());
    }

    private long[] awaitAnnouncement(Process started, CompletableFuture<long[]> announced) throws BackendStartupException {
        try {
            CompletableFuture.anyOf(announced, started.onExit())
                    .get(configuration.startupTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException slow) {
            throw new BackendStartupException(BackendStartupException.Reason.TIMEOUT,
                    "the backend was not ready after " + configuration.startupTimeout().toSeconds() + " s");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BackendStartupException(BackendStartupException.Reason.CANCELLED, "startup interrupted");
        } catch (ExecutionException impossible) {
            throw new BackendStartupException(BackendStartupException.Reason.LAUNCH_FAILED,
                    "startup supervision failed", null, impossible);
        }
        synchronized (lock) {
            if (stopRequested) {
                throw new BackendStartupException(BackendStartupException.Reason.CANCELLED, "startup cancelled");
            }
        }
        if (started.isAlive() && announced.isDone()) {
            return announced.join();
        }
        joinQuietly(outputReaderOf(started), OUTPUT_DRAIN);
        throw exitFailure(started.exitValue());
    }

    private Thread outputReaderOf(Process started) {
        synchronized (lock) {
            return process == started ? outputReader : null;
        }
    }

    /** Maps the final output of a process that died during startup to a reason the window can explain. */
    BackendStartupException exitFailure(int exitCode) {
        String output = String.join("\n", logs.recentBackendLines()).toLowerCase(Locale.ROOT);
        BackendStartupException.Reason reason;
        if (output.contains("address already in use") || (output.contains("port") && output.contains("already in use"))) {
            reason = BackendStartupException.Reason.PORT_IN_USE;
        } else if (output.contains("database may be already in use") || output.contains("locked by another process")) {
            reason = BackendStartupException.Reason.DATA_IN_USE;
        } else if (output.contains("unsupportedclassversionerror")) {
            reason = BackendStartupException.Reason.INCOMPATIBLE_RUNTIME;
        } else if (output.contains("accessdeniedexception") || output.contains("permission denied")
                || output.contains("storage paths must be real directories")) {
            reason = BackendStartupException.Reason.PERMISSION_DENIED;
        } else {
            reason = BackendStartupException.Reason.EXITED;
        }
        return new BackendStartupException(reason, "the backend exited during startup with code " + exitCode,
                exitCode, null);
    }

    private void readOutput(Process started, DesktopSession opened, CompletableFuture<long[]> announced) {
        try (Reader input = new BufferedReader(new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
            BoundedLineReader lines = new BoundedLineReader(input, MAX_OUTPUT_LINE);
            String line;
            while ((line = lines.readLine()) != null) {
                if (DesktopProtocol.isMessageLine(line)) {
                    handleMessage(line, opened, announced);
                } else {
                    logs.backend(line);
                }
            }
        } catch (IOException closed) {
            logs.launcher("backend output closed (" + closed.getClass().getSimpleName() + ")");
        }
    }

    private void handleMessage(String line, DesktopSession opened, CompletableFuture<long[]> announced) {
        DesktopMessage message;
        try {
            message = opened.accept(line, DesktopMessage.Direction.EVENT);
        } catch (DesktopProtocolException rejected) {
            // Never echo the line: a forged message may have been crafted to land in the logs.
            logs.launcher("ignored an unauthenticated protocol line (" + rejected.getMessage() + ")");
            return;
        }
        if ("READY".equals(message.type())) {
            long[] values = message.fields().size() == 2 ? numbers(message.fields()) : null;
            if (values == null || values[0] < 1 || values[0] > 65_535 || announced.isDone()) {
                logs.launcher("ignored an invalid or repeated READY announcement");
            } else {
                announced.complete(values);
            }
            return;
        }
        logs.launcher("backend event " + message.type());
        for (Listener listener : listeners) {
            listener.event(message);
        }
    }

    private static long[] numbers(List<String> fields) {
        long[] values = new long[fields.size()];
        for (int index = 0; index < values.length; index++) {
            String field = fields.get(index);
            if (field.length() > 18 || !field.chars().allMatch(Character::isDigit)) {
                return null;
            }
            values[index] = Long.parseLong(field);
        }
        return values;
    }

    /**
     * Sends an authenticated command to the running backend over its stdin.
     *
     * @return false when no verified backend is running or the pipe is closed
     */
    boolean send(String type, String... fields) {
        synchronized (lock) {
            if (state != BackendLifecycleState.READY || commands == null || session == null) {
                return false;
            }
            try {
                commands.write(session.encode(DesktopMessage.Direction.COMMAND, type, fields));
                commands.write('\n');
                commands.flush();
                return true;
            } catch (IOException closed) {
                return false;
            }
        }
    }

    /** Stops the owned backend (if any) and waits for it; safe to call repeatedly and from any thread. */
    void stop() {
        Process target;
        Thread reader;
        boolean announce;
        synchronized (lock) {
            stopRequested = true;
            target = process;
            reader = outputReader;
            announce = target != null && state != BackendLifecycleState.STOPPING;
            if (target != null) {
                state = BackendLifecycleState.STOPPING;
            }
        }
        if (target == null) {
            return;
        }
        if (announce) {
            fireState(BackendLifecycleState.STOPPING, null);
        }
        terminate(target, reader);
        boolean changed;
        synchronized (lock) {
            changed = clearProcess(target) && state == BackendLifecycleState.STOPPING;
            if (changed) {
                state = BackendLifecycleState.STOPPED;
            }
        }
        if (changed) {
            fireState(BackendLifecycleState.STOPPED, null);
        }
    }

    @Override
    public void close() {
        stop();
    }

    private void abandonStart(BackendStartupException cause) {
        Process target;
        Thread reader;
        boolean cancelled;
        synchronized (lock) {
            target = process;
            reader = outputReader;
            cancelled = stopRequested || cause.reason() == BackendStartupException.Reason.CANCELLED;
        }
        if (target != null) {
            terminate(target, reader);
        }
        logs.launcher("backend start failed: " + cause.reason() + " (" + cause.getMessage() + ")");
        BackendLifecycleState finalState = cancelled ? BackendLifecycleState.STOPPED : BackendLifecycleState.FAILED;
        boolean changed;
        synchronized (lock) {
            clearProcess(target);
            // A concurrent stop() may already have reported STOPPED; report each state once.
            changed = state != finalState || finalState == BackendLifecycleState.FAILED;
            state = finalState;
            failure = cancelled ? null : cause;
        }
        if (changed) {
            fireState(finalState, cancelled ? null : cause);
        }
    }

    private void exitedOnItsOwn(Process exited) {
        BackendStartupException crash;
        Thread reader;
        synchronized (lock) {
            if (process != exited || state != BackendLifecycleState.READY || stopRequested) {
                return;
            }
            crash = new BackendStartupException(BackendStartupException.Reason.CRASHED,
                    "the backend exited unexpectedly with code " + exited.exitValue(), exited.exitValue(), null);
            reader = outputReader;
        }
        joinQuietly(reader, OUTPUT_DRAIN);
        logs.launcher("backend exited unexpectedly with code " + exited.exitValue());
        synchronized (lock) {
            if (!clearProcess(exited)) {
                return;
            }
            state = BackendLifecycleState.FAILED;
            failure = crash;
        }
        fireState(BackendLifecycleState.FAILED, crash);
    }

    /** Graceful first, forceful last; only ever the process this manager started and its own descendants. */
    private void terminate(Process target, Thread reader) {
        List<ProcessHandle> descendants = target.descendants().toList();
        synchronized (lock) {
            if (process == target) {
                closeQuietly(commands);
                commands = null;
            }
        }
        if (!waitFor(target, configuration.shutdownGrace())) {
            logs.launcher("backend did not stop after its lifeline closed; sending SIGTERM");
            target.destroy();
            if (!waitFor(target, configuration.terminateGrace())) {
                logs.launcher("backend ignored SIGTERM; forcing termination");
                target.destroyForcibly();
                waitFor(target, configuration.terminateGrace());
            }
        }
        for (ProcessHandle descendant : descendants) {
            if (descendant.isAlive()) {
                descendant.destroyForcibly();
            }
        }
        joinQuietly(reader, OUTPUT_DRAIN);
        logs.launcher(target.isAlive() ? "backend still alive after termination attempts"
                : "backend stopped with exit code " + target.exitValue());
    }

    private boolean clearProcess(Process target) {
        if (target == null || process != target) {
            return false;
        }
        closeQuietly(commands);
        process = null;
        session = null;
        commands = null;
        outputReader = null;
        endpoint = null;
        return true;
    }

    private static boolean waitFor(Process target, Duration timeout) {
        try {
            return target.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return !target.isAlive();
        }
    }

    private static void joinQuietly(Thread thread, Duration timeout) {
        // A start cancelled before its reader started leaves a NEW thread; join(Duration) rejects those.
        if (thread == null || thread == Thread.currentThread() || thread.getState() == Thread.State.NEW) {
            return;
        }
        try {
            thread.join(timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Writer writer) {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
                // The pipe is already gone, which is what closing it means.
            }
        }
    }

    private static boolean acceptsConnections(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(BackendEndpoint.HOST, port), PORT_PROBE_MILLIS);
            return true;
        } catch (IOException refused) {
            return false;
        }
    }

    private void fireState(BackendLifecycleState newState, BackendStartupException cause) {
        for (Listener listener : listeners) {
            listener.stateChanged(newState, cause);
        }
    }

    private void fireStep(StartupStep step) {
        for (Listener listener : listeners) {
            listener.startupStep(step);
        }
    }

    /** Reads lines without letting a process that never prints a newline exhaust memory. */
    static final class BoundedLineReader {
        private final Reader input;
        private final int maxLength;

        BoundedLineReader(Reader input, int maxLength) {
            this.input = input;
            this.maxLength = maxLength;
        }

        String readLine() throws IOException {
            StringBuilder line = new StringBuilder();
            boolean truncated = false;
            int next;
            while ((next = input.read()) != -1) {
                if (next == '\n') {
                    return finish(line, truncated);
                }
                if (line.length() < maxLength) {
                    line.append((char) next);
                } else {
                    truncated = true;
                }
            }
            return line.isEmpty() && !truncated ? null : finish(line, truncated);
        }

        private static String finish(StringBuilder line, boolean truncated) {
            int length = line.length();
            if (length > 0 && line.charAt(length - 1) == '\r') {
                line.setLength(length - 1);
            }
            return truncated ? line + " [linha truncada]" : line.toString();
        }
    }
}
