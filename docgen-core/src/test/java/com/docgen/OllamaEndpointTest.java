package com.docgen;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OllamaEndpointTest {
    @Test
    void defaultsToTheStandardOllamaPortOnTheLoopbackLiteral() {
        assertEquals(URI.create("http://127.0.0.1:11434/api/generate"), OllamaEndpoint.DEFAULT.api("generate"));
        assertEquals("http://127.0.0.1:11434", OllamaEndpoint.DEFAULT.toString());
    }

    @Test
    void onlyThePortCanChangeAndTheHostStaysOnLoopback() {
        URI uri = new OllamaEndpoint(18_080).api("show");

        assertEquals("127.0.0.1", uri.getHost());
        assertEquals(18_080, uri.getPort());
        assertEquals("http", uri.getScheme());
    }

    @Test
    void rejectsPortsOutsideTheTcpRange() {
        for (int port : new int[]{0, -1, 65_536, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new OllamaEndpoint(port), String.valueOf(port));
        }
    }
}
