package com.docgen.api;

import com.docgen.desktop.DesktopMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Desktop sign-in without passwords: a browser tab asks for a session and the
 * person at the launcher window confirms it.
 *
 * <ol>
 *   <li>"Abrir DocGen" in the launcher sends ARM: for a short window, exactly
 *   one pairing may be requested. Nothing can be requested unprompted, so no
 *   local process can pop confirmation dialogs at will.</li>
 *   <li>The workspace (same-origin, JSON, custom header: unreachable for other
 *   web pages) creates a pairing and receives a random poll secret that only
 *   it holds, plus a short display code. The launcher receives the code over
 *   its authenticated channel and shows it next to Autorizar/Recusar.</li>
 *   <li>The person compares the code shown in the browser and in the window.
 *   APPROVE must name both the pairing and its code.</li>
 *   <li>The tab polls with its secret and receives an ordinary bearer token,
 *   once; the pairing is then gone.</li>
 * </ol>
 *
 * <p>A second request while one is live cancels both and disarms (fail
 * closed: someone else is racing the browser). Wrong poll secrets cancel the
 * pairing after three attempts. Poll secrets are kept only as SHA-256 hashes,
 * and neither secrets nor tokens are ever logged.</p>
 */
@Service
@ConditionalOnProperty(name = "docgen.desktop.enabled", havingValue = "true")
class DesktopPairingService implements DesktopLauncherLink.CommandListener {
    private static final Logger LOG = LoggerFactory.getLogger(DesktopPairingService.class);
    static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    static final int MAX_FAILED_POLLS = 3;
    private static final SecureRandom RANDOM = new SecureRandom();

    enum Status {
        PENDING,
        APPROVED,
        DENIED,
        EXPIRED
    }

    record Created(String pairingId, String pollSecret, String code, Instant expiresAt) {
    }

    sealed interface PollResult {
        record Pending(Instant expiresAt) implements PollResult {
        }

        record Issued(AuthTokenService.IssuedToken token, String username) implements PollResult {
        }

        record Denied() implements PollResult {
        }

        record Expired() implements PollResult {
        }

        record Unknown() implements PollResult {
        }
    }

    private static final class Pairing {
        private final String id;
        private final byte[] pollSecretHash;
        private final String code;
        private final Instant expiresAt;
        private Status status = Status.PENDING;
        private int failedPolls;

        private Pairing(String id, byte[] pollSecretHash, String code, Instant expiresAt) {
            this.id = id;
            this.pollSecretHash = pollSecretHash;
            this.code = code;
            this.expiresAt = expiresAt;
        }
    }

    private final DesktopLauncherLink link;
    private final AuthTokenService tokens;
    private final UserDetailsService users;
    private final String username;
    private final Duration armWindow;
    private final Duration pairingTtl;
    private final Clock clock;

    private Instant armedUntil = Instant.MIN;
    private Pairing current;

    DesktopPairingService(
            DesktopLauncherLink link,
            AuthTokenService tokens,
            UserDetailsService users,
            DocGenApiProperties properties,
            Clock clock
    ) {
        this.link = link;
        this.tokens = tokens;
        this.users = users;
        this.username = properties.api().username();
        this.armWindow = properties.desktop().armWindow();
        this.pairingTtl = properties.desktop().pairingTtl();
        this.clock = clock;
        link.addListener(this);
    }

