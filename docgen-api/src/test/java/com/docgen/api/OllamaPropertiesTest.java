package com.docgen.api;

import com.docgen.OllamaEndpoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OllamaPropertiesTest {
    @Test
    void anUnsetPortSelectsTheStandardOllamaPort() {
        assertEquals(OllamaEndpoint.DEFAULT, new DocGenApiProperties.Ollama(0).endpoint());
        assertEquals(new OllamaEndpoint(18_080), new DocGenApiProperties.Ollama(18_080).endpoint());
    }

    @Test
    void anOutOfRangePortNeverBecomesAnEndpoint() {
        assertThrows(IllegalArgumentException.class, () -> new DocGenApiProperties.Ollama(70_000).endpoint());
    }
}
