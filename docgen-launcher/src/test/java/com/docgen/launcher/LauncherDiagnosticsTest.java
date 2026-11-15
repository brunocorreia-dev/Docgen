package com.docgen.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LauncherDiagnosticsTest {
    private static final PlatformDetector.Platform LINUX = PlatformDetector.detect("Linux", "amd64");

    @TempDir
    Path temp;

    static Path backendJar(Path file, String startClass) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "org.springframework.boot.loader.launch.JarLauncher");
        manifest.getMainAttributes().putValue("Start-Class", startClass);
        Files.createDirectories(file.getParent());
        try (OutputStream output = Files.newOutputStream(file); JarOutputStream ignored = new JarOutputStream(output, manifest)) {
            return file;
        }
    }

    private InstallationLayout layout(Path backend) {
        return InstallationLayout.resolve(LINUX, Map.of("XDG_DATA_HOME", temp.resolve("data").toString(),
                        "XDG_STATE_HOME", temp.resolve("state").toString()),
                temp.resolve("home"), temp.resolve("app/docgen-launcher.jar"), backend, null);
    }

    private static RuntimeEnvironment runtime(String version) {
        return RuntimeEnvironment.of(Path.of(System.getProperty("java.home")), Runtime.Version.parse(version), "Test", LINUX);
    }

    @Test
    void aCompleteEnvironmentPasses() throws IOException {
        Path backend = backendJar(temp.resolve("app/backend/docgen-api.jar"), BackendArtifact.START_CLASS);
        LauncherDiagnostics.Report report = LauncherDiagnostics.run(LINUX, runtime("21.0.4"), layout(backend));

        assertTrue(report.ok(), report.toText());
        assertTrue(report.toText().contains("[OK   ] Backend do DocGen: " + backend));
    }

    @Test
    void rejectsRuntimesOlderThanJava21() throws IOException {
        Path backend = backendJar(temp.resolve("app/backend/docgen-api.jar"), BackendArtifact.START_CLASS);
        LauncherDiagnostics.Report report = LauncherDiagnostics.run(LINUX, runtime("17.0.12"), layout(backend));

        LauncherDiagnostics.Check failure = report.firstFailure().orElseThrow();
        assertEquals("runtime", failure.id());
        assertEquals(LauncherDiagnostics.Category.RUNTIME, failure.category());
        assertTrue(failure.message().contains("Java 21 ou superior"));
    }

    @Test
    void reportsAMissingJavaExecutable() throws IOException {
        Path backend = backendJar(temp.resolve("app/backend/docgen-api.jar"), BackendArtifact.START_CLASS);
        RuntimeEnvironment broken = RuntimeEnvironment.of(temp.resolve("no-jdk"), Runtime.Version.parse("21"), "Test", LINUX);
        LauncherDiagnostics.Check failure = LauncherDiagnostics.run(LINUX, broken, layout(backend)).firstFailure().orElseThrow();
        assertEquals("runtime", failure.id());
        assertTrue(failure.message().contains("não existe"));
    }

    @Test
    void reportsMissingCorruptedOrForeignBackendJars() throws IOException {
        LauncherDiagnostics.Check missing = LauncherDiagnostics.run(LINUX, runtime("21"), layout(temp.resolve("absent.jar")))
                .firstFailure().orElseThrow();
        assertEquals(LauncherDiagnostics.Category.MISSING_FILES, missing.category());
        assertTrue(missing.message().contains("não encontrado"));

        Path corrupted = Files.writeString(temp.resolve("corrupted.jar"), "not a zip");
        assertEquals("o arquivo está corrompido ou não é um JAR.", BackendArtifact.problem(corrupted));

        Path foreign = backendJar(temp.resolve("foreign.jar"), "com.example.Other");
        assertEquals("o arquivo não é o backend do DocGen.", BackendArtifact.problem(foreign));
        assertNull(BackendArtifact.problem(backendJar(temp.resolve("ok.jar"), BackendArtifact.START_CLASS)));
    }

    @Test
    void reportsDirectoriesWithoutPermission() throws IOException {
        assumeTrue(SecureDirectories.posix());
        Path backend = backendJar(temp.resolve("app/backend/docgen-api.jar"), BackendArtifact.START_CLASS);
        Path locked = Files.createDirectories(temp.resolve("state"));
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-x------"));
        try {
            assumeFalse(Files.isWritable(locked), "running with privileges that ignore permissions");
            LauncherDiagnostics.Check failure = LauncherDiagnostics.run(LINUX, runtime("21"), layout(backend))
                    .firstFailure().orElseThrow();
            assertEquals("log-directory", failure.id());
            assertEquals(LauncherDiagnostics.Category.PERMISSION, failure.category());
            assertTrue(failure.message().contains("sem permissão"));
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void rejectsSymbolicLinkAndFileDirectories() throws IOException {
        Path target = Files.createDirectories(temp.resolve("real"));
        Path link = Files.createSymbolicLink(temp.resolve("link"), target);
        assertTrue(SecureDirectories.problem(link).contains("link simbólico"));
        Path file = Files.writeString(temp.resolve("file"), "x");
        assertTrue(SecureDirectories.problem(file).contains("não é um diretório"));
        assertNull(SecureDirectories.problem(temp.resolve("not/yet/created")));
    }

    @Test
    void createsPrivateDirectoriesAndRefusesLinks() throws IOException {
        Path created = SecureDirectories.createPrivate(temp.resolve("a/b/logs"));
        assertTrue(Files.isDirectory(created));
        if (SecureDirectories.posix()) {
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(created)));
        }
        Path link = Files.createSymbolicLink(temp.resolve("linked"), created);
        assertThrowsIo(() -> SecureDirectories.createPrivate(link));
    }

    @Test
    void unknownPlatformsAreWarningsNotFailures() throws IOException {
        Path backend = backendJar(temp.resolve("app/backend/docgen-api.jar"), BackendArtifact.START_CLASS);
        PlatformDetector.Platform exotic = PlatformDetector.detect("Haiku", "sparc");
        LauncherDiagnostics.Report report = LauncherDiagnostics.run(exotic, runtime("21"), layout(backend));
        assertTrue(report.ok());
        assertEquals(LauncherDiagnostics.Status.WARNING, report.checks().get(0).status());
        assertEquals(LauncherDiagnostics.Status.WARNING, report.checks().get(1).status());
        assertFalse(report.toText().contains("FALHA"));
    }

    private static void assertThrowsIo(IoAction action) {
        try {
            action.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("expected an IOException");
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }
}
