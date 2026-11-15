package com.docgen.hardware;

import oshi.util.PlatformEnum;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Where Ollama keeps models, to tell how much disk is free for a download.
 * Ollama has no API for it, so DocGen looks in the documented places
 * (docs.ollama.com/faq, verified on 2026-10-25): {@code ~/.ollama/models} on
 * macOS and Windows, {@code /usr/share/ollama/.ollama/models} for the Linux
 * service, and {@code OLLAMA_MODELS} when it is set. Distribution packages may
 * use other folders, such as {@code /var/lib/ollama}, which is checked too.
 * The first folder that exists wins; with none, the home folder's disk is
 * reported as an approximation and the screen says so.
 */
final class OllamaModelsDirectory {
    private OllamaModelsDirectory() {
    }

    static List<Path> candidates(PlatformEnum platform, Map<String, String> environment, Path home) {
        List<Path> candidates = new ArrayList<>();
        String configured = environment.get("OLLAMA_MODELS");
        if (configured != null && !configured.isBlank()) {
            try {
                Path path = Path.of(configured);
                if (path.isAbsolute()) {
                    candidates.add(path.normalize());
                }
            } catch (InvalidPathException ignored) {
                // An unusable value is the same as no value.
            }
        }
        if (platform == PlatformEnum.LINUX) {
            candidates.add(Path.of("/usr/share/ollama/.ollama/models"));
            candidates.add(Path.of("/var/lib/ollama"));
        }
        candidates.add(home.resolve(".ollama").resolve("models"));
        return List.copyOf(candidates);
    }

    static HardwareProfile.Storage measure(List<Path> candidates, Path home) {
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                Long free = usableSpace(candidate);
                if (free != null) {
                    return new HardwareProfile.Storage(candidate.toString(), free, true);
                }
            }
        }
        return new HardwareProfile.Storage(null, usableSpace(home), false);
    }

    private static Long usableSpace(Path path) {
        try {
            return Files.getFileStore(path).getUsableSpace();
        } catch (IOException | SecurityException unreadable) {
            return null;
        }
    }
}
