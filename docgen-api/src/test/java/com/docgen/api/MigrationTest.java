package com.docgen.api;

import org.flywaydb.core.Flyway;
import org.h2.Driver;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Runs the real Flyway migrations on a throwaway H2 database, one version at a time. */
class MigrationTest {
    @Test
    void v2MovesProjectsToOllamaAndKeepsPlanHistory() {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(new Driver(),
                "jdbc:h2:mem:migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "docgen", "");
        migrateTo(dataSource, "1");
        JdbcClient jdbc = JdbcClient.create(dataSource);
        insertProject(jdbc, "11111111-1111-1111-1111-111111111111", "remote-cloud", "large-remote-model");
        insertProject(jdbc, "22222222-2222-2222-2222-222222222222", "ollama", "qwen2.5-coder:7b");
        jdbc.sql("""
                        INSERT INTO plans (id, project_id, owner, status, provider, model, plan_json, mermaid,
                                           warnings_json, created_at)
                        VALUES ('33333333-3333-3333-3333-333333333333', '11111111-1111-1111-1111-111111111111',
                                'local', 'DRAFT', 'remote-cloud', 'large-remote-model', '{}', '', '[]',
                                CURRENT_TIMESTAMP)
                        """)
                .update();

        migrateTo(dataSource, "2");

        Map<String, Object> moved = project(jdbc, "11111111-1111-1111-1111-111111111111");
        assertEquals("ollama", moved.get("PROVIDER"));
        assertNull(moved.get("MODEL"), "a remote model name is meaningless for Ollama");
        Map<String, Object> untouched = project(jdbc, "22222222-2222-2222-2222-222222222222");
        assertEquals("ollama", untouched.get("PROVIDER"));
        assertEquals("qwen2.5-coder:7b", untouched.get("MODEL"));
        assertEquals(List.of("remote-cloud"), jdbc.sql("SELECT provider FROM plans").query(String.class).list(),
                "plans keep the provider that actually produced them");
    }

    private static void migrateTo(SimpleDriverDataSource dataSource, String version) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(version)
                .load()
                .migrate();
    }

    private static void insertProject(JdbcClient jdbc, String id, String provider, String model) {
        jdbc.sql("""
                        INSERT INTO projects (id, owner, name, description, functional_requirements,
                                              non_functional_requirements, preferred_technologies, constraints_json,
                                              provider, model, output_language, created_at, updated_at)
                        VALUES (:id, 'local', 'Projeto', 'Descrição', '[]', '[]', '[]', '[]', :provider, :model,
                                'pt-BR', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """)
                .param("id", id)
                .param("provider", provider)
                .param("model", model)
                .update();
    }

    private static Map<String, Object> project(JdbcClient jdbc, String id) {
        return jdbc.sql("SELECT provider, model FROM projects WHERE id = :id").param("id", id).query().singleRow();
    }
}
