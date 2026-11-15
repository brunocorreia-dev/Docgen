package com.docgen.launcher;

import com.docgen.desktop.DesktopProtocol;
import com.docgen.desktop.DesktopSession;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Client side of the readiness handshake. A port that merely answers HTTP
 * (even with a 200 from /api/v1/health) proves nothing; the backend must MAC
 * a fresh nonce with the key derived from this launch's secret, bound to its
 * pid and to the port the request reached. Anything else fails closed.
 */
final class ReadinessVerifier {
    static final String PATH = "/api/v1/desktop/handshake";
    private static final int MAX_RESPONSE_BYTES = FlatJson.MAX_LENGTH;
    private static final Duration RETRY_DELAY = Duration.ofMillis(150);

    private final SecureRandom random;

    ReadinessVerifier(SecureRandom random) {
        this.random = random;
    }

    void verify(DesktopSession session, int port, long pid, Duration timeout) throws BackendStartupException {
        String nonce = DesktopProtocol.newNonce(random);
        Instant deadline = Instant.now().plus(timeout);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + BackendEndpoint.HOST + ":" + port + PATH))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"nonce\":\"" + nonce + "\"}", StandardCharsets.US_ASCII))
                .build();
        try (HttpClient client = HttpClient.newBuilder()
                .proxy(HttpClient.Builder.NO_PROXY)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(timeout)
                .build()) {
            HttpResponse<InputStream> response = send(client, request, deadline);
            String body;
            try (InputStream input = response.body()) {
                byte[] bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    throw failure("readiness response too large");
                }
                body = new String(bytes, StandardCharsets.UTF_8);
            }
            String type = response.headers().firstValue("Content-Type").orElse("");
            if (response.statusCode() != 200 || !type.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                throw failure("unexpected readiness response (HTTP " + response.statusCode() + ")");
            }
            Map<String, String> fields;
            try {
                fields = FlatJson.parseObject(body);
            } catch (IllegalArgumentException malformed) {
                throw failure("malformed readiness response");
            }
            if (!fields.keySet().equals(Set.of("protocol", "proof"))
                    || !DesktopProtocol.VERSION.equals(fields.get("protocol"))) {
                throw failure("unexpected readiness response fields");
            }
            if (!session.verifyReadinessProof(nonce, pid, port, fields.get("proof"))) {
                throw failure("the readiness proof does not match this launch");
            }
        } catch (IOException unreachable) {
            throw failure("the backend port did not answer the readiness challenge");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BackendStartupException(BackendStartupException.Reason.CANCELLED, "readiness check interrupted");
        }
    }

    /** The port is announced after the server binds it; a refused connection is retried until the deadline. */
    private static HttpResponse<InputStream> send(HttpClient client, HttpRequest request, Instant deadline)
            throws IOException, InterruptedException {
        while (true) {
            try {
                return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (ConnectException refused) {
                if (Instant.now().plus(RETRY_DELAY).isAfter(deadline)) {
                    throw refused;
                }
                Thread.sleep(RETRY_DELAY);
            }
        }
    }

    private static BackendStartupException failure(String message) {
        return new BackendStartupException(BackendStartupException.Reason.HANDSHAKE_FAILED, message);
    }
}
