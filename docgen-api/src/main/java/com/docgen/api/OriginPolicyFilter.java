package com.docgen.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Locale;

/**
 * Cross-origin defense in depth for state-changing API requests (ADR 0002).
 * There is no ambient credential, so classic CSRF has nothing to ride on; this
 * filter still refuses, before authentication, any POST/PUT/PATCH/DELETE under
 * {@code /api/} whose {@code Origin} is not this server's own origin or whose
 * {@code Sec-Fetch-Site} is not {@code same-origin}, when the browser sends
 * them.
 *
 * <p>The reopen route is the opposite case: only the app's own second start
 * calls it, never a browser, so any {@code Origin} or {@code Sec-Fetch-*}
 * header there means a web page is trying and is refused.</p>
 */
final class OriginPolicyFilter extends OncePerRequestFilter {
    private final LoopbackAuthority authority;

    OriginPolicyFilter(LoopbackAuthority authority) {
        this.authority = authority;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        boolean stateChanging = "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)
                || "DELETE".equals(method);
        return !stateChanging || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        boolean allowed = DesktopControl.REOPEN_PATH.equals(request.getRequestURI())
                ? !sentByABrowser(request)
                : sameOrigin(request);
        if (!allowed) {
            RequestPolicyFilter.write(response, HttpServletResponse.SC_FORBIDDEN, "origin_rejected",
                    "The request origin is not allowed.");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean sameOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin != null && !authority.allowsOrigin(origin)) {
            return false;
        }
        String site = request.getHeader("Sec-Fetch-Site");
        return site == null || "same-origin".equals(site.toLowerCase(Locale.ROOT));
    }

    private static boolean sentByABrowser(HttpServletRequest request) {
        if (request.getHeader("Origin") != null) {
            return true;
        }
        for (String name : Collections.list(request.getHeaderNames())) {
            if (name.toLowerCase(Locale.ROOT).startsWith("sec-fetch-")) {
                return true;
            }
        }
        return false;
    }
}
