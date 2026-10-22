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

/**
 * Job rows. State changes are compare-and-set updates guarded by the expected
 * current status, so a cancelled or timed-out job can never be overwritten by
 * a worker that finishes late.
 */
@Repository
class JobRepository {
    private final JdbcClient jdbc;

    JobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    enum Status {
        QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED;

        boolean terminal() {
            return this == SUCCEEDED || this == FAILED || this == CANCELLED;
        }
    }

    enum Type {
        ARCHITECTURE_PLAN, PROJECT_DOCUMENTATION
    }

    record JobRecord(
            String id,
            String owner,
            String projectId,
            Type type,
            Status status,
            String provider,
            String model,
            Instant createdAt,
            Instant updatedAt,
            Instant startedAt,
            Instant finishedAt,
            String resultId,
            String errorCode,
            String errorMessage
    ) {
    }

    void insert(JobRecord job) {
        jdbc.sql("""
                        INSERT INTO jobs (id, owner, project_id, type, status, provider, model, created_at, updated_at)
                        VALUES (:id, :owner, :project, :type, :status, :provider, :model, :created, :updated)
                        """)
                .param("id", job.id())
                .param("owner", job.owner())
                .param("project", job.projectId())
                .param("type", job.type().name())
                .param("status", job.status().name())
                .param("provider", job.provider())
                .param("model", job.model())
                .param("created", time(job.createdAt()))
                .param("updated", time(job.updatedAt()))
                .update();
    }

    boolean markRunning(String id, Instant now) {
        return jdbc.sql("""
                        UPDATE jobs SET status = 'RUNNING', started_at = :now, updated_at = :now
                        WHERE id = :id AND status = 'QUEUED'
                        """)
                .param("id", id)
                .param("now", time(now))
                .update() == 1;
    }

    boolean markSucceeded(String id, String resultId, Instant now) {
        return jdbc.sql("""
                        UPDATE jobs SET status = 'SUCCEEDED', result_id = :result, finished_at = :now, updated_at = :now
                        WHERE id = :id AND status = 'RUNNING'
                        """)
                .param("id", id)
                .param("result", resultId)
                .param("now", time(now))
                .update() == 1;
    }

    boolean finish(String id, Status expected, Status status, String code, String message, Instant now) {
        return jdbc.sql("""
                        UPDATE jobs SET status = :status, error_code = :code, error_message = :message,
                            finished_at = :now, updated_at = :now
                        WHERE id = :id AND status = :expected
                        """)
                .param("id", id)
                .param("expected", expected.name())
                .param("status", status.name())
                .param("code", code)
                .param("message", message)
                .param("now", time(now))
                .update() == 1;
    }

    Optional<JobRecord> find(String id, String owner) {
        return jdbc.sql("SELECT * FROM jobs WHERE id = :id AND owner = :owner")
                .param("id", id)
                .param("owner", owner)
                .query((rs, row) -> map(rs))
                .optional();
    }

    List<JobRecord> listForProject(String projectId, String owner, int limit) {
        return jdbc.sql("SELECT * FROM jobs WHERE project_id = :project AND owner = :owner ORDER BY created_at DESC LIMIT :limit")
                .param("project", projectId)
                .param("owner", owner)
                .param("limit", limit)
                .query((rs, row) -> map(rs))
                .list();
    }

    List<String> activeIdsForProject(String projectId, String owner) {
        return jdbc.sql("""
                        SELECT id FROM jobs WHERE project_id = :project AND owner = :owner
                            AND status IN ('QUEUED', 'RUNNING')
                        """)
                .param("project", projectId)
                .param("owner", owner)
                .query(String.class)
                .list();
    }

    int countActive(String owner) {
        return jdbc.sql("SELECT COUNT(*) FROM jobs WHERE owner = :owner AND status IN ('QUEUED', 'RUNNING')")
                .param("owner", owner)
                .query(Integer.class)
                .single();
    }

    int failUnfinished(String code, String message, Instant now) {
        return jdbc.sql("""
                        UPDATE jobs SET status = 'FAILED', error_code = :code, error_message = :message,
                            finished_at = :now, updated_at = :now
                        WHERE status IN ('QUEUED', 'RUNNING')
                        """)
                .param("code", code)
                .param("message", message)
                .param("now", time(now))
                .update();
    }

    void delete(String id) {
        jdbc.sql("DELETE FROM jobs WHERE id = :id").param("id", id).update();
    }

    int deleteFinishedBefore(Instant cutoff) {
        return jdbc.sql("""
                        DELETE FROM jobs WHERE status IN ('SUCCEEDED', 'FAILED', 'CANCELLED') AND updated_at < :cutoff
                        """)
                .param("cutoff", time(cutoff))
                .update();
    }

    private static JobRecord map(ResultSet rs) throws SQLException {
        return new JobRecord(rs.getString("id"), rs.getString("owner"), rs.getString("project_id"),
                Type.valueOf(rs.getString("type")), Status.valueOf(rs.getString("status")),
                rs.getString("provider"), rs.getString("model"), instant(rs, "created_at"),
                instant(rs, "updated_at"), instant(rs, "started_at"), instant(rs, "finished_at"),
                rs.getString("result_id"), rs.getString("error_code"), rs.getString("error_message"));
    }
}
