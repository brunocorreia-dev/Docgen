package com.docgen.api;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/**
 * Desktop mode has no password: the only way to obtain a session is a
 * browser pairing confirmed in the launcher window. The API still requires a
 * configured password, so in desktop mode it is replaced by a random value
 * that exists only in this process's memory and is never printed, logged or
 * written anywhere; password and HTTP Basic logins are therefore unusable.
 * The property source is added with the highest precedence, so an operator
 * password in the environment cannot re-enable them either.
 */
final class DesktopEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    static final String PROPERTY_SOURCE = "docgenDesktopMode";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty("docgen.desktop.enabled", Boolean.class, false)) {
            return;
        }
        byte[] password = new byte[32];
        RANDOM.nextBytes(password);
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE,
                Map.of("docgen.api.password", Base64.getUrlEncoder().withoutPadding().encodeToString(password))));
    }

    @Override
    public int getOrder() {
        // After config data (application.yml, profiles) so the switch can come from any source.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
