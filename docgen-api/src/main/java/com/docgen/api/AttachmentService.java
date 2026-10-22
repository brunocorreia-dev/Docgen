package com.docgen.api;

import com.docgen.SecretRedactor;
import com.docgen.SupportingDocument;
import com.docgen.UntrustedText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Accepts PDF uploads as a raw request body. Bytes are streamed into a
 * randomly named owner-only file in the private staging directory (the client
 * file name is display metadata only, never a path), hashed, parsed, and the
 * staging file is deleted in every outcome. Only redacted, normalized and
 * bounded text is persisted, with an expiry date.
 */
@Service
class AttachmentService {
    private static final Logger LOG = LoggerFactory.getLogger(AttachmentService.class);
    static final int EXCERPT_CHARACTERS = 600;

    private final AttachmentRepository attachments;
    private final ProjectService projects;
    private final PdfTextExtractor extractor;
    private final StorageConfig.StoragePaths paths;
    private final DocGenApiProperties.Attachments limits;
    private final Clock clock;

    AttachmentService(
            AttachmentRepository attachments,
            ProjectService projects,
            PdfTextExtractor extractor,
            StorageConfig.StoragePaths paths,
            DocGenApiProperties properties,
            Clock clock
    ) {
        this.attachments = attachments;
        this.projects = projects;
        this.extractor = extractor;
        this.paths = paths;
        this.limits = properties.attachments();
        this.clock = clock;
    }

    record AttachmentView(
            String id,
            String displayName,
            long sizeBytes,
            int pageCount,
            int characterCount,
            boolean truncated,
            Instant createdAt,
            Instant expiresAt,
            String excerpt
    ) {
    }

    record AttachmentList(List<AttachmentView> attachments) {
    }

    AttachmentView upload(String owner, String projectId, String encodedName, InputStream body) throws InterruptedException {
        ProjectRepository.ProjectRecord project = projects.require(owner, projectId);
        if (attachments.count(project.id(), owner) >= limits.maxPerProject()) {
            throw new ConflictException("attachment_limit", "The attachment limit for this project was reached.");
        }
        String displayName = displayName(encodedName);
        Path staged = null;
        try {
            staged = createStagingFile();
            Staged copy = copyBounded(body, staged);
            if (copy.size() == 0) {
                throw new UnprocessableContentException("pdf_invalid", "The file is not a valid PDF document.");
            }
            if (attachments.existsWithDigest(project.id(), owner, copy.sha256())) {
                throw new ConflictException("duplicate_attachment", "This document is already attached.");
            }
            PdfTextExtractor.Extraction extraction;
            try {
                extraction = extractor.extract(staged);
            } catch (PdfTextExtractor.PdfRejectedException rejected) {
                throw rejection(rejected.code());
            }
            String text = SecretRedactor.redact(extraction.text());
            Instant now = clock.instant();
            AttachmentRepository.AttachmentRecord record = new AttachmentRepository.AttachmentRecord(
                    Ids.random(), project.id(), owner, displayName, copy.size(), extraction.pages(),
                    text.length(), extraction.truncated(), copy.sha256(), now, now.plus(limits.retention()));
            attachments.insert(record, text);
            projects.touch(owner, project.id());
            return view(record, excerpt(text));
        } catch (IOException failure) {
            if (failure instanceof RequestPolicyFilter.PayloadTooLargeIOException || failure instanceof TooLarge) {
                throw new PayloadTooLargeException();
            }
            LOG.warn("Attachment staging failed ({})", failure.getClass().getSimpleName());
            throw new IllegalStateException("Attachment staging failed");
        } finally {
            if (staged != null) {
                try {
                    Files.deleteIfExists(staged);
                } catch (IOException cleanup) {
                    LOG.warn("Could not delete a staged upload ({})", cleanup.getClass().getSimpleName());
                }
            }
        }
    }

    AttachmentList list(String owner, String projectId) {
        ProjectRepository.ProjectRecord project = projects.require(owner, projectId);
        return new AttachmentList(attachments.list(project.id(), owner).stream()
                .map(record -> view(record, attachments.excerpt(record.id(), owner, EXCERPT_CHARACTERS).orElse("")))
                .toList());
    }

    void delete(String owner, String projectId, String attachmentId) {
        ProjectRepository.ProjectRecord project = projects.require(owner, projectId);
        if (!attachments.delete(Ids.require(attachmentId), project.id(), owner)) {
            throw new ResourceNotFoundException();
        }
        projects.touch(owner, project.id());
    }

    List<SupportingDocument> documents(String owner, String projectId) {
        return attachments.texts(projectId, owner).stream()
                .map(item -> new SupportingDocument(item.displayName(), item.text(), item.truncated()))
                .toList();
    }

    private record Staged(long size, String sha256) {
    }

    private static final class TooLarge extends IOException {
    }

    private Staged copyBounded(InputStream body, Path target) throws IOException {
        MessageDigest digest = sha256();
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            int read;
            while ((read = body.read(buffer)) >= 0) {
                total += read;
                if (total > limits.maxBytes()) {
                    throw new TooLarge();
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        return new Staged(total, HexFormat.of().formatHex(digest.digest()));
    }

    private Path createStagingFile() throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return Files.createTempFile(paths.staging(), "upload-", ".bin",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        return Files.createTempFile(paths.staging(), "upload-", ".bin");
    }

    /** Client-supplied names are decoded, reduced to their last segment and normalized for display only. */
    static String displayName(String encodedName) {
        String decoded;
        try {
            decoded = encodedName == null ? "" : URLDecoder.decode(
                    encodedName.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            decoded = "";
        }
        int slash = Math.max(decoded.lastIndexOf('/'), decoded.lastIndexOf('\\'));
        String name = UntrustedText.line(decoded.substring(slash + 1), SupportingDocument.MAX_DISPLAY_NAME);
        return name.isBlank() || name.equals(".") || name.equals("..") ? "document.pdf" : name;
    }

    private static UnprocessableContentException rejection(String code) {
        return switch (code) {
            case "pdf_encrypted" -> new UnprocessableContentException(code,
                    "Encrypted or password-protected PDFs are not supported.");
            case "pdf_too_many_pages" -> new UnprocessableContentException(code,
                    "The PDF exceeds the page limit.");
            case "pdf_no_text" -> new UnprocessableContentException(code,
                    "The PDF has no extractable text. Scanned documents (OCR) are not supported.");
            case "pdf_timeout" -> new UnprocessableContentException(code,
                    "The PDF took too long to process.");
            default -> new UnprocessableContentException("pdf_invalid", "The file is not a valid PDF document.");
        };
    }

    private static String excerpt(String text) {
        return text.length() <= EXCERPT_CHARACTERS ? text : text.substring(0, EXCERPT_CHARACTERS);
    }

    private static AttachmentView view(AttachmentRepository.AttachmentRecord record, String excerpt) {
        return new AttachmentView(record.id(), record.displayName(), record.sizeBytes(), record.pageCount(),
                record.characterCount(), record.truncated(), record.createdAt(), record.expiresAt(), excerpt);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }
}
