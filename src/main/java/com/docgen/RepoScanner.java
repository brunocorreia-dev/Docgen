package com.docgen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

public final class RepoScanner {
    private static final int MAX_FILE_BYTES = 256 * 1024;
    private static final int MAX_FILE_CHARS = 20_000;
    private static final int MAX_TOTAL_CHARS = 120_000;

    private static final Set<String> SKIPPED_DIRS = Set.of(
            ".git", ".idea", ".vscode", "target", "build", "dist", "out", "node_modules", ".gradle", ".mvn", "vendor"
    );

    private static final Set<String> SENSITIVE_NAMES = Set.of(
            ".env", ".npmrc", ".pypirc", "credentials", "credentials.json", "secrets.json", "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519"
    );

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".java", ".kt", ".kts", ".xml", ".gradle", ".properties", ".yml", ".yaml", ".json", ".md", ".txt", ".sh",
            ".py", ".js", ".ts", ".tsx", ".jsx", ".html", ".css", ".scss", ".go", ".rs", ".rb", ".php", ".sql", ".toml"
    );

    private static final Set<String> SENSITIVE_EXTENSIONS = Set.of(
            ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".sqlite", ".db"
    );

    private static final Set<String> SPECIAL_NAMES = Set.of("dockerfile", "makefile", "pom.xml", "readme", "license");

    /**
     * Scan-narrowing options. {@code allowedExtensions} is an optional allowlist:
     * when non-empty, only files ending with one of these extensions are included.
     * It can only narrow the built-in supported set; sensitive-file and
     * .docgenignore exclusions always run first and cannot be bypassed by it.
     */
    public record Options(Set<String> allowedExtensions) {
        public Options {
            allowedExtensions = normalize(allowedExtensions);
        }

        public static Options defaults() {
            return new Options(Set.of());
        }

        public static Options withAllowedExtensions(Collection<String> extensions) {
            return new Options(extensions == null ? Set.of() : new LinkedHashSet<>(extensions));
        }

        private static Set<String> normalize(Collection<String> extensions) {
            if (extensions == null) {
                return Set.of();
            }
            Set<String> normalized = new LinkedHashSet<>();
            for (String extension : extensions) {
                if (extension == null || extension.isBlank()) {
                    continue;
                }
                String cleaned = extension.trim().toLowerCase(Locale.ROOT);
                while (cleaned.startsWith(".")) {
                    cleaned = cleaned.substring(1);
                }
                if (!cleaned.isEmpty()) {
                    normalized.add("." + cleaned);
                }
            }
            return Set.copyOf(normalized);
        }
    }

    public RepoContext scan(Path repository) throws IOException {
        return scan(repository, Options.defaults());
    }

    public RepoContext scan(Path repository, Options options) throws IOException {
        Path root = repository.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("Repository path is not a directory: " + root);
        }
        DocgenIgnore ignore = DocgenIgnore.load(root);

        List<Path> regularFiles;
        try (Stream<Path> stream = Files.walk(root)) {
            regularFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> !isUnderSkippedDirectory(root, path))
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                    .toList();
        }

        List<Path> candidates = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        for (Path path : regularFiles) {
            Path relative = root.relativize(path);
            String reason = exclusionReason(relative, ignore, options);
            if (reason == null) {
                candidates.add(path);
            } else {
                excluded.add(relative.toString().replace('\\', '/') + " (" + reason + ")");
            }
        }

        List<RepoContext.ScannedFile> files = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int totalChars = 0;

        for (Path path : candidates) {
            if (totalChars >= MAX_TOTAL_CHARS) {
                skipped.add(root.relativize(path) + " (total content limit reached)");
                continue;
            }
            long size = Files.size(path);
            if (size > MAX_FILE_BYTES) {
                skipped.add(root.relativize(path) + " (larger than " + MAX_FILE_BYTES + " bytes)");
                continue;
            }

            String raw = Files.readString(path, StandardCharsets.UTF_8);
            String redacted = SecretRedactor.redact(raw);
            boolean truncated = false;
            if (redacted.length() > MAX_FILE_CHARS) {
                redacted = redacted.substring(0, MAX_FILE_CHARS) + "\n[TRUNCATED: file content limit reached]";
                truncated = true;
            }
            int remaining = MAX_TOTAL_CHARS - totalChars;
            if (redacted.length() > remaining) {
                redacted = redacted.substring(0, Math.max(0, remaining)) + "\n[TRUNCATED: repository content limit reached]";
                truncated = true;
            }
            totalChars += redacted.length();
            files.add(new RepoContext.ScannedFile(root.relativize(path), redacted, truncated));
        }

        return new RepoContext(root, buildFileTree(root, candidates, skipped), files, totalChars, skipped, excluded);
    }

    /**
     * Returns why {@code relative} must not be scanned, or {@code null} when it is
     * a candidate. Sensitive-file rules run before the extension allowlist so the
     * allowlist can only narrow, never re-include, protected files.
     */
    private static String exclusionReason(Path relative, DocgenIgnore ignore, Options options) {
        String name = relative.getFileName().toString();
        String lowerName = name.toLowerCase(Locale.ROOT);
        String relativeString = relative.toString().replace('\\', '/').toLowerCase(Locale.ROOT);

        if (ignore.excludes(relative)) {
            return "excluded by .docgenignore";
        }
        if (isSensitiveName(lowerName) || hasExtension(lowerName, SENSITIVE_EXTENSIONS)) {
            return "sensitive file";
        }
        if (lowerName.startsWith(".env.") && !(lowerName.endsWith(".example") || lowerName.endsWith(".template"))) {
            return "sensitive file";
        }
        if (relativeString.endsWith("package-lock.json") || relativeString.endsWith("yarn.lock") || relativeString.endsWith("pnpm-lock.yaml")) {
            return "lock file";
        }
        boolean supported = TEXT_EXTENSIONS.stream().anyMatch(lowerName::endsWith) || SPECIAL_NAMES.contains(lowerName);
        if (!supported) {
            return "unsupported file type";
        }
        if (!options.allowedExtensions().isEmpty()
                && options.allowedExtensions().stream().noneMatch(lowerName::endsWith)) {
            return "not in --include-ext allowlist";
        }
        return null;
    }

    private static boolean isUnderSkippedDirectory(Path root, Path path) {
        Path relative = root.relativize(path);
        for (Path part : relative) {
            if (SKIPPED_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSensitiveName(String lowerName) {
        if (SENSITIVE_NAMES.contains(lowerName)) {
            return true;
        }
        return lowerName.contains("secret") && !lowerName.endsWith(".example") && !lowerName.endsWith(".template");
    }

    private static boolean hasExtension(String lowerName, Set<String> extensions) {
        return extensions.stream().anyMatch(lowerName::endsWith);
    }

    private static String buildFileTree(Path root, List<Path> scanned, List<String> skipped) {
        Set<String> entries = new HashSet<>();
        for (Path path : scanned) {
            Path relative = root.relativize(path);
            Path current = Path.of("");
            for (Path part : relative) {
                current = current.resolve(part);
                entries.add(current.toString().replace('\\', '/'));
            }
        }
        if (entries.isEmpty()) {
            return "(no supported text files found)";
        }
        return entries.stream().sorted().reduce((left, right) -> left + "\n" + right).orElse("");
    }
}
