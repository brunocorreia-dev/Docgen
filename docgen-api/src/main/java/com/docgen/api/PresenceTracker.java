package com.docgen.api;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * When the UI last showed it is open: its periodic heartbeat, a new session
 * or a reopen request. Closing the browser tab does not stop the app, so this
 * is what the idle shutdown (ADR 0001) measures.
 */
@Component
final class PresenceTracker {
    private final Clock clock;
    private volatile Instant lastSeen;

    PresenceTracker(Clock clock) {
        this.clock = clock;
        this.lastSeen = clock.instant();
    }

    void seen() {
        lastSeen = clock.instant();
    }

    Instant lastSeen() {
        return lastSeen;
    }
}
