package com.docgen.api;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.session.DisableEncodeUrlFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Stateless security for a loopback-only service.
 *
 * <p>Credentials travel only in the Authorization header: HTTP Basic for
 * scripts and opaque bearer tokens for the web workspace. No cookie or session
 * is ever created, so browsers never attach credentials automatically. That,
 * the content-type rule of {@link RequestPolicyFilter} and the absence of any
 * CORS grant are why Spring's cookie-oriented CSRF tokens are disabled.</p>
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {
    static final String WEB_CLIENT_HEADER = "X-DocGen-Client";
    static final String DESKTOP_HANDSHAKE = "/api/v1/desktop/handshake";
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
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AuthenticationManager authenticationManager,
            AuthTokenService tokens,
            DocGenApiProperties properties
    ) throws Exception {
        AuthenticationEntryPoint entryPoint = SecurityConfig::writeUnauthorized;
        http
                // No ambient credentials exist (see class comment), so cookie CSRF tokens add nothing.
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .authenticationManager(authenticationManager)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> {
                    authorize
                            .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                            .requestMatchers(HttpMethod.GET, "/", "/index.html", "/favicon.svg",
                                    "/css/**", "/js/**", "/img/**").permitAll()
                            .requestMatchers(HttpMethod.GET, "/api/v1/health", "/api/v1/providers").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/v1/auth/token").permitAll();
                    if (properties.desktop().enabled()) {
                        // Desktop-only routes; each controller enforces its own proof (secret MAC, pairing secret).
                        authorize.requestMatchers(HttpMethod.POST, DESKTOP_HANDSHAKE).permitAll();
                    }
                    authorize
                            .requestMatchers("/api/v1/**").hasRole("ANALYST")
                            .anyRequest().denyAll();
                })
                .httpBasic(basic -> basic.authenticationEntryPoint(entryPoint))
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
                .addFilterBefore(new HostHeaderFilter(properties.api().allowedHosts()), DisableEncodeUrlFilter.class)
                .addFilterBefore(new BearerTokenAuthenticationFilter(tokens), BasicAuthenticationFilter.class)
                .addFilterAfter(new RequestPolicyFilter(properties.attachments().maxBytes()), AuthorizationFilter.class);
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(
            DocGenApiProperties properties,
            PasswordEncoder passwordEncoder
    ) {
        List<UserDetails> users = new ArrayList<>();
        users.add(analyst(properties.api().username(), properties.api().password(), passwordEncoder));
        for (Map.Entry<String, String> extra : properties.api().additionalUsers().entrySet()) {
            if (!USERNAME.matcher(extra.getKey()).matches() || extra.getValue() == null
                    || extra.getValue().length() < 12 || extra.getValue().length() > 256
                    || extra.getKey().equalsIgnoreCase(properties.api().username())) {
                throw new IllegalStateException("Additional API users need a valid unique name and a 12+ character password");
            }
            users.add(analyst(extra.getKey(), extra.getValue(), passwordEncoder));
        }
        return new InMemoryUserDetailsManager(users);
    }

    @Bean
    AuthenticationManager authenticationManager(
            UserDetailsService users,
            PasswordEncoder passwordEncoder,
            LoginThrottle throttle
    ) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwordEncoder);
        return new ThrottledAuthenticationManager(new ProviderManager(provider), throttle);
    }

    private static UserDetails analyst(String username, String password, PasswordEncoder encoder) {
        return User.builder()
                .username(username)
                .password(encoder.encode(password))
                .roles("ANALYST")
                .build();
    }

    /**
     * The web workspace identifies itself with a header so it receives a Bearer
     * challenge; browsers show no native Basic dialog for it and therefore never
     * cache Basic credentials that they could later attach automatically.
     */
    private static void writeUnauthorized(
            HttpServletRequest request,
            HttpServletResponse response,
            Exception ignored
    ) throws IOException {
        boolean webClient = request.getHeader(WEB_CLIENT_HEADER) != null;
        response.setHeader("WWW-Authenticate", webClient
                ? "Bearer realm=\"docgen-local\""
                : "Basic realm=\"docgen-local\"");
        RequestPolicyFilter.write(response, HttpServletResponse.SC_UNAUTHORIZED,
                "authentication_required", "Authentication is required.");
    }
}
