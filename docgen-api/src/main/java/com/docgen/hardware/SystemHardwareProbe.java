package com.docgen.hardware;

import com.docgen.UntrustedText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.GpuStats;
import oshi.hardware.GraphicsCard;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.software.os.OperatingSystem;
import oshi.util.PlatformEnum;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Reads the hardware with OSHI (MIT), locally and without network:
 *
 * <ul>
 *   <li>operating system, CPU and memory ({@link GlobalMemory#getAvailable()} is what programs can use
 *   now, {@code MemAvailable} on Linux);</li>
 *   <li>graphics cards: OSHI reads the total memory and the memory in use from the driver (NVML for NVIDIA,
 *   amdgpu files for AMD, driver statistics on Windows). A card is "confirmed" only when both numbers
 *   arrive: without driver statistics OSHI falls back to the card's memory window from {@code lspci},
 *   which is not its memory (measured here: 288 MB of windows on a 12 GB RTX 5070);</li>
 *   <li>an NVIDIA card that OSHI could not confirm is asked through {@code nvidia-smi}, when installed;</li>
 *   <li>on Apple Silicon the GPU uses the system RAM ("memória unificada"), so no VRAM is reported and the
 *   recommender plans with the RAM;</li>
 *   <li>free disk space where Ollama keeps models ({@link OllamaModelsDirectory}).</li>
 * </ul>
 *
 * <p>Each part is read on its own and a part that fails or exceeds its time is "not detected"; the probe
 * itself never throws.</p>
 */
public final class SystemHardwareProbe implements HardwareProbe {
    private static final Logger LOG = LoggerFactory.getLogger(SystemHardwareProbe.class);
    static final Duration PART_TIMEOUT = Duration.ofSeconds(8);
    static final int MAX_GPUS = 8;

    private final PlatformEnum platform;
    private final Map<String, String> environment;
    private final Path home;
    private final String architecture;
    private volatile SystemInfo systemInfo;

    public SystemHardwareProbe() {
        this(PlatformEnum.getCurrentPlatform(), System.getenv(), Path.of(System.getProperty("user.home")),
                System.getProperty("os.arch", ""));
    }

    SystemHardwareProbe(PlatformEnum platform, Map<String, String> environment, Path home, String architecture) {
        this.platform = platform;
        this.environment = Map.copyOf(environment);
        this.home = home;
        this.architecture = architecture;
    }

    /** Apple Silicon: macOS on ARM, where CPU and GPU share the memory. */
    boolean unifiedMemory() {
        String arch = architecture.toLowerCase(Locale.ROOT);
        return platform == PlatformEnum.MACOS && (arch.equals("aarch64") || arch.equals("arm64"));
    }

    @Override
    public HardwareProfile probe() {
        SystemInfo info = bounded("system", this::systemInfo);
        HardwareAbstractionLayer hardware = info == null ? null : bounded("hardware", info::getHardware);
        OperatingSystem os = info == null ? null : bounded("operating system", info::getOperatingSystem);
        CentralProcessor cpu = hardware == null ? null : bounded("cpu", hardware::getProcessor);
        GlobalMemory memory = hardware == null ? null : bounded("memory", hardware::getMemory);
        boolean unified = unifiedMemory();
        List<HardwareProfile.Gpu> gpus = hardware == null ? List.of() : bounded("gpu", () -> gpus(hardware, unified));
        HardwareProfile.Storage storage = modelStorage();
        return new HardwareProfile(
                os == null ? null : bounded("os name", () -> label(os.getFamily())),
                os == null ? null : bounded("os version", () -> label(String.valueOf(os.getVersionInfo()))),
                architecture.isBlank() ? null : label(architecture),
                cpu == null ? null : bounded("cpu name", () -> label(cpu.getProcessorIdentifier().getName())),
                cpu == null ? null : bounded("cpu cores", () -> positive(cpu.getPhysicalProcessorCount())),
                cpu == null ? null : bounded("cpu threads", () -> positive(cpu.getLogicalProcessorCount())),
                memory == null ? null : bounded("ram total", () -> positive(memory.getTotal())),
                memory == null ? null : bounded("ram available", () -> nonNegative(memory.getAvailable())),
                gpus == null ? List.of() : gpus,
                unified,
                storage);
    }

    @Override
    public HardwareProfile.Storage modelStorage() {
        HardwareProfile.Storage storage = bounded("disk", () -> OllamaModelsDirectory.measure(
                OllamaModelsDirectory.candidates(platform, environment, home), home));
        return storage == null ? new HardwareProfile.Storage(null, null, false) : storage;
    }

    private SystemInfo systemInfo() {
        if (systemInfo == null) {
            systemInfo = new SystemInfo();
        }
        return systemInfo;
    }

    private List<HardwareProfile.Gpu> gpus(HardwareAbstractionLayer hardware, boolean unified) {
        List<HardwareProfile.Gpu> gpus = new ArrayList<>();
        boolean nvidiaUnconfirmed = false;
        for (GraphicsCard card : hardware.getGraphicsCards()) {
            if (gpus.size() >= MAX_GPUS) {
                break;
            }
            String vendor = vendor(card.getVendor());
            String name = label(card.getName());
            HardwareProfile.Kind kind = HardwareProfile.Kind.of(vendor, name);
            if (unified && kind == HardwareProfile.Kind.APPLE) {
                gpus.add(new HardwareProfile.Gpu(vendor, name, null, null, false, "memória unificada"));
                continue;
            }
            long total = card.getVRam();
            long used = vramUsed(card);
            boolean confirmed = total > 0 && used >= 0 && used <= total;
            nvidiaUnconfirmed |= kind == HardwareProfile.Kind.NVIDIA && !confirmed;
            gpus.add(new HardwareProfile.Gpu(vendor, name, total > 0 ? total : null,
                    confirmed ? total - used : null, confirmed,
                    confirmed ? "driver da placa" : total > 0 ? "sistema, não confirmado" : null));
        }
        boolean anyNvidia = gpus.stream().anyMatch(gpu -> gpu.kind() == HardwareProfile.Kind.NVIDIA);
        if (nvidiaUnconfirmed || !anyNvidia) {
            List<NvidiaSmi.Card> cards = NvidiaSmi.query(NvidiaSmi.candidates(platform, environment));
            if (!cards.isEmpty()) {
                gpus.removeIf(gpu -> gpu.kind() == HardwareProfile.Kind.NVIDIA);
                for (NvidiaSmi.Card card : cards) {
                    gpus.add(new HardwareProfile.Gpu("NVIDIA", card.name(), card.totalBytes(), card.freeBytes(),
                            true, "nvidia-smi"));
                }
            }
        }
        return List.copyOf(gpus);
    }

    private static long vramUsed(GraphicsCard card) {
        try (GpuStats stats = card.createStatsSession()) {
            return stats.getVramUsed();
        } catch (RuntimeException | LinkageError unavailable) {
            return -1;
        }
    }

    /** OSHI appends the PCI vendor id, as in "NVIDIA Corporation (0x10de)". */
    static String vendor(String value) {
        String text = label(value);
        return text == null ? null : label(text.replaceAll("\\s*\\(0x[0-9a-fA-F]{4}\\)$", ""));
    }

    static String label(String value) {
        if (value == null) {
            return null;
        }
        String text = UntrustedText.line(value, 96);
        return text.isEmpty() || text.equalsIgnoreCase("unknown") ? null : text;
    }

    private static Integer positive(int value) {
        return value > 0 ? value : null;
    }

    private static Long positive(long value) {
        return value > 0 ? value : null;
    }

    private static Long nonNegative(long value) {
        return value >= 0 ? value : null;
    }

    /** Runs one part with a time limit; a failure, a native library error or a timeout means "not detected". */
    private static <T> T bounded(String part, Supplier<T> reader) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Thread.ofVirtual().name("docgen-hardware").start(() -> {
            try {
                result.complete(reader.get());
            } catch (RuntimeException | LinkageError failure) {
                result.completeExceptionally(failure);
            }
        });
        try {
            return result.get(PART_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception failure) {
            LOG.debug("Hardware part '{}' was not detected ({})", part, failure.getClass().getSimpleName());
            return null;
        }
    }
}
