package com.docgen.hardware;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import oshi.util.PlatformEnum;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HardwareProbeTest {
    @Test
    void readsNvidiaSmiOutputInMebibytes() {
        List<NvidiaSmi.Card> cards = NvidiaSmi.parse("""
                NVIDIA GeForce RTX 5070, 12227, 10742
                NVIDIA GeForce RTX 3060, 12288, 12000
                """);

        assertEquals(List.of(
                new NvidiaSmi.Card("NVIDIA GeForce RTX 5070", 12_227L << 20, 10_742L << 20),
                new NvidiaSmi.Card("NVIDIA GeForce RTX 3060", 12_288L << 20, 12_000L << 20)), cards);
    }

    @Test
    void skipsWhatNvidiaSmiCouldNotMeasure() {
        assertEquals(List.of(), NvidiaSmi.parse("""
                NVIDIA A100, [N/A], [N/A]
                broken line
                , 100, 50
                GPU, 100, 200
                GPU, -1, 0
                GPU, 0, 0
                """));
    }

    @Test
    void nvidiaSmiIsLookedUpOnlyInFixedPlacesAndAbsolutePathEntries(@TempDir Path directory) {
        assertEquals(List.of(directory.resolve("System32").resolve("nvidia-smi.exe")),
                NvidiaSmi.candidates(PlatformEnum.WINDOWS, Map.of("SystemRoot", directory.toString(), "ProgramFiles", "relative")));
        assertEquals(List.of(Path.of("/usr/bin/nvidia-smi")), NvidiaSmi.candidates(PlatformEnum.LINUX,
                Map.of("PATH", "bin:./tools:" + directory)));
        assertEquals(List.of(), NvidiaSmi.candidates(PlatformEnum.MACOS, Map.of("PATH", "/usr/bin")));
    }

    @Test
    void findsTheOllamaModelsFolderOrSaysTheSpaceIsApproximate(@TempDir Path home) throws Exception {
        Path models = Files.createDirectories(home.resolve("custom-models"));
        HardwareProfile.Storage configured = OllamaModelsDirectory.measure(OllamaModelsDirectory.candidates(
                PlatformEnum.MACOS, Map.of("OLLAMA_MODELS", models.toString()), home), home);
        assertEquals(models.toString(), configured.directory());
        assertTrue(configured.exact());
        assertTrue(configured.freeBytes() > 0);

        HardwareProfile.Storage fallback = OllamaModelsDirectory.measure(OllamaModelsDirectory.candidates(
                PlatformEnum.WINDOWS, Map.of("OLLAMA_MODELS", "relative/models"), home), home);
        assertNull(fallback.directory());
        assertFalse(fallback.exact(), "no Ollama folder: the home disk is only an approximation");
        assertNotNull(fallback.freeBytes());

        Files.createDirectories(home.resolve(".ollama/models"));
        assertEquals(home.resolve(".ollama/models").toString(), OllamaModelsDirectory.measure(
                OllamaModelsDirectory.candidates(PlatformEnum.WINDOWS, Map.of(), home), home).directory());
    }

    @Test
    void linuxAlsoLooksWhereTheOfficialServiceKeepsModels(@TempDir Path home) {
        assertEquals(List.of(Path.of("/usr/share/ollama/.ollama/models"), Path.of("/var/lib/ollama"),
                home.resolve(".ollama/models")), OllamaModelsDirectory.candidates(PlatformEnum.LINUX, Map.of(), home));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void diagnosticProgramsAreBoundedInTimeAndOutput() {
        String path = System.getenv("PATH");
        Path echo = BoundedCommand.onPath("echo", path).orElseThrow();
        assertEquals("ok\n", BoundedCommand.run(List.of(echo.toString(), "ok"), Duration.ofSeconds(5), 64).orElseThrow());

        Path sleep = BoundedCommand.onPath("sleep", path).orElseThrow();
        long started = System.nanoTime();
        assertTrue(BoundedCommand.run(List.of(sleep.toString(), "10"), Duration.ofMillis(300), 64).isEmpty());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 5, "killed at the timeout");

        Path yes = BoundedCommand.onPath("yes", path).orElseThrow();
        assertTrue(BoundedCommand.run(List.of(yes.toString()), Duration.ofMillis(500), 1024).isEmpty(),
                "endless output is cut and refused");

        assertTrue(BoundedCommand.run(List.of("echo", "relative"), Duration.ofSeconds(1), 64).isEmpty(),
                "only resolved absolute executables run");
        Path falseProgram = BoundedCommand.onPath("false", path).orElseThrow();
        assertTrue(BoundedCommand.run(List.of(falseProgram.toString()), Duration.ofSeconds(5), 64).isEmpty(),
                "a failing exit status is no answer");
    }

    @Test
    void appleSiliconUsesUnifiedMemory(@TempDir Path home) {
        assertTrue(new SystemHardwareProbe(PlatformEnum.MACOS, Map.of(), home, "aarch64").unifiedMemory());
        assertFalse(new SystemHardwareProbe(PlatformEnum.MACOS, Map.of(), home, "x86_64").unifiedMemory());
        assertFalse(new SystemHardwareProbe(PlatformEnum.LINUX, Map.of(), home, "aarch64").unifiedMemory());
    }

    @Test
    void vendorNamesLoseThePciId() {
        assertEquals("NVIDIA Corporation", SystemHardwareProbe.vendor("NVIDIA Corporation (0x10de)"));
        assertNull(SystemHardwareProbe.vendor("unknown"));
        assertEquals(HardwareProfile.Kind.NVIDIA, HardwareProfile.Kind.of("NVIDIA Corporation", "GB205 [GeForce RTX 5070]"));
        assertEquals(HardwareProfile.Kind.AMD, HardwareProfile.Kind.of("Advanced Micro Devices, Inc. [AMD/ATI]", "Navi 32"));
        assertEquals(HardwareProfile.Kind.INTEL, HardwareProfile.Kind.of("Intel Corporation", "UHD Graphics 620"));
        assertEquals(HardwareProfile.Kind.APPLE, HardwareProfile.Kind.of("Apple", "Apple M2"));
    }

    @Test
    void theRealProbeReportsThisComputerWithoutFailing() {
        long started = System.nanoTime();
        HardwareProfile profile = new SystemHardwareProbe().probe();

        assertNotNull(profile.osName());
        assertNotNull(profile.architecture());
        assertTrue(profile.ramTotalBytes() != null && profile.ramTotalBytes() > 0);
        assertTrue(profile.cpuThreads() != null && profile.cpuThreads() > 0);
        assertNotNull(profile.modelStorage());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 30);
        for (HardwareProfile.Gpu gpu : profile.gpus()) {
            if (gpu.vramConfirmed()) {
                assertTrue(gpu.vramFreeBytes() <= gpu.vramTotalBytes());
            }
        }
    }
}
