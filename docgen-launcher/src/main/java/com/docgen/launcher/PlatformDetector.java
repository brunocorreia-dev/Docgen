package com.docgen.launcher;

import java.util.Locale;

/** Classifies the host from the JVM's os.name and os.arch properties. */
final class PlatformDetector {
    enum OperatingSystem {
        LINUX("Linux"),
        WINDOWS("Windows"),
        MACOS("macOS"),
        OTHER("Desconhecido");

        private final String label;

        OperatingSystem(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    enum Architecture {
        X64("x86_64"),
        AARCH64("ARM64"),
        X86("x86 (32 bits)"),
        ARM32("ARM (32 bits)"),
        UNKNOWN("Desconhecida");

        private final String label;

        Architecture(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    /**
     * @param osName       the raw os.name value, kept for diagnostics
     * @param architecture the raw os.arch value, kept for diagnostics
     */
    record Platform(OperatingSystem os, Architecture arch, String osName, String architecture) {
        boolean posix() {
            return os != OperatingSystem.WINDOWS;
        }
    }

    private PlatformDetector() {
    }

    static Platform current() {
        return detect(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    static Platform detect(String osName, String osArch) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        String arch = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
        OperatingSystem system;
        if (os.startsWith("linux")) {
            system = OperatingSystem.LINUX;
        } else if (os.startsWith("windows")) {
            system = OperatingSystem.WINDOWS;
        } else if (os.startsWith("mac") || os.startsWith("darwin")) {
            system = OperatingSystem.MACOS;
        } else {
            system = OperatingSystem.OTHER;
        }
        Architecture architecture = switch (arch) {
            case "amd64", "x86_64", "x64" -> Architecture.X64;
            case "aarch64", "arm64" -> Architecture.AARCH64;
            case "x86", "i386", "i486", "i586", "i686" -> Architecture.X86;
            case "arm", "arm32", "armv7l", "armhf" -> Architecture.ARM32;
            default -> Architecture.UNKNOWN;
        };
        return new Platform(system, architecture, LauncherOptions.printable(osName == null ? "" : osName),
                LauncherOptions.printable(osArch == null ? "" : osArch));
    }
}
