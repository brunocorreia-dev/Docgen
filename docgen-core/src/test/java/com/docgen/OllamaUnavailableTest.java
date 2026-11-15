package com.docgen;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.http.HttpConnectTimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ollama that is not running is reported as unavailable, not as a failed request (ROADMAP §6.6). */
class OllamaUnavailableTest {
    @Test
    void aClosedPortIsReportedAsOllamaUnavailable() throws IOException {
        OllamaEndpoint closed = new OllamaEndpoint(closedPort());

        OllamaUnavailableException error = assertThrows(OllamaUnavailableException.class,
                () -> new OllamaProvider("qwen2.5-coder:7b", closed).generateDocumentation("hello"));

        assertTrue(error.getMessage().contains("not reachable"), error.getMessage());
    }

    @Test
    void onlyConnectionFailuresCountAsUnavailable() {
        assertTrue(HttpExchange.refusedConnection(new ConnectException("refused")));
        assertTrue(HttpExchange.refusedConnection(new IOException(new HttpConnectTimeoutException("slow"))));
        assertFalse(HttpExchange.refusedConnection(new IOException("reset by peer")));
        assertFalse(HttpExchange.refusedConnection(null));
    }

    /** A port that was free a moment ago; nothing listens on it once the socket closes. */
    static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
