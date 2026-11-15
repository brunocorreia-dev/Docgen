package com.docgen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptTokensTest {
    @Test
    void neverEstimatesBelowWhatOllamaMeasuredForEachClassOfText() throws IOException {
        JsonNode measured = resource("measured.json", node -> new ObjectMapper().readTree(node));
        Iterator<Map.Entry<String, JsonNode>> samples = measured.path("samples").fields();
        int checked = 0;
        while (samples.hasNext()) {
            Map.Entry<String, JsonNode> sample = samples.next();
            String text = resource(sample.getKey(), stream -> new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            int real = sample.getValue().asInt();
            int estimate = PromptTokens.estimate(text);

            assertTrue(estimate >= real, sample.getKey() + ": estimated " + estimate + " below measured " + real);
            assertTrue(estimate <= real * 2.5, sample.getKey() + ": estimated " + estimate
                    + " is so far above " + real + " that ordinary prompts would stop fitting");
            checked++;
        }
        assertEquals(11, checked, "every calibration sample is checked");
    }

    @Test
    void digitsAndDenseDataCostMoreThanProse() {
        int prose = PromptTokens.estimate("a".repeat(300));
        int digits = PromptTokens.estimate("7".repeat(300));
        int base64 = PromptTokens.estimate("QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo0NTY3ODkw".repeat(7));

        assertEquals(PromptTokens.TEMPLATE_ALLOWANCE + 110, prose);
        assertEquals(PromptTokens.TEMPLATE_ALLOWANCE + 330, digits);
        assertTrue(base64 - PromptTokens.TEMPLATE_ALLOWANCE > 2 * (prose - PromptTokens.TEMPLATE_ALLOWANCE));
    }

    @Test
    void charactersOutsideTheBasicPlaneCountAsSeveralTokens() {
        assertEquals(PromptTokens.TEMPLATE_ALLOWANCE + 5, PromptTokens.estimate("🚀"));
        assertEquals(PromptTokens.TEMPLATE_ALLOWANCE + 2, PromptTokens.estimate("ã"));
        assertEquals(PromptTokens.TEMPLATE_ALLOWANCE, PromptTokens.estimate(""));
    }

    private interface Reader<T> {
        T read(InputStream stream) throws IOException;
    }

    private static <T> T resource(String name, Reader<T> reader) throws IOException {
        try (InputStream stream = PromptTokensTest.class.getResourceAsStream("/token-calibration/" + name)) {
            assertNotNull(stream, name);
            return reader.read(stream);
        }
    }
}
