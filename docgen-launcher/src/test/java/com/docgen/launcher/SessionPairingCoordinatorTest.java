package com.docgen.launcher;

import com.docgen.desktop.DesktopMessage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionPairingCoordinatorTest {
    private static final String ID = "0f8c2a52-3b5d-4c1e-9a7f-1234567890ab";
    private static final String CODE = "ABCD-EFGH";

    private final List<List<String>> sent = new ArrayList<>();
    private final List<String> shown = new ArrayList<>();
    private final List<String> closed = new ArrayList<>();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-23T12:00:00Z"));
    private boolean channelOpen = true;

    private SessionPairingCoordinator coordinator() {
        SessionPairingCoordinator coordinator = new SessionPairingCoordinator((type, fields) -> {
            if (!channelOpen) {
                return false;
            }
            List<String> command = new ArrayList<>(List.of(type));
            command.addAll(List.of(fields));
            sent.add(command);
            return true;
        }, clock, BackendLogManager.inMemory(clock));
        coordinator.addListener(new SessionPairingCoordinator.Listener() {
            @Override
            public void pairingRequested(SessionPairingCoordinator.PairingRequest request) {
                shown.add(request.code());
            }

            @Override
            public void pairingClosed(String pairingId, String reason) {
                closed.add(reason);
            }
        });
        return coordinator;
    }

    private static DesktopMessage event(String type, String... fields) {
        return new DesktopMessage(DesktopMessage.Direction.EVENT, 1, type, List.of(fields));
    }

    private String expiry() {
        return String.valueOf(clock.instant().plusSeconds(120).getEpochSecond());
    }

    @Test
    void showsArmedRequestsAndApprovesThemWithTheirCode() {
        SessionPairingCoordinator coordinator = coordinator();
        assertTrue(coordinator.arm());
        coordinator.event(event("PAIRING_REQUESTED", ID, CODE, expiry()));

        assertEquals(List.of(CODE), shown);
        assertEquals(ID, coordinator.pending().orElseThrow().pairingId());
        assertTrue(coordinator.approve(ID));
        assertEquals(List.of(List.of("ARM"), List.of("APPROVE", ID, CODE)), sent);

        coordinator.event(event("PAIRING_CLOSED", ID, "completed"));
        assertTrue(coordinator.pending().isEmpty());
        assertEquals(List.of("completed"), closed);
    }

    @Test
    void refusesRequestsItDidNotArmFor() {
        SessionPairingCoordinator coordinator = coordinator();
        coordinator.event(event("PAIRING_REQUESTED", ID, CODE, expiry()));

        assertTrue(shown.isEmpty(), "an unsolicited request is never shown");
        assertEquals(List.of(List.of("DENY", ID)), sent);
        assertFalse(coordinator.approve(ID));
    }

    @Test
    void theArmWindowExpiresAndCoversOneRequest() {
        SessionPairingCoordinator coordinator = coordinator();
        coordinator.arm();
        clock.advance(SessionPairingCoordinator.ARM_WINDOW.plusSeconds(1));
        coordinator.event(event("PAIRING_REQUESTED", ID, CODE, expiry()));
        assertTrue(shown.isEmpty());

        coordinator.arm();
        coordinator.event(event("PAIRING_REQUESTED", ID, CODE, expiry()));
        coordinator.event(event("PAIRING_REQUESTED", "11111111-2222-3333-4444-555555555555", "WXYZ-2345", expiry()));
        assertEquals(List.of(CODE), shown, "one request per arm");
    }

    @Test
    void ignoresMalformedRequests() {
        SessionPairingCoordinator coordinator = coordinator();
        coordinator.arm();
        coordinator.event(event("PAIRING_REQUESTED", "not-an-id", CODE, expiry()));
        coordinator.event(event("PAIRING_REQUESTED", ID, "abcd-efgh", expiry()));
        coordinator.event(event("PAIRING_REQUESTED", ID, CODE));
        assertTrue(shown.isEmpty());
        assertTrue(coordinator.pending().isEmpty());
    }

    @Test
    void neverApprovesExpiredOrForeignRequests() {
        SessionPairingCoordinator coordinator = coordinator();
        coordinator.arm();
        coordinator.event(event("PAIRING_REQUESTED", ID, CODE, expiry()));

        assertFalse(coordinator.approve("11111111-2222-3333-4444-555555555555"));
        clock.advance(Duration.ofSeconds(121));
        assertFalse(coordinator.approve(ID), "expired on screen");
        assertEquals(List.of(List.of("ARM")), sent);
    }

    @Test
    void denyingSendsDenyForTheRequestOnScreen() {
        SessionPairingCoordinator coordinator = coordinator();
        coordinator.arm();
        coordinator.event(event("PAIRING_REQUESTED", ID, CODE, expiry()));
        assertTrue(coordinator.deny(ID));
        assertEquals(List.of("DENY", ID), sent.getLast());
    }

    @Test
    void reArmingReplacesTheRequestOnScreen() {
        SessionPairingCoordinator coordinator = coordinator();
        coordinator.arm();
        coordinator.event(event("PAIRING_REQUESTED", ID, CODE, expiry()));
        coordinator.arm();
        assertEquals(List.of("replaced"), closed);
        assertTrue(coordinator.pending().isEmpty());
    }

    @Test
    void aStoppedBackendDropsEverything() {
        SessionPairingCoordinator coordinator = coordinator();
        coordinator.arm();
        coordinator.event(event("PAIRING_REQUESTED", ID, CODE, expiry()));
        coordinator.stateChanged(BackendLifecycleState.STOPPING, null);
        assertTrue(coordinator.pending().isEmpty());
        assertEquals(List.of("stopped"), closed);

        channelOpen = false;
        assertFalse(coordinator.arm(), "nothing is armed without a running backend");
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
