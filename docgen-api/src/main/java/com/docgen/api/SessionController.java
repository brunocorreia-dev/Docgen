package com.docgen.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exchanges a single-use launch token for a session token (ADR 0002). The
 * session token is returned in the body only: no cookie is ever set.
 */
@RestController
@RequestMapping("/api/v1/session")
class SessionController {
    private final LaunchTokenService launchTokens;
    private final SessionTokenService sessions;
    private final PresenceTracker presence;

    SessionController(LaunchTokenService launchTokens, SessionTokenService sessions, PresenceTracker presence) {
        this.launchTokens = launchTokens;
        this.sessions = sessions;
        this.presence = presence;
    }

    record SessionRequest(@NotBlank @Size(max = 64) String launchToken) {
    }

    record SessionResponse(String accessToken, String tokenType, long idleTimeoutSeconds) {
    }

    record SessionState(String owner, long idleTimeoutSeconds) {
    }

    @PostMapping
    ResponseEntity<?> create(@Valid @RequestBody SessionRequest request) {
        return switch (launchTokens.redeem(request.launchToken())) {
            case LaunchTokenService.Redemption.Accepted accepted -> {
                presence.seen();
                yield ResponseEntity.status(HttpStatus.CREATED).body(new SessionResponse(
                        accepted.sessionToken(), "Bearer", sessions.idleTimeout().toSeconds()));
            }
            case LaunchTokenService.Redemption.Reused reused -> ApiExceptionHandler.error(HttpStatus.CONFLICT,
                    "launch_token_reused", "The launch link was already used; its session was ended.");
            case LaunchTokenService.Redemption.Invalid invalid -> ApiExceptionHandler.error(HttpStatus.UNAUTHORIZED,
                    "launch_token_invalid", "The launch link is invalid or expired.");
        };
    }

    @GetMapping
    SessionState current() {
        return new SessionState(CurrentUser.username(), sessions.idleTimeout().toSeconds());
    }

    @DeleteMapping
    ResponseEntity<Void> end(HttpServletRequest request) {
        sessions.revoke(BearerTokenAuthenticationFilter.extract(request));
        return ResponseEntity.noContent().build();
    }
}
