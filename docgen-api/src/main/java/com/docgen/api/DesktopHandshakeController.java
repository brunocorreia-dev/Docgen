package com.docgen.api;

import com.docgen.desktop.DesktopProtocol;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Readiness proof for the launcher: {@code /api/v1/health} only shows that
 * something answers on the port, while this endpoint shows that it is the
 * process holding this launch's secret. It exists only in desktop mode.
 *
 * <p>The proof MACs the launcher's fresh nonce with a key reserved for this
 * purpose, bound to the pid and the local port of the connection, and never
 * reveals the secret. Browsers are refused outright (they always send Origin
 * or Sec-Fetch-Site on a POST), and each nonce is answered once.</p>
 */
@RestController
@RequestMapping("/api/v1/desktop")
@ConditionalOnProperty(name = "docgen.desktop.enabled", havingValue = "true")
class DesktopHandshakeController {
    static final int REMEMBERED_NONCES = 1_024;

    private final DesktopLauncherLink link;
    private final long pid = ProcessHandle.current().pid();
    private final Map<String, Boolean> answered = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > REMEMBERED_NONCES;
        }
    };

    DesktopHandshakeController(DesktopLauncherLink link) {
        this.link = link;
    }

    record HandshakeRequest(@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String nonce) {
    }

    record HandshakeResponse(String protocol, String proof) {
    }

    @PostMapping("/handshake")
    HandshakeResponse handshake(@Valid @RequestBody HandshakeRequest body, HttpServletRequest request) {
        if (request.getHeader("Origin") != null || request.getHeader("Sec-Fetch-Site") != null) {
            throw new ForbiddenException("origin_rejected", "This endpoint is not available to web pages.");
        }
        if (!DesktopProtocol.isNonce(body.nonce())) {
            throw new InvalidRequestException();
        }
        synchronized (answered) {
            if (answered.putIfAbsent(body.nonce(), Boolean.TRUE) != null) {
                throw new ConflictException("nonce_reused", "The challenge was already answered.");
            }
        }
        return new HandshakeResponse(DesktopProtocol.VERSION,
                link.session().readinessProof(body.nonce(), pid, request.getLocalPort()));
    }
}
