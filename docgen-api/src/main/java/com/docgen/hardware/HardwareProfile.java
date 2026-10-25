package com.docgen.hardware;

import java.util.List;

/**
 * What DocGen knows about this computer. Every value that could not be
 * detected is {@code null} ("não detectado" on screen), never a guess.
 *
 * @param ramAvailableBytes memory programs can use now without swapping (Linux {@code MemAvailable})
 * @param unifiedMemory     Apple Silicon: the GPU uses the system RAM, there is no separate VRAM
 * @param modelStorage      the disk where Ollama keeps models
 */
public record HardwareProfile(
        String osName,
        String osVersion,
        String architecture,
        String cpuModel,
        Integer cpuCores,
        Integer cpuThreads,
        Long ramTotalBytes,
        Long ramAvailableBytes,
        List<Gpu> gpus,
        boolean unifiedMemory,
        Storage modelStorage
) {
    public HardwareProfile {
        gpus = gpus == null ? List.of() : List.copyOf(gpus);
    }

    /**
     * A graphics card.
     *
     * @param vramConfirmed the totals come from the driver (NVML, amdgpu, nvidia-smi or the Windows driver
     *                      statistics); otherwise {@code vramTotalBytes} may only be the memory window the
     *                      card exposes, and DocGen does not plan with it
     * @param source        where the memory numbers came from, for the diagnosis screen
     */
    public record Gpu(
            String vendor,
            String name,
            Long vramTotalBytes,
            Long vramFreeBytes,
            boolean vramConfirmed,
            String source
    ) {
        public Kind kind() {
            return Kind.of(vendor, name);
        }
    }

    /** The GPU makers Ollama runs models on, as far as this estimate is concerned. */
    public enum Kind {
        NVIDIA, AMD, APPLE, INTEL, OTHER;

        static Kind of(String vendor, String name) {
            String text = ((vendor == null ? "" : vendor) + " " + (name == null ? "" : name)).toLowerCase(java.util.Locale.ROOT);
            if (text.contains("nvidia")) {
                return NVIDIA;
            }
            if (text.contains("advanced micro devices") || text.contains("amd") || text.contains("radeon")
                    || text.contains("ati technologies")) {
                return AMD;
            }
            if (text.contains("apple")) {
                return APPLE;
            }
            if (text.contains("intel")) {
                return INTEL;
            }
            return OTHER;
        }
    }

    /**
     * @param directory {@code null} when the Ollama models folder was not found
     * @param exact     the free space is that of the models folder itself; otherwise of the
     *                  user's home folder, as an approximation
     */
    public record Storage(String directory, Long freeBytes, boolean exact) {
    }
}
