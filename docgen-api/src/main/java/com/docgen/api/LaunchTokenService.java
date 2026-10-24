package com.docgen.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single-use launch tokens (ADR 0002). The desktop launcher asks for one each
 * time it opens a window and puts it in the URL fragment
 * ({@code /launch#<token>}); the page exchanges it for a session token.
 *
 * <p>A token is 256 random bits, valid for {@link #VALIDITY}, kept only as a
 * SHA-256 hash and accepted once. At most {@link #MAX_PENDING} are pending; a
 * new one displaces the oldest.</p>
 *
 * <p>Reuse detection: the token is briefly visible on the browser's command
 * line, so another local user could try to redeem it first. Consumed hashes are
 * remembered for {@link #CONSUMED_RETENTION}; a second redemption revokes the
 * session created by the first one and is refused. The server cannot tell which
 * redemption was legitimate, so it ends both: confidentiality over
 * availability. The retention is longer than the validity so that a stolen
 * token is still detected when the legitimate browser starts slowly.</p>
 */
@Component
public final class LaunchTokenService {
    static final Duration VALIDITY = Duration.ofSeconds(30);
    static final Duration CONSUMED_RETENTION = Duration.ofMinutes(5);
    static final int MAX_PENDING = 4;
    private static final int MAX_CONSUMED = 32;
    private static final Logger LOG = LoggerFactory.getLogger(LaunchTokenService.class);

    private final Map<String, Instant> pending = new LinkedHashMap<>();
    private final Map<String, Consumed> consumed = new LinkedHashMap<>();
    private final SessionTokenService sessions;
    private final Clock clock;

    LaunchTokenService(SessionTokenService sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    /** The outcome of presenting a launch token. */
    sealed interface Redemption {
        record Accepted(String sessionToken) implements Redemption {
        }

        record Reused() implements Redemption {
        }

        record Invalid() implements Redemption {
        }
    }

    private record Consumed(String sessionKey, Instant forgetAt) {
    }

    /** A fresh token for a new window. Never log or persist it. */
    public synchronized String issue() {
        Instant now = clock.instant();
        purge(now);
        while (pending.size() >= MAX_PENDING) {
            pending.entrySet().stream().min(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).ifPresent(pending::remove);
        }
        String token = Secrets.random256();
        pending.put(Secrets.hash(token), now.plus(VALIDITY));
        return token;
    }

    synchronized Redemption redeem(String token) {
        if (!Secrets.wellFormed(token)) {
            return new Redemption.Invalid();
        }
        Instant now = clock.instant();
        purge(now);
        String hash = Secrets.hash(token);
        Consumed previous = consumed.get(hash);
        if (previous != null) {
            sessions.revokeKey(previous.sessionKey());
            consumed.put(hash, new Consumed(null, previous.forgetAt()));
            LOG.warn("A launch token was presented twice; the session created with it was ended");
            return new Redemption.Reused();
        }
        Instant expiresAt = pending.remove(hash);
        if (expiresAt == null || !now.isBefore(expiresAt)) {
            return new Redemption.Invalid();
        }
        SessionTokenService.IssuedSession session = sessions.create();
        while (consumed.size() >= MAX_CONSUMED) {
            consumed.entrySet().stream().min(Comparator.comparing(entry -> entry.getValue().forgetAt()))
                    .map(Map.Entry::getKey).ifPresent(consumed::remove);
        }
        consumed.put(hash, new Consumed(session.key(), now.plus(CONSUMED_RETENTION)));
        LOG.info("A launch token was exchanged for a new session");
        return new Redemption.Accepted(session.token());
    }

    private void purge(Instant now) {
        pending.values().removeIf(expiresAt -> !now.isBefore(expiresAt));
        consumed.values().removeIf(entry -> !now.isBefore(entry.forgetAt()));
    }
}
