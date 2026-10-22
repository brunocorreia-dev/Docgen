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
class ArtifactRepository {
    private final JdbcClient jdbc;

    ArtifactRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    enum Kind {
        README("README.md", "text/markdown"),
        ARCHITECTURE("ARCHITECTURE.md", "text/markdown"),
        DIAGRAM("architecture.mmd", "text/plain");

        private final String fileName;
        private final String mediaType;

        Kind(String fileName, String mediaType) {
            this.fileName = fileName;
            this.mediaType = mediaType;
        }

        String fileName() {
            return fileName;
        }

        String mediaType() {
            return mediaType;
        }

        boolean editable() {
            return this != DIAGRAM;
        }
    }

    record ArtifactRecord(
            String id,
            String projectId,
            String owner,
            String planId,
            Kind kind,
            String content,
            int revision,
            boolean edited,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    record ArtifactMetadata(
            String id,
            String projectId,
            String projectName,
            String planId,
            Kind kind,
            int revision,
            boolean edited,
            int characters,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    void insert(ArtifactRecord artifact) {
        jdbc.sql("""
                        INSERT INTO artifacts (id, project_id, owner, plan_id, kind, file_name, content, revision,
                            edited, created_at, updated_at)
                        VALUES (:id, :project, :owner, :plan, :kind, :file, :content, :revision, :edited,
                            :created, :updated)
                        """)
                .param("id", artifact.id())
                .param("project", artifact.projectId())
                .param("owner", artifact.owner())
                .param("plan", artifact.planId())
                .param("kind", artifact.kind().name())
                .param("file", artifact.kind().fileName())
                .param("content", artifact.content())
                .param("revision", artifact.revision())
                .param("edited", artifact.edited())
                .param("created", time(artifact.createdAt()))
                .param("updated", time(artifact.updatedAt()))
                .update();
    }

    Optional<ArtifactRecord> find(String id, String projectId, String owner) {
        return jdbc.sql("SELECT * FROM artifacts WHERE id = :id AND project_id = :project AND owner = :owner")
                .param("id", id)
                .param("project", projectId)
                .param("owner", owner)
                .query((rs, row) -> map(rs))
                .optional();
    }

    List<ArtifactMetadata> listForProject(String projectId, String owner) {
        return jdbc.sql(METADATA + " WHERE a.project_id = :project AND a.owner = :owner ORDER BY a.created_at DESC, a.kind")
                .param("project", projectId)
                .param("owner", owner)
                .query((rs, row) -> metadata(rs))
                .list();
    }

    List<ArtifactMetadata> listForOwner(String owner, int limit) {
        return jdbc.sql(METADATA + " WHERE a.owner = :owner ORDER BY a.updated_at DESC LIMIT :limit")
                .param("owner", owner)
                .param("limit", limit)
                .query((rs, row) -> metadata(rs))
                .list();
    }

    List<ArtifactRecord> forPlan(String planId, String projectId, String owner) {
        return jdbc.sql("""
                        SELECT * FROM artifacts WHERE plan_id = :plan AND project_id = :project AND owner = :owner
                        ORDER BY created_at DESC
                        """)
                .param("plan", planId)
                .param("project", projectId)
                .param("owner", owner)
                .query((rs, row) -> map(rs))
                .list();
    }

    boolean updateContent(String id, String projectId, String owner, int expectedRevision, String content,
                          Instant now) {
        return jdbc.sql("""
                        UPDATE artifacts SET content = :content, revision = revision + 1, edited = TRUE,
                            updated_at = :now
                        WHERE id = :id AND project_id = :project AND owner = :owner AND revision = :revision
                        """)
                .param("content", content)
                .param("now", time(now))
                .param("id", id)
                .param("project", projectId)
                .param("owner", owner)
                .param("revision", expectedRevision)
                .update() == 1;
    }

    private static final String METADATA = """
            SELECT a.id, a.project_id, p.name AS project_name, a.plan_id, a.kind, a.revision, a.edited,
                LENGTH(a.content) AS characters, a.created_at, a.updated_at
            FROM artifacts a JOIN projects p ON p.id = a.project_id AND p.owner = a.owner
            """;

    private static ArtifactRecord map(ResultSet rs) throws SQLException {
        return new ArtifactRecord(rs.getString("id"), rs.getString("project_id"), rs.getString("owner"),
                rs.getString("plan_id"), Kind.valueOf(rs.getString("kind")), rs.getString("content"),
                rs.getInt("revision"), rs.getBoolean("edited"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static ArtifactMetadata metadata(ResultSet rs) throws SQLException {
        return new ArtifactMetadata(rs.getString("id"), rs.getString("project_id"), rs.getString("project_name"),
                rs.getString("plan_id"), Kind.valueOf(rs.getString("kind")), rs.getInt("revision"),
                rs.getBoolean("edited"), rs.getInt("characters"), instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }
}
