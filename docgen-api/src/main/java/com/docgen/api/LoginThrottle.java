package com.docgen.api;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows password guessing against the token endpoint: after a few failures a
 * username is locked with an exponential back-off (capped at 15 minutes).
 * Counting by username rather than by address keeps the policy meaningful on a
 * loopback-only server where every client shares 127.0.0.1.
 */
@Component
final class LoginThrottle {
    static final int FREE_ATTEMPTS = 5;
    private static final Duration BASE_LOCK = Duration.ofSeconds(30);
    private static final Duration MAX_LOCK = Duration.ofMinutes(15);
    private static final int MAX_TRACKED = 10_000;

    private final Map<String, State> failures = new ConcurrentHashMap<>();
    private final Clock clock;

    LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    private record State(int failures, Instant lockedUntil) {
    }

    boolean isLocked(String username) {
        State state = failures.get(key(username));
        return state != null && state.lockedUntil() != null && clock.instant().isBefore(state.lockedUntil());
    }

    void recordFailure(String username) {
        if (failures.size() >= MAX_TRACKED) {
            failures.clear();
        }
        failures.compute(key(username), (ignored, state) -> {
            int count = state == null ? 1 : state.failures() + 1;
            Instant lockedUntil = null;
            if (count >= FREE_ATTEMPTS) {
                long factor = 1L << Math.min(count - FREE_ATTEMPTS, 5);
                Duration lock = BASE_LOCK.multipliedBy(factor);
                lockedUntil = clock.instant().plus(lock.compareTo(MAX_LOCK) > 0 ? MAX_LOCK : lock);
            }
            return new State(count, lockedUntil);
        });
    }

    void recordSuccess(String username) {
        failures.remove(key(username));
    }

    private static String key(String username) {
        return username == null ? "" : username.toLowerCase(Locale.ROOT);
    }
}
