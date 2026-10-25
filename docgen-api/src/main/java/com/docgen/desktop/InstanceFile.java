package com.docgen.desktop;

import com.docgen.api.PrivateFiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@code instance.json}: how a second start of the app reaches the running
 * one (ADR 0001). Written atomically, mode 0600, only by the holder of the
 * instance lock and only after its server is ready; deleted when it stops.
 *
 * @param port   the server's loopback port
 * @param pid    the running process, to recognize a file left behind by a crash
 * @param secret the reopen secret; never logged
 */
record InstanceFile(int port, long pid, String secret) {
    static final String FILE = "instance.json";
    private static final int MAX_BYTES = 1024;
    private static final Pattern SECRET = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    InstanceFile {
        if (port < 1 || port > 65_535 || pid < 1 || secret == null || !SECRET.matcher(secret).matches()) {
            throw new IllegalArgumentException("Invalid instance file");
        }
    }

    void write(Path dataDirectory) throws IOException {
        String json = JSON.writeValueAsString(JSON.createObjectNode()
                .put("port", port)
                .put("pid", pid)
                .put("secret", secret));
        PrivateFiles.writeAtomically(dataDirectory.resolve(FILE), json.getBytes(StandardCharsets.UTF_8));
    }

    /** The instance file, or empty when it is missing, malformed or unreadable. */
    static Optional<InstanceFile> read(Path dataDirectory) {
        try {
            Optional<byte[]> content = PrivateFiles.readSmallFile(dataDirectory.resolve(FILE), MAX_BYTES);
            if (content.isEmpty()) {
                return Optional.empty();
            }
            JsonNode node = JSON.readTree(new String(content.get(), StandardCharsets.UTF_8));
            if (!node.path("port").isInt() || !node.path("pid").isIntegralNumber() || !node.path("secret").isString()) {
                return Optional.empty();
            }
            return Optional.of(new InstanceFile(node.path("port").asInt(), node.path("pid").asLong(),
                    node.path("secret").asString()));
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    static void delete(Path dataDirectory) throws IOException {
        Files.deleteIfExists(dataDirectory.resolve(FILE));
    }

    /** False when the recorded process is gone, i.e. the file was left behind by a crash. */
    boolean processAlive() {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    @Override
    public String toString() {
        return "InstanceFile[port=" + port + ", pid=" + pid + "]";
    }
}
