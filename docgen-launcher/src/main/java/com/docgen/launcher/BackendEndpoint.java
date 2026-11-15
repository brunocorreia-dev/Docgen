package com.docgen.launcher;

import java.net.URI;

/**
 * A verified backend: the loopback port it listens on and its process id.
 * The workspace address carries only a route, never a credential.
 */
record BackendEndpoint(int port, long pid) {
    static final String HOST = "127.0.0.1";

    URI baseUri() {
        return URI.create("http://" + HOST + ":" + port + "/");
    }

    URI workspaceUri() {
        return URI.create("http://" + HOST + ":" + port + "/#/desktop");
    }
}
