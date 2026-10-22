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
@Import(ApiTestSupport.FakeProviders.class)
abstract class ApiTestSupport {
    static final String USER = "test-analyst";
    static final String PASSWORD = "test-password-change-me";
    static final String OTHER = "other-analyst";
    static final String OTHER_PASSWORD = "other-password-change-me";

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper mapper;

    @TestConfiguration
    static class FakeProviders {
        @Bean
        @Primary
        LLMProviderResolver fakeResolver() {
            return FakeLlm.resolver();
        }
    }

    @BeforeEach
    void resetFake() {
        FakeLlm.reset();
    }

    String token(String username, String password) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("accessToken").asString();
    }

    String token() throws Exception {
        return token(USER, PASSWORD);
    }

    static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token).header(SecurityConfig.WEB_CLIENT_HEADER, "web");
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
