package com.docgen.hardware;

import com.docgen.UntrustedText;
import oshi.util.PlatformEnum;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Optional NVIDIA probe: {@code nvidia-smi --query-gpu=name,memory.total,memory.free
 * --format=csv,noheader,nounits}, which reports MiB per card. Used only when
 * the driver statistics read through OSHI did not confirm an NVIDIA card's memory.
 */
final class NvidiaSmi {
    static final List<String> ARGUMENTS = List.of("--query-gpu=name,memory.total,memory.free",
            "--format=csv,noheader,nounits");
    static final Duration TIMEOUT = Duration.ofSeconds(3);
    static final int MAX_OUTPUT_BYTES = 16 * 1024;
    static final int MAX_CARDS = 16;
    private static final long MIB = 1024L * 1024;

    private NvidiaSmi() {
    }

    record Card(String name, long totalBytes, long freeBytes) {
    }

    /** Fixed install locations, plus absolute {@code PATH} entries on Linux. */
    static List<Path> candidates(PlatformEnum platform, Map<String, String> environment) {
        List<Path> candidates = new ArrayList<>();
        switch (platform) {
            case WINDOWS -> {
                absolute(environment.get("SystemRoot")).ifPresent(root ->
                        candidates.add(root.resolve("System32").resolve("nvidia-smi.exe")));
                absolute(environment.get("ProgramFiles")).ifPresent(root ->
                        candidates.add(root.resolve("NVIDIA Corporation").resolve("NVSMI").resolve("nvidia-smi.exe")));
            }
            case LINUX -> {
                BoundedCommand.onPath("nvidia-smi", environment.get("PATH")).ifPresent(candidates::add);
                candidates.add(Path.of("/usr/bin/nvidia-smi"));
            }
            default -> {
                // macOS has had no NVIDIA driver for years; other systems are not supported.
            }
        }
        return List.copyOf(candidates);
    }

    static List<Card> query(List<Path> candidates) {
        for (Path executable : candidates) {
            if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
                continue;
            }
            List<String> command = new ArrayList<>();
            command.add(executable.toString());
            command.addAll(ARGUMENTS);
            return BoundedCommand.run(command, TIMEOUT, MAX_OUTPUT_BYTES).map(NvidiaSmi::parse).orElse(List.of());
        }
        return List.of();
    }

    /** One {@code name, total, free} line per card, in MiB; malformed lines (such as {@code [N/A]}) are skipped. */
    static List<Card> parse(String output) {
        List<Card> cards = new ArrayList<>();
        for (String line : output.split("\\R")) {
            if (cards.size() >= MAX_CARDS) {
                break;
            }
            int lastComma = line.lastIndexOf(',');
            int middleComma = lastComma < 0 ? -1 : line.lastIndexOf(',', lastComma - 1);
            if (middleComma <= 0) {
                continue;
            }
            String name = UntrustedText.line(line.substring(0, middleComma), 64);
            Long total = mebibytes(line.substring(middleComma + 1, lastComma));
            Long free = mebibytes(line.substring(lastComma + 1));
            if (name.isEmpty() || total == null || free == null || total == 0 || free > total) {
                continue;
            }
            cards.add(new Card(name, total * MIB, free * MIB));
        }
        return List.copyOf(cards);
    }

    private static Long mebibytes(String value) {
        String text = value.strip();
        if (!text.matches("[0-9]{1,9}")) {
            return null;
        }
        return Long.parseLong(text);
    }

    private static java.util.Optional<Path> absolute(String value) {
        if (value == null || value.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            Path path = Path.of(value);
            return path.isAbsolute() ? java.util.Optional.of(path.normalize()) : java.util.Optional.empty();
        } catch (InvalidPathException invalid) {
            return java.util.Optional.empty();
        }
    }
}