    @Override
    public boolean handle(DesktopMessage command) {
        switch (command.type()) {
            case "ARM" -> arm();
            case "APPROVE" -> {
                if (command.fields().size() != 2) {
                    return false;
                }
                approve(command.field(0), command.field(1));
            }
            case "DENY" -> {
                if (command.fields().size() != 1) {
                    return false;
                }
                deny(command.field(0));
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Opens the window in which one browser may ask for a session; a pending request is replaced. */
    synchronized void arm() {
        if (current != null) {
            close("replaced");
        }
        armedUntil = clock.instant().plus(armWindow);
        LOG.info("Desktop pairing armed for {} s", armWindow.toSeconds());
    }

    synchronized Created create() {
        expire();
        Instant now = clock.instant();
        if (current != null && (current.status == Status.PENDING || current.status == Status.APPROVED)) {
            // Two requests for one "Abrir DocGen": someone is racing the browser. Cancel everything.
            close("conflict");
            armedUntil = Instant.MIN;
            LOG.warn("Desktop pairing conflict: a second request arrived; all requests were cancelled");
            throw new ConflictException("pairing_conflict", "Another pairing request was made at the same time.");
        }
        if (!now.isBefore(armedUntil)) {
            throw new ConflictException("pairing_unavailable", "Open DocGen from the launcher window to pair this browser.");
        }
        armedUntil = Instant.MIN;
        if (current != null) {
            close(current.status == Status.DENIED ? "denied" : "expired");
        }
        String pollSecret = randomToken();
        Pairing pairing = new Pairing(Ids.random(), hash(pollSecret), randomCode(), now.plus(pairingTtl));
        current = pairing;
        link.emit("PAIRING_REQUESTED", pairing.id, pairing.code, String.valueOf(pairing.expiresAt.getEpochSecond()));
        LOG.info("Desktop pairing requested; waiting for confirmation in the launcher");
        return new Created(pairing.id, pollSecret, pairing.code, pairing.expiresAt);
    }

    synchronized void approve(String pairingId, String code) {
        expire();
        if (current == null || !current.id.equals(pairingId) || !MessageDigest.isEqual(
                current.code.getBytes(StandardCharsets.US_ASCII), code.getBytes(StandardCharsets.US_ASCII))
                || current.status != Status.PENDING) {
            LOG.warn("Desktop pairing approval ignored: no matching pending request");
            return;
        }
        current.status = Status.APPROVED;
        LOG.info("Desktop pairing approved in the launcher");
    }

    synchronized void deny(String pairingId) {
        expire();
        if (current != null && current.id.equals(pairingId) && current.status == Status.PENDING) {
            current.status = Status.DENIED;
            LOG.info("Desktop pairing denied in the launcher");
        }
    }

    synchronized PollResult poll(String pairingId, String pollSecret) {
        expire();
        if (current == null || !current.id.equals(pairingId)) {
            return new PollResult.Unknown();
        }
        if (!MessageDigest.isEqual(current.pollSecretHash, hash(pollSecret))) {
            if (++current.failedPolls >= MAX_FAILED_POLLS) {
                close("cancelled");
                LOG.warn("Desktop pairing cancelled after repeated invalid poll secrets");
            }
            return new PollResult.Unknown();
        }
        return switch (current.status) {
            case PENDING -> new PollResult.Pending(current.expiresAt);
            case DENIED -> {
                close("denied");
                yield new PollResult.Denied();
            }
            case EXPIRED -> {
                close("expired");
                yield new PollResult.Expired();
            }
            case APPROVED -> {
                UserDetails user = users.loadUserByUsername(username);
                AuthTokenService.IssuedToken issued = tokens.issue(UsernamePasswordAuthenticationToken.authenticated(
                        user.getUsername(), null, user.getAuthorities()));
                close("completed");
                LOG.info("Desktop pairing completed; a browser session was issued");
                yield new PollResult.Issued(issued, user.getUsername());
            }
        };
    }

    /** Marks an overdue request as expired so its tab learns the outcome, and tells the launcher to hide it. */
    @Scheduled(initialDelayString = "PT5S", fixedDelayString = "PT5S")
    synchronized void expire() {
        if (current != null && current.status != Status.EXPIRED && !clock.instant().isBefore(current.expiresAt)) {
            current.status = Status.EXPIRED;
            link.emit("PAIRING_CLOSED", current.id, "expired");
        }
    }

    private void close(String reason) {
        Pairing closed = current;
        current = null;
        if (closed != null && closed.status != Status.EXPIRED) {
            link.emit("PAIRING_CLOSED", closed.id, reason);
        }
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String randomCode() {
        StringBuilder code = new StringBuilder(9);
        for (int index = 0; index < 8; index++) {
            if (index == 4) {
                code.append('-');
            }
            code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return code.toString();
    }

    private static byte[] hash(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }
}
