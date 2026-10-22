package com.docgen.api;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Operation-specific consent for remote transmission. A preview shows the
 * user exactly what would be sent and returns a single-use token bound to the
 * user, project, operation, provider, model and the SHA-256 digest of the exact
 * prompt. Any change to the inputs changes the digest and invalidates consent.
 * Authentication proves who the user is; this token records what they agreed
 * to transmit. The two are never conflated.
 */
@Component
final class RemoteConsentService {
    static final int MAX_PENDING = 1_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    enum Operation {
        PLAN, DOCUMENTATION
    }

    record Grant(
            String owner,
            String projectId,
            Operation operation,
            String provider,
            String model,
            String digest,
            Instant expiresAt
    ) {
    }

    record IssuedConsent(String token, Instant expiresAt) {
    }

    private final Map<String, Grant> grants = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration ttl;

    RemoteConsentService(DocGenApiProperties properties, Clock clock) {
        this.clock = clock;
        this.ttl = properties.auth().consentTtl();
    }

    IssuedConsent issue(String owner, String projectId, Operation operation, String provider, String model,
                        String digest) {
        Instant now = clock.instant();
        grants.values().removeIf(grant -> !now.isBefore(grant.expiresAt()));
        if (grants.size() >= MAX_PENDING) {
            throw new CapacityExceededException("too_many_consents", "Too many pending consent requests.");
        }
        byte[] secret = new byte[24];
        RANDOM.nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        Instant expiresAt = now.plus(ttl);
        grants.put(token, new Grant(owner, projectId, operation, provider, model, digest, expiresAt));
        return new IssuedConsent(token, expiresAt);
    }

    /** Consumes the token; it can never be reused, even when the check fails. */
    void consume(String token, String owner, String projectId, Operation operation, String provider, String model,
                 String digest) {
        if (token == null || token.isBlank()) {
            throw new ConflictException("consent_required",
                    "This provider sends data to a remote service. Review and confirm the transmission first.");
        }
        Grant grant = grants.remove(token);
        if (grant == null || !clock.instant().isBefore(grant.expiresAt())
                || !grant.owner().equals(owner)
                || !grant.projectId().equals(projectId)
                || grant.operation() != operation
                || !grant.provider().equals(provider)
                || !Objects.equals(grant.model(), model)) {
            throw new ConflictException("consent_invalid",
                    "The transmission consent is invalid or expired. Review the transmission again.");
        }
        if (!grant.digest().equals(digest)) {
            throw new ConflictException("consent_stale",
                    "The project changed after the transmission was reviewed. Review it again.");
        }
    }
}
