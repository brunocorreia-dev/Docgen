package com.docgen.desktop;

import com.docgen.api.DesktopControl;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * A second start of the app asks the running instance to open a window
 * ({@code POST /api/v1/desktop/reopen}, ADR 0001). The only network client
 * besides the Ollama client, and like it loopback-only: the host is the
 * literal 127.0.0.1, the port comes from the private instance file, no proxy
 * is used and redirects are not followed. As an app, not a browser, it sends
 * no {@code Origin} or {@code Sec-Fetch-*} header, which the server requires.
 */
final class ReopenClient {
    static final String HOST = "127.0.0.1";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private ReopenClient() {
    }

    static URI address(int port) {
        try {
            return new URI("http", null, HOST, port, DesktopControl.REOPEN_PATH, null, null);
        } catch (URISyntaxException impossible) {
            throw new IllegalArgumentException("Invalid port", impossible);
        }
    }

    /** @return true when the running instance accepted the request */
    static boolean reopen(InstanceFile instance) {
        HttpClient client = HttpClient.newBuilder()
                .proxy(HttpClient.Builder.NO_PROXY)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        HttpRequest request = HttpRequest.newBuilder(address(instance.port()))
                .timeout(REQUEST_TIMEOUT)
                .header(DesktopControl.INSTANCE_SECRET_HEADER, instance.secret())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 202;
        } catch (IOException unreachable) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            client.close();
        }
    }
}
