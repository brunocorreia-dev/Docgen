package com.docgen.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ADR 0002: sessions in memory only, 12 h of inactivity, at most eight. */
class SessionTokenServiceTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-24T12:00:00Z"));
    private final SessionTokenService sessions = new SessionTokenService(new DocGenApiProperties(Map.of()), clock);

    @Test
    void aSessionEndsAfterTwelveHoursWithoutUse() {
        String token = sessions.create().token();
        clock.advance(Duration.ofHours(11));
        assertTrue(sessions.authenticate(token).isPresent(), "use renews the session");
        clock.advance(Duration.ofHours(12).minusSeconds(1));
        assertTrue(sessions.authenticate(token).isPresent());
        clock.advance(Duration.ofHours(12));

        assertTrue(sessions.authenticate(token).isEmpty());
    }

    @Test
    void theNinthSessionEndsTheOldest() {
        List<String> tokens = new ArrayList<>();
        for (int index = 0; index <= SessionTokenService.MAX_SESSIONS; index++) {
            tokens.add(sessions.create().token());
            clock.advance(Duration.ofMillis(1));
        }

        assertTrue(sessions.authenticate(tokens.getFirst()).isEmpty());
        assertTrue(sessions.authenticate(tokens.getLast()).isPresent());
        assertEquals(SessionTokenService.MAX_SESSIONS, sessions.activeSessions());
    }

    @Test
    void onlyTheHashIsKeptSoTheKeyIsNotTheToken() {
        SessionTokenService.IssuedSession issued = sessions.create();

        assertTrue(!issued.key().equals(issued.token()) && issued.key().length() == 64);
        sessions.revokeKey(issued.key());
        assertTrue(sessions.authenticate(issued.token()).isEmpty());
    }
}
