package com.docgen.api;

import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/** Serializes small string lists stored in CLOB columns. */
@Component
final class JsonColumns {
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private final JsonMapper mapper;

    JsonColumns(JsonMapper mapper) {
        this.mapper = mapper;
    }

    String write(List<String> values) {
        return mapper.writeValueAsString(values == null ? List.of() : values);
    }

    List<String> read(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return List.copyOf(mapper.readValue(json, STRINGS));
    }
}
