package com.docgen.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Body policy for state-changing API requests, applied after authorization.
 *
 * <ul>
 *   <li>CSRF defense: every POST/PUT/PATCH must declare a non-"simple" content
 *   type (application/json, or application/pdf for uploads). Cross-site pages
 *   cannot send such requests without a CORS preflight, and the API grants no
 *   cross-origin access. Together with header-only credentials this removes
 *   the ambient-authority conditions CSRF relies on.</li>
 *   <li>Size limits: per-route caps enforced from Content-Length and while
 *   reading, so oversized bodies are never buffered.</li>
 * </ul>
 */
final class RequestPolicyFilter extends OncePerRequestFilter {
    static final int MAX_ANALYSIS_REQUEST_BYTES = 16 * 1024;
    static final int MAX_JSON_REQUEST_BYTES = 64 * 1024;
    static final int MAX_ARTIFACT_REQUEST_BYTES = 1024 * 1024;

    private static final Pattern ATTACHMENT_UPLOAD = Pattern.compile("/api/v1/projects/[^/]+/attachments");
    private static final Pattern ANALYSIS = Pattern.compile("/api/v1/repositories/[^/]+/analysis");
    private static final Pattern ARTIFACT_EDIT = Pattern.compile("/api/v1/projects/[^/]+/artifacts/[^/]+");

    private final long maxAttachmentBytes;

    RequestPolicyFilter(long maxAttachmentBytes) {
        this.maxAttachmentBytes = maxAttachmentBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        return !("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method))
                || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean upload = "POST".equals(request.getMethod()) && ATTACHMENT_UPLOAD.matcher(path).matches();
        MediaType required = upload ? MediaType.APPLICATION_PDF : MediaType.APPLICATION_JSON;
        if (!hasContentType(request, required)) {
            write(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type",
                    "The content type is not supported.");
            return;
        }
        long limit = upload ? maxAttachmentBytes
                : ANALYSIS.matcher(path).matches() ? MAX_ANALYSIS_REQUEST_BYTES
                : "PUT".equals(request.getMethod()) && ARTIFACT_EDIT.matcher(path).matches()
                ? MAX_ARTIFACT_REQUEST_BYTES
                : MAX_JSON_REQUEST_BYTES;
        if (request.getContentLengthLong() > limit) {
            writeTooLarge(response);
            return;
        }
        if (upload) {
            filterChain.doFilter(new LimitedBodyRequest(request, limit), response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes((int) limit + 1);
        if (body.length > limit) {
            writeTooLarge(response);
            return;
        }
        filterChain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private static boolean hasContentType(HttpServletRequest request, MediaType required) {
        String header = request.getContentType();
        if (header == null) {
            return false;
        }
        try {
            MediaType actual = MediaType.parseMediaType(header);
            return required.equalsTypeAndSubtype(actual);
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static void writeTooLarge(HttpServletResponse response) throws IOException {
        write(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "request_too_large",
                "The request exceeds the allowed size.");
    }

    static void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.getWriter().write("{\"status\":" + status + ",\"code\":\"" + code
                + "\",\"message\":\"" + message + "\"}");
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body.clone();
        }

        @Override
        public ServletInputStream getInputStream() {
            return new DelegatingServletInputStream(new ByteArrayInputStream(body));
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    /** Streams the body but fails once more than {@code limit} bytes were read. */
    private static final class LimitedBodyRequest extends HttpServletRequestWrapper {
        private final long limit;

        private LimitedBodyRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            InputStream source = super.getInputStream();
            return new DelegatingServletInputStream(new InputStream() {
                private long read;

                @Override
                public int read() throws IOException {
                    int next = source.read();
                    if (next >= 0 && ++read > limit) {
                        throw new PayloadTooLargeIOException();
                    }
                    return next;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    int count = source.read(buffer, offset, (int) Math.min(length, limit - read + 1));
                    if (count > 0) {
                        read += count;
                        if (read > limit) {
                            throw new PayloadTooLargeIOException();
                        }
                    }
                    return count;
                }
            });
        }

        @Override
        public BufferedReader getReader() {
            throw new IllegalStateException("Uploads are binary");
        }
    }

    static final class PayloadTooLargeIOException extends IOException {
        PayloadTooLargeIOException() {
            super("Request body exceeds its limit");
        }
    }

    private static final class DelegatingServletInputStream extends ServletInputStream {
        private final InputStream input;
        private boolean finished;

        private DelegatingServletInputStream(InputStream input) {
            this.input = input;
        }

        @Override
        public boolean isFinished() {
            return finished;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException("Asynchronous reads are not supported");
        }

        @Override
        public int read() throws IOException {
            int next = input.read();
            finished = next < 0;
            return next;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = input.read(buffer, offset, length);
            finished = count < 0;
            return count;
        }
    }
}
