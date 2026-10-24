package com.docgen;

import java.net.URI;

/**
 * The local Ollama service. The host is the IPv4 loopback literal and cannot be
 * configured: no name resolution (a hosts entry could redirect "localhost") and
 * no proxy. Only the port can change, for an Ollama started on another port.
 */
public record OllamaEndpoint(int port) {
    public static final String HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 11434;
    public static final OllamaEndpoint DEFAULT = new OllamaEndpoint(DEFAULT_PORT);

    public OllamaEndpoint {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("The Ollama port must be between 1 and 65535.");
        }
    }

    /** The address of one fixed API operation, such as {@code generate}. */
    URI api(String operation) {
        return URI.create(this + "/api/" + operation);
    }

    @Override
    public String toString() {
        return "http://" + HOST + ":" + port;
    }
}
