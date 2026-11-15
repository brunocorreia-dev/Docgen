package com.docgen;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

public final class RepoScanner {
    private static final int MAX_FILE_BYTES = 256 * 1024;
    private static final int MAX_FILE_CHARS = 20_000;
    private static final int MAX_TOTAL_CHARS = 120_000;
    private static final int MAX_DISCOVERED_ENTRIES = 4_096;
    private static final int MAX_SCANNED_FILES = 512;
    private static final int MAX_DIRECTORY_DEPTH = 64;
    private static final int MAX_PATH_CHARS = 512;
    private static final int MAX_FILE_TREE_CHARS = 20_000;
    private static final int MAX_SKIPPED_METADATA_CHARS = 8_192;
    private static final int MAX_EXCLUDED_METADATA_CHARS = 16_384;
    private static final int MAX_METADATA_ITEMS = 256;
    private static final int MAX_METADATA_ITEM_CHARS = MAX_PATH_CHARS + 160;

    private static final String FILE_TRUNCATION_MARKER = "\n[TRUNCATED: file content limit reached]";
    private static final String REPOSITORY_TRUNCATION_MARKER = "\n[TRUNCATED: repository content limit reached]";
    private static final String TREE_TRUNCATION_MARKER = "[TRUNCATED: file tree metadata limit reached]";
    private static final Set<OpenOption> SECURE_READ_OPTIONS =
            Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);

    private static final Set<String> SKIPPED_DIRS = Set.of(
            ".git", ".idea", ".vscode", "target", "build", "dist", "out", "node_modules", ".gradle", ".mvn", "vendor"
    );

    private static final Set<String> SENSITIVE_COMPONENT_NAMES = Set.of(
            ".env", ".envrc", ".npmrc", ".pypirc", ".netrc", "_netrc", ".git-credentials", ".htpasswd",
            "credentials", "credentials.json", "secrets", ".secrets", "secrets.json", "keys", ".keys",
            "private-keys", "private_keys", "kubeconfig", "auth.json", "settings.xml",
            "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519",
            ".ssh", ".gnupg", ".aws", ".azure", ".kube", ".docker", ".direnv",
            ".agents", ".agent", ".claude", "claude", ".codex", ".cursor", ".continue", ".windsurf", ".aider", ".gemini"
    );

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".java", ".kt", ".kts", ".xml", ".gradle", ".properties", ".yml", ".yaml", ".json", ".md", ".txt", ".sh",
            ".py", ".js", ".ts", ".tsx", ".jsx", ".html", ".css", ".scss", ".go", ".rs", ".rb", ".php", ".sql", ".toml"
    );

    private static final Set<String> SENSITIVE_EXTENSIONS = Set.of(
            ".pem", ".key", ".p8", ".ppk", ".p12", ".pfx", ".jks", ".keystore", ".ovpn", ".sqlite", ".db"
    );

    private static final Set<String> SPECIAL_NAMES = Set.of("dockerfile", "makefile", "pom.xml", "readme", "license");

    private final Limits limits;
    private final AfterDiscoveryHook afterDiscoveryHook;

    public RepoScanner() {
        this(Limits.defaults(), () -> { });
    }

    RepoScanner(Limits limits) {
        this(limits, () -> { });
    }

    RepoScanner(Limits limits, AfterDiscoveryHook afterDiscoveryHook) {
        this.limits = limits;
        this.afterDiscoveryHook = afterDiscoveryHook;
    }

    /**
     * Scan-narrowing options. {@code allowedExtensions} is an optional allowlist:
     * when non-empty, only files ending with one of these extensions are included.
     * It can only narrow the built-in supported set; sensitive-file and ignore
     * exclusions always run first and cannot be bypassed by it.
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
        DocgenIgnore ignore = new DocgenIgnore();
        BoundedMessages excluded = new BoundedMessages(MAX_EXCLUDED_METADATA_CHARS);
        DiscoveryState discovery = new DiscoveryState(ignore, excluded, limits.maximumDiscoveredEntries());

        try (SecureDirectoryStream<Path> rootDirectory = openSecureRoot(root)) {
            discover(rootDirectory, root.getFileSystem().getPath(""), 0, options, discovery);
            discovery.candidates.sort(Comparator.comparing(RepoScanner::toPortablePath));
            afterDiscoveryHook.run();

            List<RepoContext.ScannedFile> files = new ArrayList<>();
            BoundedMessages skipped = new BoundedMessages(limits.maximumSkippedMetadataCharacters());
            int totalChars = 0;
            int attemptedFiles = 0;

            for (Path relative : discovery.candidates) {
                if (attemptedFiles >= limits.maximumScannedFiles()) {
                    skipped.add(metadataMessage(relative, "file count limit reached"));
                    continue;
                }
                attemptedFiles++;
                if (totalChars >= limits.maximumTotalCharacters()) {
                    skipped.add(metadataMessage(relative, "total content limit reached"));
                    continue;
                }

                SecureRead read = readSecureFile(rootDirectory, relative, MAX_FILE_BYTES);
                if (read.tooLarge()) {
                    skipped.add(metadataMessage(relative, "larger than " + MAX_FILE_BYTES + " bytes"));
                    continue;
                }

                String raw = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(read.bytes())).toString();
                String redacted = SecretRedactor.redact(raw);
                boolean truncated = false;
                if (redacted.length() > limits.maximumFileCharacters()) {
                    redacted = truncateWithMarker(redacted, limits.maximumFileCharacters(), FILE_TRUNCATION_MARKER);
                    truncated = true;
                }

                int remaining = limits.maximumTotalCharacters() - totalChars;
                if (redacted.length() > remaining) {
                    if (remaining < REPOSITORY_TRUNCATION_MARKER.length()) {
                        skipped.add(metadataMessage(relative, "insufficient content budget for truncation marker"));
                        continue;
                    }
                    redacted = truncateWithMarker(redacted, remaining, REPOSITORY_TRUNCATION_MARKER);
                    truncated = true;
                }
                totalChars += redacted.length();
                files.add(new RepoContext.ScannedFile(relative, redacted, truncated));
            }

            return new RepoContext(root, buildFileTree(files, limits.maximumFileTreeCharacters()), files, totalChars,
                    skipped.toList(), excluded.toList());
        }
    }

    /**
     * Resolves every component from the filesystem root through directory handles
     * with NOFOLLOW_LINKS. There is intentionally no path-based fallback: a
     * provider without SecureDirectoryStream cannot provide the required
     * confinement guarantee and is rejected.
     */
    private static SecureDirectoryStream<Path> openSecureRoot(Path repository) throws IOException {
        Path filesystemRoot = repository.getRoot();
        if (filesystemRoot == null) {
            throw new IOException("Repository path has no filesystem root: " + repository);
        }

        DirectoryStream<Path> initial = Files.newDirectoryStream(filesystemRoot);
        if (!(initial instanceof SecureDirectoryStream<?>)) {
            try {
                initial.close();
            } catch (IOException ignored) {
                // The fail-closed error below is the actionable failure.
            }
            throw new IOException("Filesystem does not provide SecureDirectoryStream; refusing an insecure repository scan: "
                    + repository);
        }

        @SuppressWarnings("unchecked")
        SecureDirectoryStream<Path> current = (SecureDirectoryStream<Path>) initial;
        try {
            for (Path component : repository) {
                SecureDirectoryStream<Path> parent = current;
                current = parent.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS);
                parent.close();
            }
            return current;
        } catch (IOException | RuntimeException failure) {
            try {
                current.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw new IOException("Repository path must contain only real directories and support secure relative access: "
                    + repository, failure);
        }
    }

    private static void discover(SecureDirectoryStream<Path> directory, Path directoryRelative,
                                 int depth, Options options, DiscoveryState state) throws IOException {
        List<DirectoryEntry> entries = listDirectory(directory, state);
        loadIgnoreFiles(directory, directoryRelative, entries, state.ignore);

        for (DirectoryEntry entry : entries) {
            String nameString = entry.name().toString();
            boolean rootDocgenIgnore = directoryRelative.toString().isEmpty() && nameString.equals(".docgenignore");
            if (nameString.equals(".gitignore") || rootDocgenIgnore) {
                continue;
            }

            Path relative = directoryRelative.resolve(entry.name());
            if (!isSafePromptPath(relative)) {
                state.excluded.add(metadataMessage(relative, "unsafe or overlong path"));
                continue;
            }

            BasicFileAttributes attributes = entry.attributes();
            if (attributes.isSymbolicLink()) {
                state.excluded.add(metadataMessage(relative, "symbolic link"));
                continue;
            }
            if (attributes.isDirectory()) {
                if (depth + 1 > MAX_DIRECTORY_DEPTH) {
                    state.excluded.add(metadataMessage(relative, "directory depth limit reached"));
                    continue;
                }
                String lowerName = nameString.toLowerCase(Locale.ROOT);
                if (SKIPPED_DIRS.contains(lowerName)) {
                    continue;
                }
                String reason = directoryExclusionReason(relative, state.ignore);
                if (reason != null) {
                    state.excluded.add(metadataMessage(relative, reason));
                    continue;
                }
                try (SecureDirectoryStream<Path> child =
                             directory.newDirectoryStream(entry.name(), LinkOption.NOFOLLOW_LINKS)) {
                    discover(child, relative, depth + 1, options, state);
                } catch (IOException e) {
                    throw new IOException("Unable to securely traverse repository directory: "
                            + safeMetadataPath(relative) + ": " + safeExceptionMessage(e), e);
                }
                continue;
            }
            if (!attributes.isRegularFile()) {
                state.excluded.add(metadataMessage(relative, "not a regular file"));
                continue;
            }

            String reason = exclusionReason(relative, state.ignore, options);
            if (reason == null) {
                state.candidates.add(relative);
            } else {
                state.excluded.add(metadataMessage(relative, reason));
            }
        }
    }

    private static List<DirectoryEntry> listDirectory(SecureDirectoryStream<Path> directory,
                                                       DiscoveryState state) throws IOException {
        List<DirectoryEntry> entries = new ArrayList<>();
        try {
            for (Path entryPath : directory) {
                state.discoveredEntries++;
                if (state.discoveredEntries > state.maximumDiscoveredEntries) {
                    throw new IOException("Repository entry count exceeds the limit of "
                            + state.maximumDiscoveredEntries + ".");
                }
                Path name = entryPath.getFileName();
                if (name == null || name.isAbsolute() || name.getNameCount() != 1
                        || name.toString().isEmpty() || name.toString().equals(".") || name.toString().equals("..")) {
                    throw new IOException("Filesystem returned an unsafe directory entry name.");
                }
                BasicFileAttributeView view = directory.getFileAttributeView(
                        name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (view == null) {
                    throw new IOException("Filesystem cannot securely read attributes for entry: "
                            + sanitizeMetadata(name.toString()));
                }
                entries.add(new DirectoryEntry(name, view.readAttributes()));
            }
        } catch (DirectoryIteratorException e) {
            throw e.getCause();
        }
        entries.sort(Comparator.comparing(entry -> entry.name().toString()));
        return entries;
    }

    private static void loadIgnoreFiles(SecureDirectoryStream<Path> directory, Path directoryRelative,
                                        List<DirectoryEntry> entries, DocgenIgnore ignore) throws IOException {
        DirectoryEntry docgenIgnore = null;
        DirectoryEntry gitIgnore = null;
        for (DirectoryEntry entry : entries) {
            String name = entry.name().toString();
            if (name.equalsIgnoreCase(".gitignore") && !name.equals(".gitignore")) {
                throw new IOException("Refusing ambiguously cased ignore file: "
                        + safeMetadataPath(directoryRelative.resolve(entry.name())));
            }
            if (directoryRelative.toString().isEmpty()
                    && name.equalsIgnoreCase(".docgenignore") && !name.equals(".docgenignore")) {
                throw new IOException("Refusing ambiguously cased ignore file: "
                        + safeMetadataPath(directoryRelative.resolve(entry.name())));
            }
            if (directoryRelative.toString().isEmpty() && name.equals(".docgenignore")) {
                docgenIgnore = entry;
            } else if (name.equals(".gitignore")) {
                gitIgnore = entry;
            }
        }
        if (docgenIgnore != null) {
            loadIgnoreFile(directory, docgenIgnore, ".docgenignore",
                    channel -> ignore.addDocgenIgnore(directoryRelative, channel, ".docgenignore"));
        }
        if (gitIgnore != null) {
            String source = directoryRelative.toString().isEmpty()
                    ? ".gitignore"
                    : toPortablePath(directoryRelative.resolve(".gitignore"));
            loadIgnoreFile(directory, gitIgnore, source,
                    channel -> ignore.addGitIgnore(directoryRelative, channel, source));
        }
    }

    private static void loadIgnoreFile(SecureDirectoryStream<Path> directory, DirectoryEntry entry,
                                       String source, IgnoreLoader loader) throws IOException {
        if (!entry.attributes().isRegularFile() || entry.attributes().isSymbolicLink()) {
            throw new IOException("Refusing unsafe ignore file " + sanitizeMetadata(source)
                    + ": expected a regular non-symbolic-link file.");
        }

        SeekableByteChannel channel;
        try {
            channel = directory.newByteChannel(entry.name(), SECURE_READ_OPTIONS);
        } catch (IOException e) {
            throw new IOException("Refusing ignore file that changed or cannot be opened securely: "
                    + sanitizeMetadata(source), e);
        }
        try (channel) {
            loader.load(channel);
        }
    }

    private static SecureRead readSecureFile(SecureDirectoryStream<Path> root, Path relative,
                                             int maximumBytes) throws IOException {
        return readSecureFile(root, relative, 0, maximumBytes);
    }

    private static SecureRead readSecureFile(SecureDirectoryStream<Path> directory, Path relative,
                                             int componentIndex, int maximumBytes) throws IOException {
        Path component = relative.getName(componentIndex);
        if (componentIndex < relative.getNameCount() - 1) {
            try (SecureDirectoryStream<Path> child =
                         directory.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS)) {
                return readSecureFile(child, relative, componentIndex + 1, maximumBytes);
            } catch (IOException e) {
                throw new IOException("Repository path changed or contains an unsafe ancestor: "
                        + safeMetadataPath(relative), e);
            }
        }

        try (SeekableByteChannel channel = directory.newByteChannel(component, SECURE_READ_OPTIONS)) {
            if (channel.size() > maximumBytes) {
                return new SecureRead(new byte[0], true);
            }
            InputStream input = Channels.newInputStream(channel);
            byte[] bytes = input.readNBytes(maximumBytes + 1);
            return new SecureRead(bytes.length > maximumBytes ? new byte[0] : bytes,
                    bytes.length > maximumBytes);
        } catch (IOException e) {
            throw new IOException("Repository file changed or cannot be opened securely: "
                    + safeMetadataPath(relative), e);
        }
    }

    private static String exclusionReason(Path relative, DocgenIgnore ignore, Options options) throws IOException {
        String lowerName = relative.getFileName().toString().toLowerCase(Locale.ROOT);
        String relativeString = toPortablePath(relative).toLowerCase(Locale.ROOT);

        if (isPromptPreviewPath(relative)) {
            return "prompt preview";
        }
        if (isSensitivePath(relative) || hasExtension(lowerName, SENSITIVE_EXTENSIONS)) {
            return "sensitive file";
        }
        String ignoreSource = ignore.exclusionSourceForReachablePath(relative, false);
        if (ignoreSource != null) {
            return "excluded by " + ignoreSource;
        }
        if (relativeString.endsWith("package-lock.json") || relativeString.endsWith("yarn.lock")
                || relativeString.endsWith("pnpm-lock.yaml")) {
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

    private static String directoryExclusionReason(Path relative, DocgenIgnore ignore) throws IOException {
        if (isPromptPreviewPath(relative)) {
            return "prompt preview";
        }
        if (isSensitivePath(relative)) {
            return "sensitive path";
        }
        String ignoreSource = ignore.exclusionSourceForReachablePath(relative, true);
        return ignoreSource == null ? null : "excluded by " + ignoreSource;
    }

    private static boolean isSensitivePath(Path relative) {
        for (Path part : relative) {
            if (isSensitiveComponent(part.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSensitiveComponent(String lowerName) {
        if (SENSITIVE_COMPONENT_NAMES.contains(lowerName)) {
            return true;
        }
        if (lowerName.startsWith(".env")) {
            return true;
        }
        if (lowerName.startsWith("credentials") || lowerName.contains("secret")) {
            return true;
        }
        if (lowerName.startsWith("service-account") || lowerName.startsWith("service_account")) {
            return true;
        }
        if (lowerName.startsWith("id_rsa") || lowerName.startsWith("id_dsa")
                || lowerName.startsWith("id_ecdsa") || lowerName.startsWith("id_ed25519")) {
            return true;
        }
        return lowerName.endsWith(".tfvars") || lowerName.contains(".tfvars.");
    }

    private static boolean isPromptPreviewPath(Path relative) {
        for (Path part : relative) {
            String lowerName = part.toString().toLowerCase(Locale.ROOT);
            if (lowerName.startsWith("docgen-") && lowerName.endsWith(".prompt.txt")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasExtension(String lowerName, Set<String> extensions) {
        return extensions.stream().anyMatch(lowerName::endsWith);
    }

    private static String truncateWithMarker(String value, int limit, String marker) {
        if (value.length() <= limit) {
            return value;
        }
        if (limit <= marker.length()) {
            return marker.substring(0, Math.max(0, limit));
        }
        return value.substring(0, limit - marker.length()) + marker;
    }

    private static boolean isSafePromptPath(Path relative) {
        String portable = toPortablePath(relative);
        if (portable.length() > MAX_PATH_CHARS
                || !Normalizer.isNormalized(portable, Normalizer.Form.NFC)) {
            return false;
        }
        for (int index = 0; index < portable.length();) {
            char character = portable.charAt(index);
            if (Character.isSurrogate(character)
                    && (index + 1 >= portable.length()
                    || !Character.isSurrogatePair(character, portable.charAt(index + 1)))) {
                return false;
            }
            int codePoint = portable.codePointAt(index);
            int type = Character.getType(codePoint);
            if (codePoint == '\uFFFD'
                    || Character.isISOControl(codePoint)
                    || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR
                    || (type == Character.SPACE_SEPARATOR && codePoint != ' ')
                    || (Character.isWhitespace(codePoint) && codePoint != ' ')) {
                return false;
            }
            index += Character.charCount(codePoint);
        }
        return true;
    }

    private static String metadataMessage(Path relative, String reason) {
        return safeMetadataPath(relative) + " (" + reason + ")";
    }

    private static String safeMetadataPath(Path relative) {
        return abbreviate(sanitizeMetadata(toPortablePath(relative)), MAX_PATH_CHARS);
    }

    private static String safeExceptionMessage(IOException exception) {
        String message = exception.getMessage();
        return message == null ? "secure traversal failed" : abbreviate(sanitizeMetadata(message), MAX_PATH_CHARS);
    }

    private static String sanitizeMetadata(String value) {
        StringBuilder sanitized = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            sanitized.append(character < 0x20 || character == 0x7f ? '?' : character);
        }
        return sanitized.toString();
    }

    private static String abbreviate(String value, int limit) {
        if (value.length() <= limit) {
            return value;
        }
        String marker = "...[truncated]";
        return value.substring(0, limit - marker.length()) + marker;
    }

    private static String toPortablePath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String buildFileTree(List<RepoContext.ScannedFile> files, int maximumCharacters) {
        Set<String> entries = new TreeSet<>();
        for (RepoContext.ScannedFile file : files) {
            Path current = file.relativePath().getFileSystem().getPath("");
            for (Path part : file.relativePath()) {
                current = current.resolve(part);
                entries.add(toPortablePath(current));
            }
        }
        if (entries.isEmpty()) {
            return "(no supported text files found)";
        }

        List<String> ordered = new ArrayList<>(entries);
        StringBuilder tree = new StringBuilder(Math.min(maximumCharacters, ordered.size() * 32));
        for (int i = 0; i < ordered.size(); i++) {
            String separator = tree.isEmpty() ? "" : "\n";
            String line = separator + ordered.get(i);
            boolean hasMore = i < ordered.size() - 1;
            int markerCost = hasMore ? (tree.isEmpty() ? 0 : 1) + TREE_TRUNCATION_MARKER.length() : 0;
            if (tree.length() + line.length() + markerCost > maximumCharacters) {
                if (!tree.isEmpty()) {
                    tree.append('\n');
                }
                tree.append(TREE_TRUNCATION_MARKER);
                break;
            }
            tree.append(line);
        }
        return tree.toString();
    }

    private record DirectoryEntry(Path name, BasicFileAttributes attributes) {
    }

    private record SecureRead(byte[] bytes, boolean tooLarge) {
    }

    record Limits(int maximumDiscoveredEntries, int maximumScannedFiles,
                  int maximumFileTreeCharacters, int maximumSkippedMetadataCharacters,
                  int maximumFileCharacters, int maximumTotalCharacters) {
        Limits {
            requireRange("maximumDiscoveredEntries", maximumDiscoveredEntries, 1, MAX_DISCOVERED_ENTRIES);
            requireRange("maximumScannedFiles", maximumScannedFiles, 1, MAX_SCANNED_FILES);
            requireRange("maximumFileTreeCharacters", maximumFileTreeCharacters,
                    TREE_TRUNCATION_MARKER.length(), MAX_FILE_TREE_CHARS);
            requireRange("maximumSkippedMetadataCharacters", maximumSkippedMetadataCharacters,
                    BoundedMessages.MARKER_RESERVE, MAX_SKIPPED_METADATA_CHARS);
            requireRange("maximumFileCharacters", maximumFileCharacters,
                    FILE_TRUNCATION_MARKER.length() + 1, MAX_FILE_CHARS);
            requireRange("maximumTotalCharacters", maximumTotalCharacters,
                    REPOSITORY_TRUNCATION_MARKER.length(), MAX_TOTAL_CHARS);
            if (maximumTotalCharacters < maximumFileCharacters) {
                throw new IllegalArgumentException("maximumTotalCharacters cannot be smaller than maximumFileCharacters");
            }
        }

        static Limits defaults() {
            return new Limits(MAX_DISCOVERED_ENTRIES, MAX_SCANNED_FILES,
                    MAX_FILE_TREE_CHARS, MAX_SKIPPED_METADATA_CHARS,
                    MAX_FILE_CHARS, MAX_TOTAL_CHARS);
        }

        private static void requireRange(String name, int value, int minimum, int maximum) {
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
            }
        }
    }

    @FunctionalInterface
    private interface IgnoreLoader {
        void load(SeekableByteChannel channel) throws IOException;
    }

    @FunctionalInterface
    interface AfterDiscoveryHook {
        void run() throws IOException;
    }

    private static final class DiscoveryState {
        private final DocgenIgnore ignore;
        private final BoundedMessages excluded;
        private final List<Path> candidates = new ArrayList<>();
        private final int maximumDiscoveredEntries;
        private int discoveredEntries;

        private DiscoveryState(DocgenIgnore ignore, BoundedMessages excluded, int maximumDiscoveredEntries) {
            this.ignore = ignore;
            this.excluded = excluded;
            this.maximumDiscoveredEntries = maximumDiscoveredEntries;
        }
    }

    private static final class BoundedMessages {
        private static final int MARKER_RESERVE = 96;

        private final int maximumCharacters;
        private final List<String> values = new ArrayList<>();
        private int characters;
        private int omitted;

        private BoundedMessages(int maximumCharacters) {
            this.maximumCharacters = maximumCharacters;
        }

        private void add(String value) {
            String safe = abbreviate(sanitizeMetadata(value), MAX_METADATA_ITEM_CHARS);
            int cost = safe.length() + 3;
            if (values.size() < MAX_METADATA_ITEMS - 1
                    && characters + cost <= maximumCharacters - MARKER_RESERVE) {
                values.add(safe);
                characters += cost;
            } else {
                omitted++;
            }
        }

        private List<String> toList() {
            List<String> result = new ArrayList<>(values);
            if (omitted > 0) {
                result.add("[" + omitted + " additional entries omitted: metadata limit reached]");
            }
            return List.copyOf(result);
        }
    }
}
