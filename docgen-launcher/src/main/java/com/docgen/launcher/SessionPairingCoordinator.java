package com.docgen.launcher;

import com.docgen.desktop.DesktopMessage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Launcher half of the browser pairing. "Abrir DocGen" arms the backend for
 * one request; the backend reports the request over the authenticated
 * channel, and the window shows its code until the person authorizes or
 * refuses it. A request the launcher did not arm for is refused without
 * being shown, so nothing can make the window ask for approval on its own.
 */
final class SessionPairingCoordinator implements BackendProcessManager.Listener {
    /** Same window the API grants after ARM (docgen.desktop.arm-window default). */
    static final Duration ARM_WINDOW = Duration.ofMinutes(2);
    private static final Pattern PAIRING_ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern CODE = Pattern.compile("[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}");
    private static final Pattern EPOCH_SECONDS = Pattern.compile("[0-9]{1,12}");

    @FunctionalInterface
    interface CommandChannel {
        boolean send(String type, String... fields);
    }

    record PairingRequest(String pairingId, String code, Instant expiresAt) {
    }

    interface Listener {
        void pairingRequested(PairingRequest request);

        void pairingClosed(String pairingId, String reason);
    }

    private final CommandChannel channel;
    private final Clock clock;
    private final BackendLogManager logs;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private Instant armedAt;
    private PairingRequest pending;

    SessionPairingCoordinator(CommandChannel channel, Clock clock, BackendLogManager logs) {
        this.channel = channel;
        this.clock = clock;
        this.logs = logs;
    }

    void addListener(Listener listener) {
        listeners.add(listener);
    }

    /** Allows one browser request for {@link #ARM_WINDOW}; a request still on screen is replaced. */
    boolean arm() {
        PairingRequest replaced;
        synchronized (this) {
            if (!channel.send("ARM")) {
                return false;
            }
            armedAt = clock.instant();
            replaced = pending;
            pending = null;
        }
        if (replaced != null) {
            fireClosed(replaced.pairingId(), "replaced");
        }
        return true;
    }

    Optional<PairingRequest> pending() {
        synchronized (this) {
            return Optional.ofNullable(pending);
        }
    }

    /** Sends APPROVE for the request on screen, naming its code; false when it is gone or expired. */
    boolean approve(String pairingId) {
        synchronized (this) {
            if (pending == null || !pending.pairingId().equals(pairingId) || !clock.instant().isBefore(pending.expiresAt())) {
                return false;
            }
            return channel.send("APPROVE", pending.pairingId(), pending.code());
        }
    }

    boolean deny(String pairingId) {
        synchronized (this) {
            if (pending == null || !pending.pairingId().equals(pairingId)) {
                return false;
            }
            return channel.send("DENY", pairingId);
        }
    }

    /** Forgets everything when the backend stops; a new backend means a new session. */
    void reset() {
        synchronized (this) {
            armedAt = null;
            pending = null;
        }
    }

    @Override
    public void stateChanged(BackendLifecycleState state, BackendStartupException failure) {
        if (state != BackendLifecycleState.READY) {
            PairingRequest dropped;
            synchronized (this) {
                dropped = pending;
            }
            reset();
            if (dropped != null) {
                fireClosed(dropped.pairingId(), "stopped");
            }
        }
    }

    @Override
    public void event(DesktopMessage event) {
        switch (event.type()) {
            case "PAIRING_REQUESTED" -> requested(event);
            case "PAIRING_CLOSED" -> closed(event);
            default -> {
                // Other events are not about pairing.
            }
        }
    }

    private void requested(DesktopMessage event) {
        List<String> fields = event.fields();
        if (fields.size() != 3 || !PAIRING_ID.matcher(fields.get(0)).matches() || !CODE.matcher(fields.get(1)).matches()
                || !EPOCH_SECONDS.matcher(fields.get(2)).matches()) {
            logs.launcher("ignored a malformed pairing request");
            return;
        }
        PairingRequest request = new PairingRequest(fields.get(0), fields.get(1),
                Instant.ofEpochSecond(Long.parseLong(fields.get(2))));
        synchronized (this) {
            Instant now = clock.instant();
            if (armedAt == null || !now.isBefore(armedAt.plus(ARM_WINDOW))) {
                logs.launcher("refused a pairing request the launcher did not ask for");
                channel.send("DENY", request.pairingId());
                return;
            }
            armedAt = null;
            pending = request;
        }
        logs.launcher("browser pairing requested; waiting for the user's decision");
        for (Listener listener : listeners) {
            listener.pairingRequested(request);
        }
    }

    private void closed(DesktopMessage event) {
        if (event.fields().size() != 2) {
            return;
        }
        String pairingId = event.field(0);
        synchronized (this) {
            if (pending == null || !pending.pairingId().equals(pairingId)) {
                return;
            }
            pending = null;
        }
        logs.launcher("browser pairing closed (" + event.field(1) + ")");
        fireClosed(pairingId, event.field(1));
    }

    private void fireClosed(String pairingId, String reason) {
        for (Listener listener : listeners) {
            listener.pairingClosed(pairingId, reason);
        }
    }
}
