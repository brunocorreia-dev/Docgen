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
class AttachmentRepository {
    private final JdbcClient jdbc;

    AttachmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record AttachmentRecord(
            String id,
            String projectId,
            String owner,
            String displayName,
            long sizeBytes,
            int pageCount,
            int characterCount,
            boolean truncated,
            String sha256,
            Instant createdAt,
            Instant expiresAt
    ) {
    }

    record AttachmentText(String displayName, String text, boolean truncated) {
    }

    void insert(AttachmentRecord record, String extractedText) {
        jdbc.sql("""
                        INSERT INTO attachments (id, project_id, owner, display_name, size_bytes, page_count,
                            character_count, truncated, sha256, extracted_text, created_at, expires_at)
                        VALUES (:id, :project, :owner, :name, :size, :pages, :chars, :truncated, :sha, :text,
                            :created, :expires)
                        """)
                .param("id", record.id())
                .param("project", record.projectId())
                .param("owner", record.owner())
                .param("name", record.displayName())
                .param("size", record.sizeBytes())
                .param("pages", record.pageCount())
                .param("chars", record.characterCount())
                .param("truncated", record.truncated())
                .param("sha", record.sha256())
                .param("text", extractedText)
                .param("created", time(record.createdAt()))
                .param("expires", time(record.expiresAt()))
                .update();
    }

    List<AttachmentRecord> list(String projectId, String owner) {
        return jdbc.sql("""
                        SELECT id, project_id, owner, display_name, size_bytes, page_count, character_count, truncated,
                            sha256, created_at, expires_at
                        FROM attachments WHERE project_id = :project AND owner = :owner ORDER BY created_at
                        """)
                .param("project", projectId)
                .param("owner", owner)
                .query((rs, row) -> map(rs))
                .list();
    }

    Optional<String> excerpt(String id, String owner, int characters) {
        return jdbc.sql("SELECT SUBSTRING(extracted_text, 1, :chars) FROM attachments WHERE id = :id AND owner = :owner")
                .param("chars", characters)
                .param("id", id)
                .param("owner", owner)
                .query(String.class)
                .optional();
    }

    List<AttachmentText> texts(String projectId, String owner) {
        return jdbc.sql("""
                        SELECT display_name, extracted_text, truncated FROM attachments
                        WHERE project_id = :project AND owner = :owner ORDER BY created_at
                        """)
                .param("project", projectId)
                .param("owner", owner)
                .query((rs, row) -> new AttachmentText(rs.getString(1), rs.getString(2), rs.getBoolean(3)))
                .list();
    }

    int count(String projectId, String owner) {
        return jdbc.sql("SELECT COUNT(*) FROM attachments WHERE project_id = :project AND owner = :owner")
                .param("project", projectId)
                .param("owner", owner)
                .query(Integer.class)
                .single();
    }

    boolean existsWithDigest(String projectId, String owner, String sha256) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM attachments WHERE project_id = :project AND owner = :owner AND sha256 = :sha
                        """)
                .param("project", projectId)
                .param("owner", owner)
                .param("sha", sha256)
                .query(Integer.class)
                .single() > 0;
    }

    boolean delete(String id, String projectId, String owner) {
        return jdbc.sql("DELETE FROM attachments WHERE id = :id AND project_id = :project AND owner = :owner")
                .param("id", id)
                .param("project", projectId)
                .param("owner", owner)
                .update() == 1;
    }

    int deleteExpired(Instant now) {
        return jdbc.sql("DELETE FROM attachments WHERE expires_at <= :now")
                .param("now", time(now))
                .update();
    }

    private static AttachmentRecord map(ResultSet rs) throws SQLException {
        return new AttachmentRecord(rs.getString("id"), rs.getString("project_id"), rs.getString("owner"),
                rs.getString("display_name"), rs.getLong("size_bytes"), rs.getInt("page_count"),
                rs.getInt("character_count"), rs.getBoolean("truncated"), rs.getString("sha256"),
                instant(rs, "created_at"), instant(rs, "expires_at"));
    }
}
