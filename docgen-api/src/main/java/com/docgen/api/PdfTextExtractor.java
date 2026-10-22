package com.docgen.api;

import com.docgen.UntrustedText;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Extracts plain text from an uploaded PDF with Apache PDFBox.
 *
 * <p>Only text extraction is performed: nothing is rendered, no JavaScript,
 * form action, embedded file or link is executed or followed. The parser runs
 * on a small dedicated pool with a wall-clock deadline, a bounded memory and
 * scratch-file budget inside the private staging directory, page and character
 * caps, and cooperative cancellation between pages. Encrypted PDFs are
 * rejected, as are PDFs without an extractable text layer (scanned images;
 * OCR is not supported).</p>
 */
@Component
final class PdfTextExtractor {
    static final Duration DEADLINE = Duration.ofSeconds(60);
    private static final byte[] MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final long MAX_MAIN_MEMORY = 32L * 1024 * 1024;
    private static final long MAX_SCRATCH = 256L * 1024 * 1024;

    private final ExecutorService pool;
    private final Path scratchDirectory;
    private final int maxPages;
    private final int maxCharacters;

    PdfTextExtractor(DocGenApiProperties properties, StorageConfig.StoragePaths paths) {
        AtomicInteger counter = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "docgen-pdf-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.scratchDirectory = paths.staging();
        this.maxPages = properties.attachments().maxPages();
        this.maxCharacters = properties.attachments().maxCharacters();
    }

    record Extraction(String text, int pages, boolean truncated) {
    }

    static final class PdfRejectedException extends Exception {
        private final String code;

        PdfRejectedException(String code) {
            super(code);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    Extraction extract(Path file) throws PdfRejectedException, InterruptedException {
        requireMagic(file);
        Future<Extraction> task = pool.submit(() -> parse(file));
        try {
            return task.get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            task.cancel(true);
            throw new PdfRejectedException("pdf_timeout");
        } catch (InterruptedException interrupted) {
            task.cancel(true);
            throw interrupted;
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof PdfRejectedException rejected) {
                throw rejected;
            }
            throw new PdfRejectedException("pdf_invalid");
        }
    }

    private Extraction parse(Path file) throws PdfRejectedException, IOException {
        MemoryUsageSetting memory = MemoryUsageSetting.setupMixed(MAX_MAIN_MEMORY, MAX_SCRATCH)
                .setTempDir(scratchDirectory.toFile());
        PDDocument document;
        try {
            document = Loader.loadPDF(file.toFile(), "", memory.streamCache);
        } catch (InvalidPasswordException encrypted) {
            throw new PdfRejectedException("pdf_encrypted");
        } catch (IOException | RuntimeException malformed) {
            throw new PdfRejectedException("pdf_invalid");
        }
        try (document) {
            if (document.isEncrypted()) {
                throw new PdfRejectedException("pdf_encrypted");
            }
            int pages = document.getNumberOfPages();
            if (pages <= 0) {
                throw new PdfRejectedException("pdf_invalid");
            }
            if (pages > maxPages) {
                throw new PdfRejectedException("pdf_too_many_pages");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            StringBuilder text = new StringBuilder(Math.min(maxCharacters, 64 * 1024));
            boolean truncated = false;
            for (int page = 1; page <= pages; page++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new PdfRejectedException("pdf_timeout");
                }
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText;
                try {
                    pageText = stripper.getText(document);
                } catch (IOException | RuntimeException unreadable) {
                    throw new PdfRejectedException("pdf_invalid");
                }
                int remaining = maxCharacters - text.length();
                UntrustedText.Bounded bounded = UntrustedText.normalize(pageText, Math.max(1, remaining), true);
                if (!bounded.text().isEmpty()) {
                    if (!text.isEmpty()) {
                        text.append("\n\n");
                    }
                    text.append(bounded.text());
                }
                if (bounded.truncated() || text.length() >= maxCharacters) {
                    truncated = page < pages || bounded.truncated();
                    break;
                }
            }
            String normalized = UntrustedText.block(text.toString(), maxCharacters);
            if (normalized.isBlank()) {
                throw new PdfRejectedException("pdf_no_text");
            }
            return new Extraction(normalized, pages, truncated);
        }
    }

    private static void requireMagic(Path file) throws PdfRejectedException {
        try (InputStream input = Files.newInputStream(file)) {
            byte[] header = input.readNBytes(MAGIC.length);
            for (int index = 0; index < MAGIC.length; index++) {
                if (header.length != MAGIC.length || header[index] != MAGIC[index]) {
                    throw new PdfRejectedException("pdf_invalid");
                }
            }
        } catch (IOException unreadable) {
            throw new PdfRejectedException("pdf_invalid");
        }
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
