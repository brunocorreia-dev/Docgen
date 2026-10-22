package com.docgen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderJsonModeTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void groqRequestsAJsonObjectOnlyForStructuredCalls() throws Exception {
        GroqProvider provider = new GroqProvider("model-x", "unused", null);

        JsonNode structured = MAPPER.readTree(provider.buildRequestBody("prompt", true));
        JsonNode documentation = MAPPER.readTree(provider.buildRequestBody("prompt", false));

        assertEquals("json_object", structured.path("response_format").path("type").asText());
        assertTrue(documentation.path("response_format").isMissingNode());
        assertEquals("model-x", structured.path("model").asText());
        assertEquals("prompt", structured.path("messages").path(0).path("content").asText());
    }

    @Test
    void ollamaRequestsJsonFormatOnlyForStructuredCalls() throws Exception {
        OllamaProvider provider = new OllamaProvider("llama3.2", null);

        assertEquals("json", MAPPER.readTree(provider.buildRequestBody("p", true)).path("format").asText());
        assertTrue(MAPPER.readTree(provider.buildRequestBody("p", false)).path("format").isMissingNode());
    }
}
