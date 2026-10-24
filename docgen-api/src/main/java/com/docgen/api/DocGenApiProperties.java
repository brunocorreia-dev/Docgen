package com.docgen.api;

import com.docgen.OllamaEndpoint;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Server-side configuration. Everything here is operator-controlled; nothing
 * is ever bound from an HTTP request.
 */
@Validated
@ConfigurationProperties(prefix = "docgen")
public record DocGenApiProperties(
        @Valid Api api,
        Map<String, String> repositories,
        @Valid Storage storage,
        @Valid Ollama ollama,
        @Valid Jobs jobs,
        @Valid Attachments attachments,
        @Valid Auth auth
) {
    @ConstructorBinding
    public DocGenApiProperties {
        repositories = repositories == null ? Map.of() : Map.copyOf(repositories);
        storage = Objects.requireNonNullElseGet(storage, () -> new Storage(null, false));
        ollama = Objects.requireNonNullElseGet(ollama, () -> new Ollama(0));
        jobs = Objects.requireNonNullElseGet(jobs, () -> new Jobs(0, 0, 0, null));
        attachments = Objects.requireNonNullElseGet(attachments, () -> new Attachments(0, 0, 0, 0, null));
        auth = Objects.requireNonNullElseGet(auth, () -> new Auth(null, null));
    }

    /** Compatibility constructor used by tests that only need credentials and repositories. */
    DocGenApiProperties(Api api, Map<String, String> repositories) {
        this(api, repositories, null, null, null, null, null);
    }

    public record Api(
            @NotBlank
            @Pattern(regexp = "[A-Za-z0-9._-]{1,64}")
            String username,
            @NotBlank
            @Size(min = 12, max = 256)
            String password,
            Map<String, String> additionalUsers,
            List<String> allowedHosts
    ) {
        @ConstructorBinding
        public Api {
            additionalUsers = additionalUsers == null ? Map.of() : Map.copyOf(additionalUsers);
            allowedHosts = allowedHosts == null || allowedHosts.isEmpty()
                    ? List.of("localhost", "127.0.0.1", "::1", "[::1]")
                    : List.copyOf(allowedHosts);
        }

        Api(String username, String password) {
            this(username, password, null, null);
        }
    }

    /**
     * @param dataDirectory private directory for the H2 database and upload staging
     * @param inMemory      use a throwaway in-memory database (tests and demos)
     */
    public record Storage(String dataDirectory, boolean inMemory) {
    }

    /** Port of the local Ollama service; the host is always 127.0.0.1 and cannot be configured. */
    public record Ollama(@Min(0) @Max(65_535) int port) {
        public Ollama {
            port = port == 0 ? OllamaEndpoint.DEFAULT_PORT : port;
        }

        public OllamaEndpoint endpoint() {
            return new OllamaEndpoint(port);
        }
    }

    public record Jobs(
            @Min(0) @Max(16) int workers,
            @Min(0) @Max(256) int queueCapacity,
            @Min(0) @Max(32) int maxActivePerUser,
            Duration timeout
    ) {
        public Jobs {
            workers = workers == 0 ? 2 : workers;
            queueCapacity = queueCapacity == 0 ? 16 : queueCapacity;
            maxActivePerUser = maxActivePerUser == 0 ? 3 : maxActivePerUser;
            timeout = timeout == null ? Duration.ofMinutes(15) : timeout;
        }
    }

    public record Attachments(
            @Min(0) @Max(50 * 1024 * 1024) long maxBytes,
            @Min(0) @Max(2_000) int maxPages,
            @Min(0) @Max(1_000_000) int maxCharacters,
            @Min(0) @Max(50) int maxPerProject,
            Duration retention
    ) {
        public Attachments {
            maxBytes = maxBytes == 0 ? 10L * 1024 * 1024 : maxBytes;
            maxPages = maxPages == 0 ? 200 : maxPages;
            maxCharacters = maxCharacters == 0 ? 200_000 : maxCharacters;
            maxPerProject = maxPerProject == 0 ? 5 : maxPerProject;
            retention = retention == null ? Duration.ofDays(30) : retention;
        }
    }

    public record Auth(Duration tokenTtl, Duration idleTimeout) {
        public Auth {
            tokenTtl = tokenTtl == null ? Duration.ofHours(8) : tokenTtl;
            idleTimeout = idleTimeout == null ? Duration.ofMinutes(30) : idleTimeout;
        }
    }
}
