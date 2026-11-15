package com.docgen.api;

import com.docgen.LLMProviderFactory;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** The default model and the first-run guide flag (migration V4). */
class SettingsApiTest extends ApiTestSupport {
    private JsonNode setDefault(String token, String body, int status) throws Exception {
        return json(authed(put("/api/v1/settings/default-model"), token).contentType(MediaType.APPLICATION_JSON)
                .content(body), status);
    }

    @Test
    void theDefaultModelMustBeAnInstalledLocalCatalogModel() throws Exception {
        String token = token();
        jdbc.sql("DELETE FROM app_settings").update();
        ollama.install("qwen3:8b", false).install("llama3.2:latest", false).install("qwen3:14b", true);

        assertTrue(json(authed(get("/api/v1/settings"), token), 200).path("defaultModel").isNull());
        assertEquals("not_in_catalog", setDefault(token, "{\"model\":\"llama3.2:latest\"}", 422).path("code").asString());
        assertEquals("model_not_installed", setDefault(token, "{\"model\":\"qwen3:32b\"}", 404).path("code").asString());
        assertEquals("remote_model_refused", setDefault(token, "{\"model\":\"qwen3:14b\"}", 422).path("code").asString());

        assertEquals("qwen3:8b", setDefault(token, "{\"model\":\"qwen3:8b\"}", 200).path("defaultModel").asString());
        assertEquals("qwen3:8b", json(authed(get("/api/v1/models"), token), 200).path("defaultModel").asString());

        json(authed(delete("/api/v1/models").param("name", "qwen3:8b"), token), 204);
        assertTrue(json(authed(get("/api/v1/settings"), token), 200).path("defaultModel").isNull(),
                "a removed model stops being the default");
    }

    @Test
    void theDefaultModelCanBeCleared() throws Exception {
        String token = token();
        ollama.install("qwen3:8b", false);
        setDefault(token, "{\"model\":\"qwen3:8b\"}", 200);

        assertTrue(setDefault(token, "{\"model\":null}", 200).path("defaultModel").isNull());
    }

    @Test
    void theFirstRunGuideIsRememberedAndErasingDataBringsItBack() throws Exception {
        String token = token();
        jdbc.sql("DELETE FROM app_settings").update();
        assertFalse(json(authed(get("/api/v1/settings"), token), 200).path("onboardingCompleted").asBoolean());

        JsonNode done = json(authed(put("/api/v1/settings/onboarding"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"completed\":true}"), 200);
        assertTrue(done.path("onboardingCompleted").asBoolean());

        json(authed(delete("/api/v1/data"), token), 204);
        assertFalse(json(authed(get("/api/v1/settings"), token), 200).path("onboardingCompleted").asBoolean());
    }

    @Test
    void projectsWithoutAModelUseTheAppDefault() {
        assertEquals("qwen3:8b", ProviderConfig.modelFor(null, () -> Optional.of("qwen3:8b")));
        assertEquals("qwen3:14b", ProviderConfig.modelFor("qwen3:14b", () -> Optional.of("qwen3:8b")));
        assertEquals(LLMProviderFactory.DEFAULT_MODEL, ProviderConfig.modelFor(" ", Optional::empty));
    }
}
