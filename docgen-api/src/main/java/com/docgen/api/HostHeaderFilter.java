package com.docgen.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * DNS-rebinding defense for a loopback service: a page on an attacker's
 * domain that re-resolves to 127.0.0.1 still sends its own host name, so only
 * requests addressed to an allowed local host name are served.
 */
final class HostHeaderFilter extends OncePerRequestFilter {
    private final Set<String> allowedHosts;

    HostHeaderFilter(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts.stream()
                .map(host -> host.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String host = request.getServerName();
        if (host == null || !allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            RequestPolicyFilter.write(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_host",
                    "The request host is not allowed.");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
