package com.docgen.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ADR 0002: single use, 30 seconds, at most four pending, reuse ends the session. */
class LaunchTokenServiceTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-24T12:00:00Z"));
    private final SessionTokenService sessions = new SessionTokenService(new DocGenApiProperties(Map.of()), clock);
    private final LaunchTokenService tokens = new LaunchTokenService(sessions, clock);

    private String accepted(LaunchTokenService.Redemption redemption) {
        return assertInstanceOf(LaunchTokenService.Redemption.Accepted.class, redemption).sessionToken();
    }

    @Test
    void tokensAre256RandomBitsInBase64Url() {
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < 50; index++) {
            String token = tokens.issue();
            assertTrue(token.matches("[A-Za-z0-9_-]{43}"), token);
            assertTrue(seen.add(token));
        }
    }

    @Test
    void aTokenIsAcceptedOnceAndCreatesAWorkingSession() {
        String launch = tokens.issue();
        String session = accepted(tokens.redeem(launch));

        assertTrue(sessions.authenticate(session).isPresent());
        assertEquals("local", sessions.authenticate(session).orElseThrow().getName());
    }

    @Test
    void aTokenExpiresAfterThirtySeconds() {
        String early = tokens.issue();
        String late = tokens.issue();
        clock.advance(LaunchTokenService.VALIDITY.minusMillis(1));
        accepted(tokens.redeem(early));
        clock.advance(Duration.ofMillis(1));

        assertInstanceOf(LaunchTokenService.Redemption.Invalid.class, tokens.redeem(late));
    }

    @Test
    void aSecondRedemptionEndsTheFirstSessionAndIsRefused() {
        String launch = tokens.issue();
        String session = accepted(tokens.redeem(launch));

        assertInstanceOf(LaunchTokenService.Redemption.Reused.class, tokens.redeem(launch));
        assertTrue(sessions.authenticate(session).isEmpty(), "the session born from a reused token must end");
        assertInstanceOf(LaunchTokenService.Redemption.Reused.class, tokens.redeem(launch));
    }

    @Test
    void reuseIsStillDetectedWhenTheLegitimateBrowserArrivesAfterTheValidity() {
        String launch = tokens.issue();
        String stolen = accepted(tokens.redeem(launch));
        clock.advance(Duration.ofSeconds(90));

        assertInstanceOf(LaunchTokenService.Redemption.Reused.class, tokens.redeem(launch));
        assertTrue(sessions.authenticate(stolen).isEmpty());
    }

    @Test
    void consumedTokensAreForgottenAfterTheRetention() {
        String launch = tokens.issue();
        accepted(tokens.redeem(launch));
        clock.advance(LaunchTokenService.CONSUMED_RETENTION);

        assertInstanceOf(LaunchTokenService.Redemption.Invalid.class, tokens.redeem(launch));
    }

    @Test
    void atMostFourTokensArePendingAndTheOldestGivesWay() {
        String oldest = tokens.issue();
        clock.advance(Duration.ofMillis(1));
        for (int index = 1; index < LaunchTokenService.MAX_PENDING; index++) {
            tokens.issue();
            clock.advance(Duration.ofMillis(1));
        }
        String newest = tokens.issue();

        assertInstanceOf(LaunchTokenService.Redemption.Invalid.class, tokens.redeem(oldest));
        accepted(tokens.redeem(newest));
    }

    @Test
    void malformedTokensAreInvalid() {
        for (String token : new String[]{null, "", "short", "A".repeat(44), "A".repeat(42) + "/"}) {
            assertInstanceOf(LaunchTokenService.Redemption.Invalid.class, tokens.redeem(token));
        }
    }
}
