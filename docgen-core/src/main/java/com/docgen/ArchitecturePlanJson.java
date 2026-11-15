package com.docgen;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;

/**
 * Stable storage format for plans that already passed {@link ArchitecturePlanParser}.
 * Reading is strict (unknown properties fail) because stored plans are written
 * only by this class; it is not a parser for model output.
 */
public final class ArchitecturePlanJson {
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(16)
                    .maxStringLength(64 * 1024)
                    .build())
            .build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private ArchitecturePlanJson() {
    }

    public static String write(ArchitecturePlan plan) {
        try {
            return MAPPER.writeValueAsString(plan);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Architecture plan could not be serialized", failure);
        }
    }

    public static String writePretty(ArchitecturePlan plan) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(plan) + "\n";
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Architecture plan could not be serialized", failure);
        }
    }

    public static ArchitecturePlan read(String json) throws IOException {
        if (json == null || json.length() > ArchitecturePlanParser.MAX_OUTPUT_CHARACTERS) {
            throw new IOException("Stored architecture plan is missing or too large.");
        }
        ArchitecturePlan plan = MAPPER.readValue(json, ArchitecturePlan.class);
        if (plan == null) {
            throw new IOException("Stored architecture plan is empty.");
        }
        return plan;
    }
}
