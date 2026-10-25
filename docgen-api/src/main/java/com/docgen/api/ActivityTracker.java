package com.docgen.api;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Long operations the app must not be shut down under: generations today,
 * model downloads and chat replies in later phases. The idle shutdown
 * (ADR 0001) never fires while one runs, and its countdown restarts when the
 * last one ends.
 */
@Component
final class ActivityTracker {
    private final Clock clock;
    private int running;
    private Instant idleSince;

    ActivityTracker(Clock clock) {
        this.clock = clock;
        this.idleSince = clock.instant();
    }

    /** Marks an operation as running until the returned handle is closed (closing twice is harmless). */
    synchronized Activity begin() {
        running++;
        return new Activity();
    }

    synchronized boolean busy() {
        return running > 0;
    }

    /** When the last operation ended, or when the app started if none ran yet. */
    synchronized Instant idleSince() {
        return idleSince;
    }

    private synchronized void end() {
        running--;
        if (running == 0) {
            idleSince = clock.instant();
        }
    }

    final class Activity implements AutoCloseable {
        private boolean closed;

        private Activity() {
        }

        @Override
        public void close() {
            synchronized (ActivityTracker.this) {
                if (!closed) {
                    closed = true;
                    end();
                }
            }
        }
    }
}
