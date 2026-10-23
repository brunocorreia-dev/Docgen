package com.docgen.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallationLayoutTest {
    private static final PlatformDetector.Platform LINUX = PlatformDetector.detect("Linux", "amd64");
    private static final PlatformDetector.Platform WINDOWS = PlatformDetector.detect("Windows 11", "amd64");
    private static final PlatformDetector.Platform MACOS = PlatformDetector.detect("Mac OS X", "aarch64");

    @TempDir
    Path temp;

    @Test
    void linuxDefaultsFollowXdgAndMatchTheApiDataDirectory() {
        Path home = temp.resolve("home");
        InstallationLayout layout = InstallationLayout.resolve(LINUX, Map.of(), home,
                temp.resolve("app/docgen-launcher.jar"), null, null);

        assertEquals(home.resolve(".local/share/docgen"), layout.dataDirectory().path());
        assertEquals(home.resolve(".local/state/docgen"), layout.stateDirectory().path());
        assertEquals(home.resolve(".local/state/docgen/logs"), layout.logDirectory().path());
        assertFalse(layout.packaged());
    }

    @Test
    void honoursAbsoluteXdgVariablesAndIgnoresRelativeOnes() {
        Path home = temp.resolve("home");
        InstallationLayout absolute = InstallationLayout.resolve(LINUX,
                Map.of("XDG_DATA_HOME", "/srv/data", "XDG_STATE_HOME", "/srv/state"), home,
                temp.resolve("app/docgen-launcher.jar"), null, null);
        assertEquals(Path.of("/srv/data/docgen"), absolute.dataDirectory().path());
        assertEquals(Path.of("/srv/state/docgen/logs"), absolute.logDirectory().path());

        InstallationLayout relative = InstallationLayout.resolve(LINUX,
                Map.of("XDG_DATA_HOME", "relative/data", "XDG_STATE_HOME", "/srv/../etc"), home,
                temp.resolve("app/docgen-launcher.jar"), null, null);
        assertEquals(home.resolve(".local/share/docgen"), relative.dataDirectory().path());
        assertEquals(home.resolve(".local/state/docgen"), relative.stateDirectory().path());
    }

    @Test
    void docgenDataDirMustBeAbsoluteAndNormalized() {
        Path home = temp.resolve("home");
        InstallationLayout configured = InstallationLayout.resolve(LINUX, Map.of("DOCGEN_DATA_DIR", "/var/lib/docgen-user"),
                home, temp.resolve("app/docgen-launcher.jar"), null, null);
        assertEquals(Path.of("/var/lib/docgen-user"), configured.dataDirectory().path());

        for (String invalid : new String[]{"data", "/var/lib/../../etc", "./x"}) {
            InstallationLayout layout = InstallationLayout.resolve(LINUX, Map.of("DOCGEN_DATA_DIR", invalid),
                    home, temp.resolve("app/docgen-launcher.jar"), null, null);
            assertFalse(layout.dataDirectory().valid(), invalid);
            assertNull(layout.dataDirectory().path());
        }
    }

    @Test
    void windowsAndMacosUseTheirPerUserConventions() {
        Path home = Path.of("/Users/ana");
        InstallationLayout mac = InstallationLayout.resolve(MACOS, Map.of(), home,
                Path.of("/Applications/DocGen.app/Contents/app/docgen-launcher.jar"), null, null);
        assertEquals(home.resolve("Library/Application Support/DocGen/data"), mac.dataDirectory().path());
        assertEquals(home.resolve("Library/Logs/DocGen"), mac.logDirectory().path());

        InstallationLayout windows = InstallationLayout.resolve(WINDOWS, Map.of("LOCALAPPDATA", "/c/Users/ana/AppData/Local"),
                home, Path.of("/c/Program Files/DocGen/app/docgen-launcher.jar"), null, null);
        assertEquals(Path.of("/c/Users/ana/AppData/Local/DocGen/data"), windows.dataDirectory().path());
        assertEquals(Path.of("/c/Users/ana/AppData/Local/DocGen/logs"), windows.logDirectory().path());
    }

    @Test
    void findsThePackagedBackendNextToTheLauncherIndependentlyOfTheWorkingDirectory() throws IOException {
        Path appDirectory = Files.createDirectories(temp.resolve("DocGen/lib/app"));
        Path backend = Files.createDirectories(appDirectory.resolve("backend")).resolve("docgen-api.jar");
        Files.writeString(backend, "jar");
        InstallationLayout layout = InstallationLayout.resolve(LINUX, Map.of(), temp.resolve("home"),
                appDirectory.resolve("docgen-launcher.jar"), null, temp.resolve("DocGen/bin/DocGen").toString());

        assertTrue(layout.packaged());
        assertEquals(temp.resolve("DocGen"), layout.installationDirectory());
        assertEquals(backend, layout.backendJar());
        assertEquals(appDirectory.resolve("backend/docgen-api.jar"), layout.backendCandidates().getFirst());
    }

    @Test
    void findsTheBackendOfTheSourceCheckoutDuringDevelopment() throws IOException {
        Path repository = temp.resolve("repo");
        Path classes = Files.createDirectories(repository.resolve("docgen-launcher/target/classes"));
        Path backend = Files.createDirectories(repository.resolve("docgen-api/target")).resolve("docgen-api.jar");
        Files.writeString(backend, "jar");

        InstallationLayout fromClasses = InstallationLayout.resolve(LINUX, Map.of(), temp.resolve("home"), classes, null, null);
        assertEquals(backend, fromClasses.backendJar());

        InstallationLayout fromJar = InstallationLayout.resolve(LINUX, Map.of(), temp.resolve("home"),
                repository.resolve("docgen-launcher/target/docgen-launcher.jar"), null, null);
        assertEquals(backend, fromJar.backendJar());
    }

    @Test
    void packagedLaunchersNeverFallBackToADevelopmentCheckout() throws IOException {
        Path repository = temp.resolve("repo");
        Files.createDirectories(repository.resolve("docgen-api/target"));
        Files.writeString(repository.resolve("docgen-api/target/docgen-api.jar"), "jar");
        Path launcher = Files.createDirectories(repository.resolve("docgen-launcher/target")).resolve("docgen-launcher.jar");

        InstallationLayout layout = InstallationLayout.resolve(LINUX, Map.of(), temp.resolve("home"), launcher, null,
                repository.resolve("bin/DocGen").toString());
        assertNull(layout.backendJar());
        assertEquals(2, layout.backendCandidates().size());
    }

    @Test
    void automaticCandidatesRejectSymbolicLinks() throws IOException {
        Path app = Files.createDirectories(temp.resolve("app"));
        Path elsewhere = Files.writeString(temp.resolve("elsewhere.jar"), "jar");
        Files.createSymbolicLink(app.resolve("docgen-api.jar"), elsewhere);

        InstallationLayout layout = InstallationLayout.resolve(LINUX, Map.of(), temp.resolve("home"),
                app.resolve("docgen-launcher.jar"), null, null);
        assertNull(layout.backendJar());
    }

    @Test
    void anExplicitOverrideIsTheOnlyCandidate() throws IOException {
        Path custom = Files.writeString(temp.resolve("custom.jar"), "jar");
        InstallationLayout layout = InstallationLayout.resolve(LINUX, Map.of(), temp.resolve("home"),
                temp.resolve("app/docgen-launcher.jar"), custom, null);
        assertEquals(custom, layout.backendJar());
        assertEquals(1, layout.backendCandidates().size());

        InstallationLayout missing = InstallationLayout.resolve(LINUX, Map.of(), temp.resolve("home"),
                temp.resolve("app/docgen-launcher.jar"), temp.resolve("missing.jar"), null);
        assertNull(missing.backendJar());
    }

    @Test
    void persistentDirectoriesMayNotLiveInsideThePackagedInstallation() {
        Path root = temp.resolve("DocGen");
        InstallationLayout layout = InstallationLayout.resolve(LINUX,
                Map.of("DOCGEN_DATA_DIR", root.resolve("data").toString()), temp.resolve("home"),
                root.resolve("lib/app/docgen-launcher.jar"), null, root.resolve("bin/DocGen").toString());
        assertFalse(layout.dataDirectory().valid());
        assertTrue(layout.dataDirectory().problem().contains("dentro da instalação"));
        assertTrue(layout.logDirectory().valid());
    }
}
