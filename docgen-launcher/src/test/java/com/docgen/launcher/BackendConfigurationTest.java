package com.docgen.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendConfigurationTest {
    private static final PlatformDetector.Platform LINUX = PlatformDetector.detect("Linux", "amd64");

    @TempDir
    Path temp;

    private InstallationLayout layout() throws IOException {
        Path backend = LauncherDiagnosticsTest.backendJar(temp.resolve("app/backend/docgen-api.jar"), BackendArtifact.START_CLASS);
        return InstallationLayout.resolve(LINUX, Map.of("XDG_DATA_HOME", temp.resolve("data").toString(),
                "XDG_STATE_HOME", temp.resolve("state").toString()), temp.resolve("home"),
                temp.resolve("app/docgen-launcher.jar"), backend, null);
    }

    @Test
    void runsTheInstalledJarOnTheLaunchersOwnRuntimeBoundToLoopback() throws IOException {
        RuntimeEnvironment runtime = RuntimeEnvironment.of(Path.of("/opt/docgen/lib/runtime"), Runtime.version(), "Test", LINUX);
        InstallationLayout layout = layout();
        BackendConfiguration configuration = BackendConfiguration.forInstallation(runtime, layout, Map.of(), "ana", 0,
                Duration.ofSeconds(30));

        assertEquals("/opt/docgen/lib/runtime/bin/java", configuration.command().getFirst());
        assertTrue(configuration.command().contains("-jar"));
        assertTrue(configuration.command().contains(layout.backendJar().toString()));
        assertTrue(configuration.command().contains("--docgen.desktop.enabled=true"));
        assertTrue(configuration.command().contains("--server.address=127.0.0.1"));
        assertTrue(configuration.command().contains("--server.port=0"));
        assertEquals(layout.stateDirectory().path(), configuration.workingDirectory());
        assertEquals(Duration.ofSeconds(30), configuration.startupTimeout());
    }

    @Test
    void neverPutsCredentialsOnTheCommandLineOrInheritsPasswordLogins() throws IOException {
        Map<String, String> parent = Map.of(
                "PATH", "/usr/bin",
                "GROQ_API_KEY", "gsk_user_choice",
                "DOCGEN_ALLOW_REMOTE", "false",
                "DOCGEN_API_PASSWORD", "operator-password-123",
                "DOCGEN_API_ADDITIONAL_USERS_EVE", "eve-password-123",
                "DOCGEN_API_PORT", "8080",
                "DOCGEN_DESKTOP_ENABLED", "false");
        BackendConfiguration configuration = BackendConfiguration.forInstallation(
                RuntimeEnvironment.of(Path.of("/jdk"), Runtime.version(), "Test", LINUX), layout(), parent, "ana", 0,
                Duration.ofSeconds(30));

        assertFalse(String.join(" ", configuration.command()).toLowerCase().contains("password"));
        Map<String, String> environment = configuration.environment();
        assertFalse(environment.containsKey("DOCGEN_API_PASSWORD"));
        assertFalse(environment.containsKey("DOCGEN_API_ADDITIONAL_USERS_EVE"));
        assertFalse(environment.containsKey("DOCGEN_API_PORT"));
        assertFalse(environment.containsKey("DOCGEN_DESKTOP_ENABLED"));
        assertEquals("/usr/bin", environment.get("PATH"));
        assertEquals("gsk_user_choice", environment.get("GROQ_API_KEY"), "provider configuration stays the user's choice");
        assertEquals("false", environment.get("DOCGEN_ALLOW_REMOTE"), "the launcher never enables remote providers");
        assertEquals("ana", environment.get("DOCGEN_API_USERNAME"));
        assertEquals(temp.resolve("data/docgen").toString(), environment.get("DOCGEN_DATA_DIR"));
    }

    @Test
    void derivesAStableValidUsername() {
        assertEquals("bruno", BackendConfiguration.username(Map.of("DOCGEN_API_USERNAME", "bruno"), "other"));
        assertEquals("ana.souza", BackendConfiguration.username(Map.of("DOCGEN_API_USERNAME", "bad name!"), "ana.souza"));
        assertEquals("Jos--Silva", BackendConfiguration.username(Map.of(), "José Silva"));
        assertEquals("DOMAIN-ana", BackendConfiguration.username(Map.of(), "DOMAIN\\ana"));
        assertEquals("desktop", BackendConfiguration.username(Map.of(), "???"));
        assertEquals("desktop", BackendConfiguration.username(Map.of(), null));
        assertEquals(64, BackendConfiguration.username(Map.of(), "x".repeat(100)).length());
    }

    @Test
    void readsAFixedPortFromTheExistingVariable() {
        assertEquals(0, BackendConfiguration.portFromEnvironment(Map.of()));
        assertEquals(8080, BackendConfiguration.portFromEnvironment(Map.of("DOCGEN_API_PORT", "8080")));
        for (String invalid : new String[]{"0", "70000", "http", "-1"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> BackendConfiguration.portFromEnvironment(Map.of("DOCGEN_API_PORT", invalid)), invalid);
        }
    }

    @Test
    void refusesAnIncompleteLayout() {
        InstallationLayout missing = InstallationLayout.resolve(LINUX, Map.of(), temp.resolve("home"),
                temp.resolve("app/docgen-launcher.jar"), temp.resolve("absent.jar"), null);
        assertThrows(IllegalArgumentException.class, () -> BackendConfiguration.forInstallation(
                RuntimeEnvironment.of(Path.of("/jdk"), Runtime.version(), "Test", LINUX), missing, Map.of(), "ana", 0,
                Duration.ofSeconds(30)));
    }
}
