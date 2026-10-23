package com.docgen.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Beans that exist only when the DocGen launcher started this process
 * ({@code docgen.desktop.enabled=true}). Startup fails closed when the
 * process could be reached from other machines or when password users are
 * configured, because desktop mode authenticates sessions only through the
 * launcher.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "docgen.desktop.enabled", havingValue = "true")
class DesktopConfig {
    private static final Logger LOG = LoggerFactory.getLogger(DesktopConfig.class);

    @Bean
    DesktopLauncherLink.Streams desktopStreams() {
        return new DesktopLauncherLink.Streams(System.in, System.out);
    }

    @Bean
    DesktopLauncherLink.Shutdown desktopShutdown(ConfigurableApplicationContext context) {
        return () -> Thread.ofPlatform().name("docgen-desktop-shutdown")
                .start(() -> System.exit(SpringApplication.exit(context)));
    }

    @Bean(destroyMethod = "close")
    DesktopLauncherLink desktopLauncherLink(
            DesktopLauncherLink.Streams streams,
            DesktopLauncherLink.Shutdown shutdown,
            DocGenApiProperties properties,
            Environment environment
    ) {
        requireLoopback(environment.getProperty("server.address"));
        if (!properties.api().additionalUsers().isEmpty()) {
            throw new IllegalStateException("Desktop mode is single-user; remove docgen.api.additional-users");
        }
        return new DesktopLauncherLink(streams, properties.desktop().bootstrapTimeout(), shutdown);
    }

    @Bean
    ApplicationListener<ApplicationReadyEvent> desktopReadyAnnouncer(DesktopLauncherLink link) {
        return event -> {
            if (event.getApplicationContext() instanceof WebServerApplicationContext web && web.getWebServer() != null) {
                link.announceReady(web.getWebServer().getPort());
            } else {
                LOG.info("Desktop mode: no embedded web server, readiness not announced");
            }
        };
    }

    static void requireLoopback(String address) {
        boolean loopback;
        try {
            loopback = address != null && !address.isBlank() && InetAddress.getByName(address.strip()).isLoopbackAddress();
        } catch (UnknownHostException unresolvable) {
            loopback = false;
        }
        if (!loopback) {
            throw new IllegalStateException("Desktop mode requires server.address to be a loopback address");
        }
    }
}
