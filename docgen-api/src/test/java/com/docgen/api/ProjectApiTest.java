package com.docgen.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProjectApiTest extends ApiTestSupport {
    @Test
    void createsListsUpdatesAndDeletesProjects() throws Exception {
        String token = token();
        String id = createProject(token, "ollama");

        JsonNode project = json(authed(get("/api/v1/projects/" + id), token), 200);
        assertEquals("Inventory API", project.path("name").asString());
        assertEquals("pt-BR", project.path("outputLanguage").asString());
        assertEquals(2, project.path("functionalRequirements").size());
        assertEquals(0, project.path("stats").path("plans").asInt());

        JsonNode list = json(authed(get("/api/v1/projects"), token), 200);
        assertFalse(list.path("projects").isEmpty());

        mvc.perform(authed(put("/api/v1/projects/" + id), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson("Renamed", "remote-cloud")))
                .andExpect(status().isBadRequest());
        mvc.perform(authed(put("/api/v1/projects/" + id), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson("Renamed", "ollama")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.provider").value("ollama"));

        mvc.perform(authed(delete("/api/v1/projects/" + id), token)).andExpect(status().isNoContent());
        mvc.perform(authed(get("/api/v1/projects/" + id), token)).andExpect(status().isNotFound());
    }

    @Test
    void validatesProjectPayloadsStrictly() throws Exception {
        String token = token();
        String[] invalid = {
                projectJson("", "ollama"),
                projectJson("x".repeat(121), "ollama"),
                projectJson("ok", "openai"),
                projectJson("ok", "ollama").replace("\"provider\"", "\"owner\": \"someone-else\", \"provider\""),
                projectJson("ok", "ollama").replace("\"outputLanguage\": \"pt-BR\"", "\"outputLanguage\": \"fr\""),
                projectJson("ok", "ollama").replace("\"provider\": \"ollama\"", "\"provider\": \"ollama\", \"model\": \"../../x\""),
                "{\"name\": \"x\"}",
                "not json"
        };
        for (String body : invalid) {
            mvc.perform(authed(post("/api/v1/projects"), token).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("invalid_request"));
        }
    }

    @Test
    void normalizesHostileTextButStoresItAsInertData() throws Exception {
        String token = token();
        String body = projectJson("<img src=x onerror=alert(1)>\u202E", "ollama");

        JsonNode created = json(authed(post("/api/v1/projects"), token)
                .contentType(MediaType.APPLICATION_JSON).content(body), 201);

        assertEquals("<img src=x onerror=alert(1)>", created.path("name").asString());
    }

    @Test
    void rowsOfAnotherOwnerStayInvisibleToTheLocalSession() throws Exception {
        String token = token();
        String foreign = foreignProject();
        String own = createProject(token, "ollama");

        mvc.perform(authed(get("/api/v1/projects/" + foreign), token)).andExpect(status().isNotFound());
        mvc.perform(authed(put("/api/v1/projects/" + foreign), token)
                        .contentType(MediaType.APPLICATION_JSON).content(projectJson("pwned", "ollama")))
                .andExpect(status().isNotFound());
        mvc.perform(authed(delete("/api/v1/projects/" + foreign), token)).andExpect(status().isNotFound());
        mvc.perform(authed(get("/api/v1/projects/" + foreign + "/attachments"), token)).andExpect(status().isNotFound());
        mvc.perform(authed(get("/api/v1/projects/" + foreign + "/plans"), token)).andExpect(status().isNotFound());
        mvc.perform(authed(get("/api/v1/projects/" + foreign + "/jobs"), token)).andExpect(status().isNotFound());
        mvc.perform(authed(get("/api/v1/projects/" + foreign + "/artifacts"), token)).andExpect(status().isNotFound());
        JsonNode list = json(authed(get("/api/v1/projects"), token), 200);
        list.path("projects").forEach(item -> assertFalse(item.path("id").asString().equals(foreign)));
        mvc.perform(authed(get("/api/v1/projects/" + own), token)).andExpect(status().isOk());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM projects WHERE id = :id").param("id", foreign)
                .query(Integer.class).single(), "the foreign project was not touched");
    }

    @Test
    void newProjectsBelongToTheFixedLocalOwner() throws Exception {
        String id = createProject(token(), "ollama");

        assertEquals("local", jdbc.sql("SELECT owner FROM projects WHERE id = :id").param("id", id)
                .query(String.class).single());
    }

    @Test
    void malformedIdentifiersBehaveLikeUnknownOnes() throws Exception {
        String token = token();
        mvc.perform(authed(get("/api/v1/projects/../../etc/passwd"), token)).andExpect(status().is4xxClientError());
        mvc.perform(authed(get("/api/v1/projects/not-a-uuid"), token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
        mvc.perform(authed(get("/api/v1/jobs/' OR '1'='1"), token)).andExpect(status().isNotFound());
    }
}
