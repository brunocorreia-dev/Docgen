package com.docgen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderJsonModeTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void ollamaRequestsJsonFormatOnlyForStructuredCalls() throws Exception {
        OllamaProvider provider = new OllamaProvider("llama3.2", OllamaEndpoint.DEFAULT);

        assertEquals("json", MAPPER.readTree(provider.buildRequestBody("p", true, 4096)).path("format").asText());
        assertTrue(MAPPER.readTree(provider.buildRequestBody("p", false, 4096)).path("format").isMissingNode());
    }

    @Test
    void everyRequestCarriesTheContextWindowAndTheAnswerCap() throws Exception {
        JsonNode options = MAPPER.readTree(new OllamaProvider("llama3.2", OllamaEndpoint.DEFAULT)
                .buildRequestBody("p", false, 10_240)).path("options");

        assertEquals(10_240, options.path("num_ctx").asInt());
        assertEquals(OllamaProvider.OUTPUT_RESERVE_TOKENS, options.path("num_predict").asInt());
    }
}
