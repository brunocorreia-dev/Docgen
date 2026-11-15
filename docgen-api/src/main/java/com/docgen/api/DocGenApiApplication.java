package com.docgen.api;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The DocGen server. The app is started by {@code com.docgen.desktop.DesktopLauncher},
 * which resolves the data directory, takes the single-instance lock and opens
 * the browser; this class is its Spring configuration.
 *
 * <p>There are no user accounts, so Spring Boot's default in-memory user (and
 * the generated password it would log) is excluded.</p>
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@EnableConfigurationProperties(DocGenApiProperties.class)
public class DocGenApiApplication {
}
