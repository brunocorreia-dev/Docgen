package com.docgen.launcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformDetectorTest {
    @Test
    void identifiesTheSupportedOperatingSystems() {
        assertEquals(PlatformDetector.OperatingSystem.LINUX, PlatformDetector.detect("Linux", "amd64").os());
        assertEquals(PlatformDetector.OperatingSystem.WINDOWS, PlatformDetector.detect("Windows 11", "amd64").os());
        assertEquals(PlatformDetector.OperatingSystem.MACOS, PlatformDetector.detect("Mac OS X", "aarch64").os());
        assertEquals(PlatformDetector.OperatingSystem.OTHER, PlatformDetector.detect("FreeBSD", "amd64").os());
        assertTrue(PlatformDetector.detect("Linux", "amd64").posix());
        assertFalse(PlatformDetector.detect("Windows 10", "amd64").posix());
    }

    @Test
    void normalizesArchitectureAliases() {
        assertEquals(PlatformDetector.Architecture.X64, PlatformDetector.detect("Linux", "x86_64").arch());
        assertEquals(PlatformDetector.Architecture.X64, PlatformDetector.detect("Linux", "AMD64").arch());
        assertEquals(PlatformDetector.Architecture.AARCH64, PlatformDetector.detect("Mac OS X", "arm64").arch());
        assertEquals(PlatformDetector.Architecture.X86, PlatformDetector.detect("Windows 10", "x86").arch());
        assertEquals(PlatformDetector.Architecture.ARM32, PlatformDetector.detect("Linux", "arm").arch());
    }

    @Test
    void keepsUnknownArchitecturesForDiagnostics() {
        PlatformDetector.Platform platform = PlatformDetector.detect("Linux", "riscv64\u0000");
        assertEquals(PlatformDetector.Architecture.UNKNOWN, platform.arch());
        assertEquals("riscv64?", platform.architecture());
        assertEquals(PlatformDetector.OperatingSystem.OTHER, PlatformDetector.detect(null, null).os());
    }

    @Test
    void detectsTheCurrentHost() {
        PlatformDetector.Platform current = PlatformDetector.current();
        assertEquals(PlatformDetector.detect(System.getProperty("os.name"), System.getProperty("os.arch")), current);
    }
}
