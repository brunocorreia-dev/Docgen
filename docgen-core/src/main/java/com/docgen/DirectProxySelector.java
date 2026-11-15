package com.docgen;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;

/** Prevents JVM proxy settings from silently changing a fixed provider boundary. */
final class DirectProxySelector extends ProxySelector {
    static final DirectProxySelector INSTANCE = new DirectProxySelector();

    private DirectProxySelector() {
    }

    @Override
    public List<Proxy> select(URI uri) {
        return List.of(Proxy.NO_PROXY);
    }

    @Override
    public void connectFailed(URI uri, SocketAddress socketAddress, IOException failure) {
        // HttpClient reports the connection failure to the caller.
    }
}
