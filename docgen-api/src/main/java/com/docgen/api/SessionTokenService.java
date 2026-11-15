package com.docgen.api;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session tokens of the desktop UI (ADR 0002). A session is born only from a
 * launch token ({@link LaunchTokenService}); its opaque 256-bit token travels
 * only in the Authorization header and is kept here only as a SHA-256 hash,
 * in memory, so every session ends when the app stops. A session ends after
 * {@code docgen.auth.idle-timeout} without use (the UI heartbeat is use).
 *
 * <p>There are no user accounts: every session acts as the fixed owner
 * {@link #OWNER}, and every query still filters by owner.</p>
 */
@Component
final class SessionTokenService {
    static final String OWNER = "local";
    static final String ROLE = "LOCAL_SESSION";
    static final int MAX_SESSIONS = 8;
    private static final List<SimpleGrantedAuthority> AUTHORITIES = List.of(new SimpleGrantedAuthority("ROLE_" + ROLE));

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration idleTimeout;

    SessionTokenService(DocGenApiProperties properties, Clock clock) {
        this.clock = clock;
        this.idleTimeout = properties.auth().idleTimeout();
    }

    /** A new session: the token goes to the client once; {@code key} identifies it here. */
    record IssuedSession(String token, String key) {
    }

    private static final class Session {
        private final Instant createdAt;
        private volatile Instant lastUsed;

        private Session(Instant now) {
            this.createdAt = now;
            this.lastUsed = now;
        }
    }

    Duration idleTimeout() {
        return idleTimeout;
    }

    synchronized IssuedSession create() {
        Instant now = clock.instant();
        purgeExpired(now);
        List<Map.Entry<String, Session>> byAge = sessions.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getValue().createdAt))
                .toList();
        for (int index = 0; index <= byAge.size() - MAX_SESSIONS; index++) {
            sessions.remove(byAge.get(index).getKey());
        }
        String token = Secrets.random256();
        String key = Secrets.hash(token);
        sessions.put(key, new Session(now));
        return new IssuedSession(token, key);
    }

    Optional<Authentication> authenticate(String token) {
        if (!Secrets.wellFormed(token)) {
            return Optional.empty();
        }
        String key = Secrets.hash(token);
        Session session = sessions.get(key);
        if (session == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (expired(session, now)) {
            sessions.remove(key);
            return Optional.empty();
        }
        session.lastUsed = now;
        return Optional.of(UsernamePasswordAuthenticationToken.authenticated(OWNER, null, AUTHORITIES));
    }

    void revoke(String token) {
        if (Secrets.wellFormed(token)) {
            sessions.remove(Secrets.hash(token));
        }
    }

    void revokeKey(String key) {
        if (key != null) {
            sessions.remove(key);
        }
    }

    int activeSessions() {
        purgeExpired(clock.instant());
        return sessions.size();
    }

    private void purgeExpired(Instant now) {
        sessions.values().removeIf(session -> expired(session, now));
    }

    private boolean expired(Session session, Instant now) {
        return !now.isBefore(session.lastUsed.plus(idleTimeout));
    }
}
