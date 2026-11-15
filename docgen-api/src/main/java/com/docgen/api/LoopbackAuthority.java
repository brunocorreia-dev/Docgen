package com.docgen.api;

import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * The only addresses the UI may use: {@code 127.0.0.1:<port>} and
 * {@code localhost:<port>}, where the port is the one the server actually
 * bound (the launcher asks for any free port). Until the server is bound the
 * port is the configured one; with port 0 nothing matches, so requests fail
 * closed.
 */
@Component
final class LoopbackAuthority implements ApplicationListener<WebServerInitializedEvent> {
    private volatile int port;

    LoopbackAuthority(Environment environment) {
        this.port = environment.getProperty("server.port", Integer.class, 0);
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        port = event.getWebServer().getPort();
    }

    int port() {
        return port;
    }

    /** DNS-rebinding defense: the Host header must name loopback and this server's port exactly. */
    boolean allowsHost(String host) {
        int current = port;
        if (host == null || current <= 0) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("127.0.0.1:" + current) || normalized.equals("localhost:" + current);
    }

    /** The page's own origin: plain HTTP to one of the allowed hosts, with nothing else in it. */
    boolean allowsOrigin(String origin) {
        if (origin == null) {
            return false;
        }
        try {
            URI uri = new URI(origin);
            return "http".equalsIgnoreCase(uri.getScheme())
                    && uri.getRawUserInfo() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && allowsHost(uri.getRawAuthority());
        } catch (URISyntaxException malformed) {
            return false;
        }
    }
}
