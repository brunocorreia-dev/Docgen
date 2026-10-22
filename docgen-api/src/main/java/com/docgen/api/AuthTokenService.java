package com.docgen.api;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Issues opaque bearer tokens for the web workspace. Only a SHA-256 hash of
 * each token is kept, in memory, so tokens never touch disk and all sessions
 * end when the server stops. Tokens expire absolutely and after inactivity.
 *
 * <p>Header-based tokens are never attached automatically by browsers, which
 * is why the API does not need cookie-oriented CSRF tokens (see SECURITY.md).</p>
 */
@Component
final class AuthTokenService {
    static final int MAX_TOKENS_PER_USER = 10;
    static final int MAX_TOKENS = 1_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration ttl;
    private final Duration idleTimeout;

    AuthTokenService(DocGenApiProperties properties, Clock clock) {
        this.clock = clock;
        this.ttl = properties.auth().tokenTtl();
        this.idleTimeout = properties.auth().idleTimeout();
    }

    record IssuedToken(String token, Instant expiresAt) {
    }

    private static final class Session {
        private final String username;
        private final List<GrantedAuthority> authorities;
        private final Instant createdAt;
        private final Instant expiresAt;
        private volatile Instant lastUsed;

        private Session(String username, Collection<? extends GrantedAuthority> authorities, Instant now,
                        Instant expiresAt) {
            this.username = username;
            this.authorities = List.copyOf(authorities);
            this.createdAt = now;
            this.expiresAt = expiresAt;
            this.lastUsed = now;
        }
    }

    synchronized IssuedToken issue(Authentication authentication) {
        Instant now = clock.instant();
        purgeExpired(now);
        String username = authentication.getName();
        List<Map.Entry<String, Session>> owned = sessions.entrySet().stream()
                .filter(entry -> entry.getValue().username.equals(username))
                .sorted(Comparator.comparing(entry -> entry.getValue().createdAt))
                .toList();
        for (int index = 0; index <= owned.size() - MAX_TOKENS_PER_USER; index++) {
            sessions.remove(owned.get(index).getKey());
        }
        if (sessions.size() >= MAX_TOKENS) {
            throw new CapacityExceededException("too_many_sessions", "Too many active sessions.");
        }
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        Instant expiresAt = now.plus(ttl);
        sessions.put(hash(token), new Session(username, authentication.getAuthorities(), now, expiresAt));
        return new IssuedToken(token, expiresAt);
    }

    Optional<Authentication> authenticate(String token) {
        if (token == null || token.length() < 20 || token.length() > 128) {
            return Optional.empty();
        }
        String key = hash(token);
        Session session = sessions.get(key);
        if (session == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (!now.isBefore(session.expiresAt) || !now.isBefore(session.lastUsed.plus(idleTimeout))) {
            sessions.remove(key);
            return Optional.empty();
        }
        session.lastUsed = now;
        return Optional.of(UsernamePasswordAuthenticationToken.authenticated(
                session.username, null, session.authorities));
    }

    void revoke(String token) {
        if (token != null) {
            sessions.remove(hash(token));
        }
    }

    private void purgeExpired(Instant now) {
        sessions.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt)
                || !now.isBefore(entry.getValue().lastUsed.plus(idleTimeout)));
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }
}
