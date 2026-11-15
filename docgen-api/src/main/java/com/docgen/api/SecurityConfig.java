package com.docgen.api;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.session.DisableEncodeUrlFilter;

/**
 * Stateless security for the loopback-only desktop server (ADR 0002).
 *
 * <p>The only credential is a session token born from a single-use launch
 * token and sent in the Authorization header. There are no accounts, no
 * passwords and no HTTP Basic, and no cookie or server session is ever
 * created, so browsers never attach a credential automatically. That, the
 * content-type rule of {@link RequestPolicyFilter}, the origin rule of
 * {@link OriginPolicyFilter} and the absence of any CORS grant are why Spring's
 * cookie-oriented CSRF tokens are disabled.</p>
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {
    static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'none'",
            "script-src 'self'",
            "style-src 'self'",
            "img-src 'self' data:",
            "font-src 'self'",
            "connect-src 'self'",
            "manifest-src 'self'",
            "base-uri 'none'",
            "form-action 'none'",
            "frame-ancestors 'none'",
            "object-src 'none'");

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SessionTokenService sessions,
            LoopbackAuthority authority,
            DocGenApiProperties properties
    ) throws Exception {
        AuthenticationEntryPoint entryPoint = (request, response, failure) -> {
            response.setHeader("WWW-Authenticate", "Bearer realm=\"docgen-local\"");
            RequestPolicyFilter.write(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "authentication_required", "Authentication is required.");
        };
        http
                // No ambient credentials exist (see class comment), so cookie CSRF tokens add nothing.
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/launch", "/launch.html",
                                "/favicon.svg", "/css/**", "/js/**", "/img/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/health").permitAll()
                        // Authorized by a launch token (in the body) or the instance secret (in a header).
                        .requestMatchers(HttpMethod.POST, "/api/v1/session", DesktopControl.REOPEN_PATH).permitAll()
                        .requestMatchers("/api/v1/**").hasRole(SessionTokenService.ROLE)
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler((request, response, failure) ->
                                RequestPolicyFilter.write(response, HttpServletResponse.SC_FORBIDDEN,
                                        "access_denied", "Access is not authorized.")))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .frameOptions(frame -> frame.deny())
                        .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy",
                                "camera=(), microphone=(), geolocation=(), payment=(), usb=()"))
                        .addHeaderWriter(new StaticHeadersWriter("Cross-Origin-Opener-Policy", "same-origin"))
                        .addHeaderWriter(new StaticHeadersWriter("Cross-Origin-Resource-Policy", "same-origin")))
                .addFilterBefore(new HostHeaderFilter(authority), DisableEncodeUrlFilter.class)
                .addFilterAfter(new OriginPolicyFilter(authority), HostHeaderFilter.class)
                .addFilterBefore(new BearerTokenAuthenticationFilter(sessions), AnonymousAuthenticationFilter.class)
                .addFilterAfter(new RequestPolicyFilter(properties.attachments().maxBytes()), AuthorizationFilter.class);
        return http.build();
    }
}
