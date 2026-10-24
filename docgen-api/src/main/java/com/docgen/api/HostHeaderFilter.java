package com.docgen.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * DNS-rebinding defense for a loopback service: a page on an attacker's
 * domain that re-resolves to 127.0.0.1 still sends its own host name, and a
 * page served by another local service sends another port, so only requests
 * addressed exactly to {@code 127.0.0.1:<port>} or {@code localhost:<port>}
 * are served.
 */
final class HostHeaderFilter extends OncePerRequestFilter {
    private final LoopbackAuthority authority;

    HostHeaderFilter(LoopbackAuthority authority) {
        this.authority = authority;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!authority.allowsHost(request.getHeader("Host"))) {
            RequestPolicyFilter.write(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_host",
                    "The request host is not allowed.");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
