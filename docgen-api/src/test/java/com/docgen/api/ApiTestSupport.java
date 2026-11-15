package com.docgen.api;

import com.docgen.LLMProviderResolver;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ApiTestSupport.FakeProviders.class, LoopbackMockMvc.class})
abstract class ApiTestSupport {
    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper mapper;

    @Autowired
    LaunchTokenService launchTokens;

    @Autowired
    JdbcClient jdbc;

    /**
     * There are no accounts any more, but the owner column and the owner
     * filter in every query stay as a second line of defense (ADR 0002). Rows
     * of another owner, written straight to the database, must stay invisible
     * to the local session.
     */
    static final String FOREIGN_OWNER = "former-account";

    @Autowired
    FakeOllama ollama;

    @Autowired
    FakeHardware hardware;

    @TestConfiguration
    static class FakeProviders {
        @Bean
        @Primary
        LLMProviderResolver fakeResolver() {
            return FakeLlm.resolver();
        }

        @Bean
        @Primary
        FakeOllama fakeOllama() {
            return new FakeOllama();
        }

        @Bean
        @Primary
        FakeHardware fakeHardware() {
            return new FakeHardware();
        }
    }

    @BeforeEach
    void resetFake() {
        FakeLlm.reset();
        ollama.reset();
        hardware.reset();
    }

    /** A session the way the UI gets one: the launcher issues a launch token and the page exchanges it. */
    String token() throws Exception {
        String body = mvc.perform(post("/api/v1/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"launchToken\":\"" + launchTokens.issue() + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("accessToken").asString();
    }

    static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    static String projectJson(String name, String provider) {
        return """
                {"name": "%s",
                 "description": "Quero construir uma API REST de gerenciamento de estoque usando Java, Spring Boot e PostgreSQL.",
                 "functionalRequirements": ["Cadastrar produtos", "Registrar movimentações"],
                 "nonFunctionalRequirements": ["Autenticação JWT"],
                 "preferredTechnologies": ["Java", "Spring Boot", "PostgreSQL"],
                 "constraints": ["Arquitetura em camadas"],
                 "provider": "%s",
                 "outputLanguage": "pt-BR"}
                """.formatted(name, provider);
    }

    String createProject(String token, String provider) throws Exception {
        String body = mvc.perform(authed(post("/api/v1/projects"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson("Inventory API", provider)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("id").asString();
    }

    String foreignProject() {
        String id = java.util.UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO projects (id, owner, name, description, functional_requirements,
                                              non_functional_requirements, preferred_technologies, constraints_json,
                                              provider, model, output_language, created_at, updated_at)
                        VALUES (:id, :owner, 'Foreign', 'Not yours', '[]', '[]', '[]', '[]', 'ollama', NULL,
                                'pt-BR', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """)
                .param("id", id).param("owner", FOREIGN_OWNER).update();
        return id;
    }

    String foreignJob(String projectId) {
        String id = java.util.UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO jobs (id, owner, project_id, type, status, provider, model, created_at, updated_at)
                        VALUES (:id, :owner, :project, 'ARCHITECTURE_PLAN', 'SUCCEEDED', 'ollama', NULL,
                                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """)
                .param("id", id).param("owner", FOREIGN_OWNER).param("project", projectId).update();
        return id;
    }

    String foreignAttachment(String projectId) {
        String id = java.util.UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO attachments (id, project_id, owner, display_name, size_bytes, page_count,
                                                 character_count, truncated, sha256, extracted_text, created_at,
                                                 expires_at)
                        VALUES (:id, :project, :owner, 'foreign.pdf', 1, 1, 1, FALSE, :sha, 'x', CURRENT_TIMESTAMP,
                                DATEADD('DAY', 1, CURRENT_TIMESTAMP))
                        """)
                .param("id", id).param("project", projectId).param("owner", FOREIGN_OWNER)
                .param("sha", "0".repeat(64)).update();
        return id;
    }

    JsonNode json(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        String body = mvc.perform(request).andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return body.isEmpty() ? mapper.createObjectNode() : mapper.readTree(body);
    }

    /** Polls a job like the web client does until it reaches a terminal state. */
    JsonNode awaitJob(String token, String jobId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            JsonNode job = json(authed(get("/api/v1/jobs/" + jobId), token), 200);
            String status = job.path("status").asString();
            if (status.equals("SUCCEEDED") || status.equals("FAILED") || status.equals("CANCELLED")) {
                return job;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("job did not finish");
    }

    JsonNode awaitStatus(String token, String jobId, String expected) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            JsonNode job = json(authed(get("/api/v1/jobs/" + jobId), token), 200);
            if (job.path("status").asString().equals(expected)) {
                return job;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("job never reached " + expected);
    }

    static String validPlanJson() {
        return """
                {"summary": "API REST para controle de estoque.",
                 "objectives": ["Centralizar o estoque"],
                 "functionalRequirements": [{"description": "Relatório de estoque baixo", "source": "AI_SUGGESTION"}],
                 "nonFunctionalRequirements": [],
                 "architecture": {"style": "Monólito em camadas", "rationale": "Domínio simples"},
                 "components": [
                   {"id": "web", "name": "Painel Web", "type": "CLIENT", "responsibility": "UI", "technologies": ["HTML"]},
                   {"id": "api", "name": "API", "type": "SERVICE", "responsibility": "Regras", "technologies": ["Spring Boot"]},
                   {"id": "db", "name": "PostgreSQL", "type": "DATABASE", "responsibility": "Dados", "technologies": []}],
                 "relationships": [{"from": "web", "to": "api", "description": "HTTPS"}, {"from": "api", "to": "db", "description": "JDBC"}],
                 "dataFlow": ["Cliente chama a API"], "technologies": [], "integrations": [],
                 "securityConsiderations": ["JWT"], "persistence": {"strategy": "Relacional", "details": []},
                 "implementationPlan": [{"title": "Fundação", "goal": "Base", "tasks": ["Setup"], "deliverables": []}],
                 "risks": [], "decisions": [], "assumptions": [], "openQuestions": []}
                """;
    }
}
