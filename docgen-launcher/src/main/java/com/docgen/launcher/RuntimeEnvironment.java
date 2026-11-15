package com.docgen.launcher;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The Java runtime that will run the backend. It is always the runtime of the
 * launcher itself (java.home), never an executable taken from arguments, the
 * PATH or the environment, so the packaged app runs on its bundled runtime.
 */
record RuntimeEnvironment(Path javaHome, Runtime.Version version, String vendor, Path javaExecutable) {
    static final int MINIMUM_FEATURE_VERSION = 21;

    static RuntimeEnvironment current(PlatformDetector.Platform platform) {
        return of(Path.of(System.getProperty("java.home")), Runtime.version(),
                System.getProperty("java.vendor", ""), platform);
    }

    static RuntimeEnvironment of(Path javaHome, Runtime.Version version, String vendor,
                                 PlatformDetector.Platform platform) {
        Path home = javaHome.toAbsolutePath().normalize();
        String executable = platform.os() == PlatformDetector.OperatingSystem.WINDOWS ? "java.exe" : "java";
        return new RuntimeEnvironment(home, version, vendor == null ? "" : vendor,
                home.resolve("bin").resolve(executable));
    }

    boolean supportedVersion() {
        return version.feature() >= MINIMUM_FEATURE_VERSION;
    }

    boolean executableAvailable() {
        return Files.isRegularFile(javaExecutable) && Files.isExecutable(javaExecutable);
    }
}
