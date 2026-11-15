package com.docgen.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Browser half of the desktop pairing (see {@link DesktopPairingService}).
 *
 * <p>Only the workspace page itself can use these routes: requests must be
 * same-origin JSON with the workspace header, and must carry an Origin equal
 * to the (already allow-listed) Host. Other web pages cannot send such
 * requests without a CORS preflight, which is never granted, and cannot read
 * the responses either. Being able to call the routes is still not enough:
 * a session is only issued after the person confirms the code in the
 * launcher window.</p>
 */
@RestController
@RequestMapping("/api/v1/desktop")
@ConditionalOnProperty(name = "docgen.desktop.enabled", havingValue = "true")
class DesktopPairingController {
    private final DesktopPairingService pairings;

    DesktopPairingController(DesktopPairingService pairings) {
        this.pairings = pairings;
    }

    record StatusResponse(String mode) {
    }

    record PairingResponse(String pairingId, String pollSecret, String code, Instant expiresAt) {
    }

    record PollRequest(@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String pollSecret) {
    }

    record PendingResponse(String status, Instant expiresAt) {
    }

    /** Lets the workspace offer pairing instead of a password form; reveals nothing else. */
    @GetMapping("/status")
    StatusResponse status() {
        return new StatusResponse("desktop");
    }

    @PostMapping("/pairings")
    ResponseEntity<PairingResponse> create(HttpServletRequest request) {
        requireWorkspaceOrigin(request);
        DesktopPairingService.Created created = pairings.create();
        return ResponseEntity.status(HttpStatus.CREATED).body(new PairingResponse(
                created.pairingId(), created.pollSecret(), created.code(), created.expiresAt()));
    }

    @PostMapping("/pairings/{pairingId}/token")
    ResponseEntity<?> poll(
            @PathVariable("pairingId") String pairingId,
            @Valid @RequestBody PollRequest body,
            HttpServletRequest request
    ) {
        requireWorkspaceOrigin(request);
        return switch (pairings.poll(Ids.require(pairingId), body.pollSecret())) {
            case DesktopPairingService.PollResult.Pending pending ->
                    ResponseEntity.status(HttpStatus.ACCEPTED).body(new PendingResponse("pending", pending.expiresAt()));
            case DesktopPairingService.PollResult.Issued issued -> ResponseEntity.ok(new AuthController.TokenResponse(
                    issued.token().token(), "Bearer", issued.token().expiresAt(), issued.username()));
            case DesktopPairingService.PollResult.Denied denied -> ApiExceptionHandler.error(HttpStatus.FORBIDDEN,
                    "pairing_denied", "The pairing was denied in the launcher.");
            case DesktopPairingService.PollResult.Expired expired -> ApiExceptionHandler.error(HttpStatus.GONE,
                    "pairing_expired", "The pairing request expired.");
            case DesktopPairingService.PollResult.Unknown unknown -> throw new ResourceNotFoundException();
        };
    }

    /**
     * Same-origin check on top of the Host allow-list: browsers always send
     * Origin on POST, so a missing, foreign or "null" Origin is refused, as is
     * any request without the workspace header.
     */
    static void requireWorkspaceOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        String host = request.getHeader("Host");
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        boolean sameOrigin = origin != null && host != null && origin.equals("http://" + host)
                && (fetchSite == null || fetchSite.equals("same-origin"));
        if (!sameOrigin || request.getHeader(SecurityConfig.WEB_CLIENT_HEADER) == null) {
            throw new ForbiddenException("origin_rejected", "Pairing is only available to the DocGen workspace.");
        }
    }
}
