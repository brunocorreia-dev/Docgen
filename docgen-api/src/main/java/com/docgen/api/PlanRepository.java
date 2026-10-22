package com.docgen.api;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.docgen.api.ProjectRepository.instant;
import static com.docgen.api.ProjectRepository.time;

@Repository
class PlanRepository {
    private final JdbcClient jdbc;

    PlanRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    enum Status {
        DRAFT, APPROVED
    }

    record PlanRecord(
            String id,
            String projectId,
            String owner,
            Status status,
            String provider,
            String model,
            String planJson,
            String mermaid,
            String warningsJson,
            Instant createdAt,
            Instant approvedAt
    ) {
    }

    void insert(PlanRecord plan) {
        jdbc.sql("""
                        INSERT INTO plans (id, project_id, owner, status, provider, model, plan_json, mermaid,
                            warnings_json, created_at, approved_at)
                        VALUES (:id, :project, :owner, :status, :provider, :model, :plan, :mermaid, :warnings,
                            :created, :approved)
                        """)
                .param("id", plan.id())
                .param("project", plan.projectId())
                .param("owner", plan.owner())
                .param("status", plan.status().name())
                .param("provider", plan.provider())
                .param("model", plan.model())
                .param("plan", plan.planJson())
                .param("mermaid", plan.mermaid())
                .param("warnings", plan.warningsJson())
                .param("created", time(plan.createdAt()))
                .param("approved", time(plan.approvedAt()))
                .update();
    }

    Optional<PlanRecord> find(String id, String projectId, String owner) {
        return jdbc.sql("SELECT * FROM plans WHERE id = :id AND project_id = :project AND owner = :owner")
                .param("id", id)
                .param("project", projectId)
                .param("owner", owner)
                .query((rs, row) -> map(rs))
                .optional();
    }

    List<PlanRecord> list(String projectId, String owner) {
        return jdbc.sql("SELECT * FROM plans WHERE project_id = :project AND owner = :owner ORDER BY created_at DESC")
                .param("project", projectId)
                .param("owner", owner)
                .query((rs, row) -> map(rs))
                .list();
    }

    boolean approve(String id, String projectId, String owner, Instant now) {
        return jdbc.sql("""
                        UPDATE plans SET status = 'APPROVED', approved_at = :now
                        WHERE id = :id AND project_id = :project AND owner = :owner AND status = 'DRAFT'
                        """)
                .param("id", id)
                .param("project", projectId)
                .param("owner", owner)
                .param("now", time(now))
                .update() == 1;
    }

    private static PlanRecord map(ResultSet rs) throws SQLException {
        return new PlanRecord(rs.getString("id"), rs.getString("project_id"), rs.getString("owner"),
                Status.valueOf(rs.getString("status")), rs.getString("provider"), rs.getString("model"),
                rs.getString("plan_json"), rs.getString("mermaid"), rs.getString("warnings_json"),
                instant(rs, "created_at"), instant(rs, "approved_at"));
    }
}
