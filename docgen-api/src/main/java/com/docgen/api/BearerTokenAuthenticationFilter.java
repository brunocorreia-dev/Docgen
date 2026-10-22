package com.docgen.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authenticates "Authorization: Bearer" tokens issued by {@link AuthTokenService}.
 * Unknown or expired tokens leave the request unauthenticated, so protected
 * endpoints answer 401 through the normal entry point.
 */
final class BearerTokenAuthenticationFilter extends OncePerRequestFilter {
    private static final String PREFIX = "Bearer ";

    private final AuthTokenService tokens;

    BearerTokenAuthenticationFilter(AuthTokenService tokens) {
        this.tokens = tokens;
    }

    static String extract(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return null;
        }
        return header.substring(PREFIX.length()).strip();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String token = extract(request);
        if (token != null) {
            tokens.authenticate(token).ifPresent(authentication -> {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
            });
        }
        filterChain.doFilter(request, response);
    }
}
