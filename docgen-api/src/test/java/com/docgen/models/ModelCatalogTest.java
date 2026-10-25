package com.docgen.models;

import com.docgen.LLMProviderFactory;
import com.docgen.RemoteModels;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelCatalogTest {
    private static final String ENTRY = """
            {"tag": "%s", "family": "qwen3", "parameterCount": 8190000000, "activeParameterCount": 8190000000,
             "quantization": "Q4_K_M", "downloadSize": "5,2 GB", "downloadBytes": 5250000000, "contextTokens": 40960,
             "architecture": {"layers": 36, "attentionLayers": 36, "kvHeads": 8, "headDim": 128, "stateBytes": 0},
             "purposes": ["general"], "thinking": "hybrid", "vision": false, "description": "x", "verified": true,
             "evidence": "ollama.com/library/qwen3:8b"%s}
            """;

    private static ModelCatalog parse(int version, String... entries) {
        String json = "{\"schemaVersion\": " + version + ", \"verifiedOn\": \"2026-10-25\", \"notes\": [], \"models\": ["
                + String.join(",", entries) + "]}";
        return ModelCatalog.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theShippedCatalogIsCompleteAndVerified() {
        ModelCatalog catalog = ModelCatalog.load();

        assertEquals(LocalDate.of(2026, 10, 25), catalog.verifiedOn());
        assertEquals(16, catalog.models().size());
        assertTrue(catalog.models().stream().allMatch(CatalogModel::verified), "every tag was checked in the library");
        assertTrue(catalog.models().stream().noneMatch(model -> RemoteModels.isCloudName(model.tag())));
        assertTrue(catalog.models().stream().noneMatch(model -> model.tag().contains("/")), "library tags only");
        assertTrue(catalog.contains(LLMProviderFactory.DEFAULT_MODEL), "the CLI default is a catalog model");
        for (CatalogModel.Purpose purpose : CatalogModel.Purpose.values()) {
            assertTrue(catalog.models().stream().anyMatch(model -> model.purposes().contains(purpose)), purpose.name());
        }
        assertTrue(catalog.models().stream().allMatch(model -> model.family().startsWith("qwen")), "only Qwen models");
    }

    @Test
    void reasoningOnlyTagsAreNotInTheCatalog() {
        // qwen3:4b and qwen3:30b point to the thinking-2507 builds, which cannot answer without reasoning.
        ModelCatalog catalog = ModelCatalog.load();
        assertFalse(catalog.contains("qwen3:4b"));
        assertFalse(catalog.contains("qwen3:30b"));
        assertEquals(CatalogModel.Thinking.NONE, catalog.find("qwen3:4b-instruct").orElseThrow().thinking());
    }

    @Test
    void aValidEntryParses() {
        ModelCatalog catalog = parse(1, ENTRY.formatted("qwen3:8b", ""));

        CatalogModel model = catalog.find("qwen3:8b").orElseThrow();
        assertEquals(List.of(CatalogModel.Purpose.GENERAL), model.purposes());
        assertEquals(CatalogModel.Thinking.HYBRID, model.thinking());
    }

    @Test
    void brokenCatalogsStopTheAppInsteadOfMisleading() {
        assertThrows(IllegalStateException.class, () -> parse(2, ENTRY.formatted("qwen3:8b", "")), "schema version");
        assertThrows(IllegalStateException.class, () -> parse(1), "no models");
        assertThrows(IllegalStateException.class,
                () -> parse(1, ENTRY.formatted("qwen3:8b", ""), ENTRY.formatted("qwen3:8b", "")), "duplicate");
        assertThrows(IllegalStateException.class, () -> parse(1, ENTRY.formatted("qwen3:480b-cloud", "")), "cloud tag");
        assertThrows(IllegalStateException.class, () -> parse(1, ENTRY.formatted("evil.example/qwen3:8b", "")),
                "a registry host would download from elsewhere");
        assertThrows(IllegalStateException.class, () -> parse(1, ENTRY.formatted("qwen3", "")), "no tag");
        assertThrows(RuntimeException.class, () -> parse(1, ENTRY.formatted("qwen3:8b", ", \"extra\": 1")), "unknown field");
        assertThrows(IllegalStateException.class, () -> parse(1, ENTRY.formatted("qwen3:8b", "")
                .replace("\"attentionLayers\": 36", "\"attentionLayers\": 40")), "more attention layers than layers");
    }
}
