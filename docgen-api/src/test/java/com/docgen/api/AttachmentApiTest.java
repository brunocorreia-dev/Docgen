package com.docgen.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AttachmentApiTest extends ApiTestSupport {
    @Autowired
    StorageConfig.StoragePaths paths;

    private MockHttpServletRequestBuilder upload(String token, String projectId, byte[] body, String name) {
        return authed(post("/api/v1/projects/" + projectId + "/attachments"), token)
                .contentType("application/pdf")
                .header(AttachmentController.NAME_HEADER, name)
                .content(body);
    }

    @Test
    void extractsRedactsAndStoresTextFromAValidPdf() throws Exception {
        String token = token();
        String project = createProject(token, "ollama");
        byte[] pdf = TestPdfs.withText("Requisito: emitir relatorio mensal de estoque.",
                "Chave: gsk_abcdefghijklmnopqrstuvwxyz123456");

        JsonNode attachment = json(upload(token, project, pdf, "especifica%C3%A7%C3%A3o.pdf"), 201);

        assertEquals("especificação.pdf", attachment.path("displayName").asString());
        assertEquals(1, attachment.path("pageCount").asInt());
        assertEquals(pdf.length, attachment.path("sizeBytes").asLong());
        assertTrue(attachment.path("excerpt").asString().contains("relatorio mensal"));
        assertFalse(attachment.path("excerpt").asString().contains("gsk_abcdefghijklmnopqrstuvwxyz123456"));
        assertTrue(attachment.path("excerpt").asString().contains("[REDACTED_SECRET]"));
        assertFalse(attachment.path("truncated").asBoolean());
        assertEquals(1, json(authed(get("/api/v1/projects/" + project + "/attachments"), token), 200)
                .path("attachments").size());
        try (Stream<java.nio.file.Path> staged = Files.list(paths.staging())) {
            assertEquals(0, staged.count(), "staging files must be deleted after processing");
        }
    }

    @Test
    void clientFileNamesAreDisplayMetadataOnly() throws Exception {
        String token = token();
        String project = createProject(token, "ollama");

        JsonNode attachment = json(upload(token, project, TestPdfs.withText("a"), "..%2F..%2Fetc%2Fpasswd.pdf"), 201);

        assertEquals("passwd.pdf", attachment.path("displayName").asString());
        assertEquals("document.pdf", AttachmentService.displayName(".."));
        assertEquals("document.pdf", AttachmentService.displayName(null));
        assertEquals("a b.pdf", AttachmentService.displayName("C:%5Cdir%5Ca%0Ab.pdf"));
    }

    @Test
    void rejectsFilesThatAreNotRealPdfs() throws Exception {
        String token = token();
        String project = createProject(token, "ollama");

        mvc.perform(upload(token, project, "<html><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8), "x.pdf"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("pdf_invalid"));
        mvc.perform(upload(token, project, "%PDF-1.7\nthis is not a pdf".getBytes(StandardCharsets.UTF_8), "x.pdf"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("pdf_invalid"));
        mvc.perform(upload(token, project, new byte[0], "x.pdf"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void rejectsEncryptedAndTextlessPdfsWithClearCodes() throws Exception {
        String token = token();
        String project = createProject(token, "ollama");

        mvc.perform(upload(token, project, TestPdfs.encrypted(), "locked.pdf"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("pdf_encrypted"));
        mvc.perform(upload(token, project, TestPdfs.blankPages(2), "scan.pdf"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("pdf_no_text"))
                .andExpect(jsonPath("$.message").value(
                        "The PDF has no extractable text. Scanned documents (OCR) are not supported."));
    }

    @Test
    void enforcesSizeTypeCountAndDuplicateLimits() throws Exception {
        String token = token();
        String project = createProject(token, "ollama");

        mvc.perform(upload(token, project, new byte[10 * 1024 * 1024 + 1], "big.pdf"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("request_too_large"));
        mvc.perform(authed(post("/api/v1/projects/" + project + "/attachments"), token)
                        .contentType("application/octet-stream").content(TestPdfs.withText("a")))
                .andExpect(status().isUnsupportedMediaType());

        byte[] first = TestPdfs.withText("documento 0");
        json(upload(token, project, first, "0.pdf"), 201);
        mvc.perform(upload(token, project, first, "again.pdf"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("duplicate_attachment"));
        for (int index = 1; index < 5; index++) {
            json(upload(token, project, TestPdfs.withText("documento " + index), index + ".pdf"), 201);
        }
        mvc.perform(upload(token, project, TestPdfs.withText("documento 6"), "6.pdf"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("attachment_limit"));
    }

    @Test
    void attachmentsOfAnotherOwnerCannotBeReachedOrDeleted() throws Exception {
        String token = token();
        String foreignProject = foreignProject();
        String foreignAttachment = foreignAttachment(foreignProject);
        String project = createProject(token, "ollama");
        String id = json(upload(token, project, TestPdfs.withText("a"), "a.pdf"), 201).path("id").asString();

        mvc.perform(upload(token, foreignProject, TestPdfs.withText("b"), "b.pdf")).andExpect(status().isNotFound());
        mvc.perform(authed(delete("/api/v1/projects/" + foreignProject + "/attachments/" + foreignAttachment), token))
                .andExpect(status().isNotFound());
        mvc.perform(authed(delete("/api/v1/projects/" + project + "/attachments/" + foreignAttachment), token))
                .andExpect(status().isNotFound());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM attachments WHERE id = :id").param("id", foreignAttachment)
                .query(Integer.class).single());
        mvc.perform(authed(delete("/api/v1/projects/" + project + "/attachments/" + id), token))
                .andExpect(status().isNoContent());
        assertEquals(0, json(authed(get("/api/v1/projects/" + project + "/attachments"), token), 200)
                .path("attachments").size());
    }
}
