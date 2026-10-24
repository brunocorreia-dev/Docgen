package com.docgen;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderJsonModeTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void ollamaRequestsJsonFormatOnlyForStructuredCalls() throws Exception {
        OllamaProvider provider = new OllamaProvider("llama3.2", OllamaEndpoint.DEFAULT);

        assertEquals("json", MAPPER.readTree(provider.buildRequestBody("p", true)).path("format").asText());
        assertTrue(MAPPER.readTree(provider.buildRequestBody("p", false)).path("format").isMissingNode());
    }
}
