package com.docgen.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Ends the desktop app after {@code docgen.desktop.idle-timeout} without any
 * sign of the UI (ADR 0001, decision P2), so closing the last tab does not leave
 * a server running forever. It never fires while a long operation runs; the
 * countdown starts again when the last one ends. Without the desktop launcher
 * (tests, development) it does nothing.
 */
@Component
final class IdleShutdownMonitor {
    private static final Logger LOG = LoggerFactory.getLogger(IdleShutdownMonitor.class);

    private final PresenceTracker presence;
    private final ActivityTracker activity;
    private final ObjectProvider<DesktopControl> desktop;
    private final Clock clock;
    private final Duration idleTimeout;
    private boolean requested;

    IdleShutdownMonitor(
            PresenceTracker presence,
            ActivityTracker activity,
            ObjectProvider<DesktopControl> desktop,
            Clock clock,
            DocGenApiProperties properties
    ) {
        this.presence = presence;
        this.activity = activity;
        this.desktop = desktop;
        this.clock = clock;
        this.idleTimeout = properties.desktop().idleTimeout();
    }

    static boolean idleLongEnough(Instant now, Instant lastSeen, boolean busy, Instant idleSince, Duration timeout) {
        if (busy) {
            return false;
        }
        Instant lastSign = lastSeen.isAfter(idleSince) ? lastSeen : idleSince;
        return !now.isBefore(lastSign.plus(timeout));
    }

    @Scheduled(initialDelayString = "${docgen.desktop.idle-check-interval:30s}",
            fixedDelayString = "${docgen.desktop.idle-check-interval:30s}")
    synchronized void check() {
        DesktopControl control = desktop.getIfAvailable();
        if (control == null || requested) {
            return;
        }
        if (idleLongEnough(clock.instant(), presence.lastSeen(), activity.busy(), activity.idleSince(), idleTimeout)) {
            requested = true;
            LOG.info("No window has been open for {}; stopping DocGen", idleTimeout);
            control.requestShutdown("idle");
        }
    }
}
