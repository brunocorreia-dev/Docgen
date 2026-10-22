package com.docgen.api;

import com.docgen.ProjectBrief;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
class ProjectRepository {
    private final JdbcClient jdbc;
    private final JsonColumns json;

    ProjectRepository(JdbcClient jdbc, JsonColumns json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    record ProjectRecord(
            String id,
            String owner,
            ProjectBrief brief,
            String provider,
            String model,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    record ProjectStats(int attachments, int plans, int approvedPlans, int artifacts, int activeJobs) {
    }

    record ProjectWithStats(ProjectRecord project, ProjectStats stats) {
    }

    void insert(ProjectRecord project) {
        jdbc.sql("""
                        INSERT INTO projects (id, owner, name, description, functional_requirements,
                            non_functional_requirements, preferred_technologies, constraints_json, provider, model,
                            output_language, created_at, updated_at)
                        VALUES (:id, :owner, :name, :description, :fr, :nfr, :tech, :constraints, :provider, :model,
                            :language, :created, :updated)
                        """)
                .param("id", project.id())
                .param("owner", project.owner())
                .param("name", project.brief().name())
                .param("description", project.brief().description())
                .param("fr", json.write(project.brief().functionalRequirements()))
                .param("nfr", json.write(project.brief().nonFunctionalRequirements()))
                .param("tech", json.write(project.brief().preferredTechnologies()))
                .param("constraints", json.write(project.brief().constraints()))
                .param("provider", project.provider())
                .param("model", project.model())
                .param("language", project.brief().outputLanguage().tag())
                .param("created", time(project.createdAt()))
                .param("updated", time(project.updatedAt()))
                .update();
    }

    boolean update(ProjectRecord project) {
        return jdbc.sql("""
                        UPDATE projects SET name = :name, description = :description, functional_requirements = :fr,
                            non_functional_requirements = :nfr, preferred_technologies = :tech,
                            constraints_json = :constraints, provider = :provider, model = :model,
                            output_language = :language, updated_at = :updated
                        WHERE id = :id AND owner = :owner
                        """)
                .param("id", project.id())
                .param("owner", project.owner())
                .param("name", project.brief().name())
                .param("description", project.brief().description())
                .param("fr", json.write(project.brief().functionalRequirements()))
                .param("nfr", json.write(project.brief().nonFunctionalRequirements()))
                .param("tech", json.write(project.brief().preferredTechnologies()))
                .param("constraints", json.write(project.brief().constraints()))
                .param("provider", project.provider())
                .param("model", project.model())
                .param("language", project.brief().outputLanguage().tag())
                .param("updated", time(project.updatedAt()))
                .update() == 1;
    }

    Optional<ProjectRecord> find(String id, String owner) {
        return jdbc.sql("SELECT * FROM projects WHERE id = :id AND owner = :owner")
                .param("id", id)
                .param("owner", owner)
                .query((rs, row) -> map(rs))
                .optional();
    }

    List<ProjectWithStats> list(String owner) {
        return jdbc.sql("""
                        SELECT p.*,
                            (SELECT COUNT(*) FROM attachments a WHERE a.project_id = p.id AND a.owner = p.owner) AS attachment_count,
                            (SELECT COUNT(*) FROM plans l WHERE l.project_id = p.id AND l.owner = p.owner) AS plan_count,
                            (SELECT COUNT(*) FROM plans l WHERE l.project_id = p.id AND l.owner = p.owner
                                AND l.status = 'APPROVED') AS approved_count,
                            (SELECT COUNT(*) FROM artifacts r WHERE r.project_id = p.id AND r.owner = p.owner) AS artifact_count,
                            (SELECT COUNT(*) FROM jobs j WHERE j.project_id = p.id AND j.owner = p.owner
                                AND j.status IN ('QUEUED', 'RUNNING')) AS active_jobs
                        FROM projects p WHERE p.owner = :owner ORDER BY p.updated_at DESC
                        """)
                .param("owner", owner)
                .query((rs, row) -> new ProjectWithStats(map(rs), stats(rs)))
                .list();
    }

    ProjectStats stats(String id, String owner) {
        return jdbc.sql("""
                        SELECT
                            (SELECT COUNT(*) FROM attachments a WHERE a.project_id = :id AND a.owner = :owner) AS attachment_count,
                            (SELECT COUNT(*) FROM plans l WHERE l.project_id = :id AND l.owner = :owner) AS plan_count,
                            (SELECT COUNT(*) FROM plans l WHERE l.project_id = :id AND l.owner = :owner
                                AND l.status = 'APPROVED') AS approved_count,
                            (SELECT COUNT(*) FROM artifacts r WHERE r.project_id = :id AND r.owner = :owner) AS artifact_count,
                            (SELECT COUNT(*) FROM jobs j WHERE j.project_id = :id AND j.owner = :owner
                                AND j.status IN ('QUEUED', 'RUNNING')) AS active_jobs
                        """)
                .param("id", id)
                .param("owner", owner)
                .query((rs, row) -> stats(rs))
                .single();
    }

    int count(String owner) {
        return jdbc.sql("SELECT COUNT(*) FROM projects WHERE owner = :owner")
                .param("owner", owner)
                .query(Integer.class)
                .single();
    }

    boolean delete(String id, String owner) {
        return jdbc.sql("DELETE FROM projects WHERE id = :id AND owner = :owner")
                .param("id", id)
                .param("owner", owner)
                .update() == 1;
    }

    void touch(String id, String owner, Instant now) {
        jdbc.sql("UPDATE projects SET updated_at = :now WHERE id = :id AND owner = :owner")
                .param("now", time(now))
                .param("id", id)
                .param("owner", owner)
                .update();
    }

    private ProjectRecord map(ResultSet rs) throws SQLException {
        ProjectBrief brief = new ProjectBrief(
                rs.getString("name"),
                rs.getString("description"),
                json.read(rs.getString("functional_requirements")),
                json.read(rs.getString("non_functional_requirements")),
                json.read(rs.getString("preferred_technologies")),
                json.read(rs.getString("constraints_json")),
                ProjectBrief.OutputLanguage.fromTag(rs.getString("output_language")));
        return new ProjectRecord(rs.getString("id"), rs.getString("owner"), brief, rs.getString("provider"),
                rs.getString("model"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static ProjectStats stats(ResultSet rs) throws SQLException {
        return new ProjectStats(rs.getInt("attachment_count"), rs.getInt("plan_count"), rs.getInt("approved_count"),
                rs.getInt("artifact_count"), rs.getInt("active_jobs"));
    }

    static OffsetDateTime time(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
