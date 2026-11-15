package com.docgen.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/attachments")
class AttachmentController {
    static final String NAME_HEADER = "X-Attachment-Name";

    private final AttachmentService attachments;

    AttachmentController(AttachmentService attachments) {
        this.attachments = attachments;
    }

    /** Body: the raw PDF bytes with Content-Type application/pdf; the name header is display-only. */
    @PostMapping(consumes = "application/pdf")
    ResponseEntity<AttachmentService.AttachmentView> upload(
            @PathVariable("projectId") String projectId,
            @RequestHeader(value = NAME_HEADER, required = false) String name,
            HttpServletRequest request
    ) throws IOException, InterruptedException {
        AttachmentService.AttachmentView view = attachments.upload(
                CurrentUser.username(), projectId, name, request.getInputStream());
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @GetMapping
    AttachmentService.AttachmentList list(@PathVariable("projectId") String projectId) {
        return attachments.list(CurrentUser.username(), projectId);
    }

    @DeleteMapping("/{attachmentId}")
    ResponseEntity<Void> delete(
            @PathVariable("projectId") String projectId,
            @PathVariable("attachmentId") String attachmentId
    ) {
        attachments.delete(CurrentUser.username(), projectId, attachmentId);
        return ResponseEntity.noContent().build();
    }
}
