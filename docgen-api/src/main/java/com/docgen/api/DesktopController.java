package com.docgen.api;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lifecycle of the desktop app (ADR 0001). */
@RestController
@RequestMapping("/api/v1/desktop")
class DesktopController {
    private final ObjectProvider<DesktopControl> desktop;
    private final PresenceTracker presence;

    DesktopController(ObjectProvider<DesktopControl> desktop, PresenceTracker presence) {
        this.desktop = desktop;
        this.presence = presence;
    }

    /** The UI is open. Sent periodically by every window. */
    @PostMapping("/heartbeat")
    ResponseEntity<Void> heartbeat() {
        presence.seen();
        return ResponseEntity.noContent().build();
    }

    /** "Sair do DocGen": answers first, then stops the server. */
    @PostMapping("/shutdown")
    ResponseEntity<?> shutdown() {
        DesktopControl control = desktop.getIfAvailable();
        if (control == null) {
            return unavailable();
        }
        control.requestShutdown("user");
        return ResponseEntity.accepted().build();
    }

    /**
     * A second start of the app asks this instance to open a window. Not a
     * session route: it is authorized by the instance secret from the private
     * instance file, and {@link OriginPolicyFilter} refuses it from browsers.
     */
    @PostMapping("/reopen")
    ResponseEntity<?> reopen(@RequestHeader(name = DesktopControl.INSTANCE_SECRET_HEADER, required = false) String secret) {
        DesktopControl control = desktop.getIfAvailable();
        if (control == null || !control.acceptsInstanceSecret(secret)) {
            return ApiExceptionHandler.error(HttpStatus.FORBIDDEN, "access_denied", "Access is not authorized.");
        }
        presence.seen();
        control.openWindow();
        return ResponseEntity.accepted().build();
    }

    private static ResponseEntity<ApiDtos.ErrorResponse> unavailable() {
        return ApiExceptionHandler.error(HttpStatus.CONFLICT, "desktop_unavailable",
                "The server is not running as the desktop app.");
    }
}
