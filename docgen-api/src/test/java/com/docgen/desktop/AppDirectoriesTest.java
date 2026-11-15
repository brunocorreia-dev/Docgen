package com.docgen.desktop;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** ADR 0001: per-OS data directories, honoring only absolute, normalized environment paths. */
class AppDirectoriesTest {
    private static final Path HOME = Path.of("/home/ana");

    @Test
    void linuxFollowsXdgAndKeepsTheDocGen2Location() {
        assertEquals(Path.of("/home/ana/.local/share/docgen"),
                AppDirectories.dataDirectory(Platform.LINUX, Map.of(), HOME));
        assertEquals(Path.of("/srv/data/docgen"),
                AppDirectories.dataDirectory(Platform.LINUX, Map.of("XDG_DATA_HOME", "/srv/data"), HOME));
        for (String ignored : new String[]{"relative/data", "/srv/../etc", ""}) {
            assertEquals(Path.of("/home/ana/.local/share/docgen"),
                    AppDirectories.dataDirectory(Platform.LINUX, Map.of("XDG_DATA_HOME", ignored), HOME), ignored);
        }
    }

    @Test
    void macosUsesApplicationSupport() {
        assertEquals(Path.of("/home/ana/Library/Application Support/DocGen"),
                AppDirectories.dataDirectory(Platform.MACOS, Map.of("XDG_DATA_HOME", "/srv/data"), HOME));
    }

    @Test
    void windowsUsesTheLocalNotTheRoamingProfile() {
        assertEquals(Path.of("/c/Users/ana/AppData/Local/DocGen"), AppDirectories.dataDirectory(Platform.WINDOWS,
                Map.of("LOCALAPPDATA", "/c/Users/ana/AppData/Local", "APPDATA", "/c/Users/ana/AppData/Roaming"), HOME));
        assertEquals(HOME.resolve("AppData").resolve("Local").resolve("DocGen"),
                AppDirectories.dataDirectory(Platform.WINDOWS, Map.of("APPDATA", "/c/Users/ana/AppData/Roaming"), HOME));
    }

    @Test
    void theDevelopmentOverrideMustBeAbsoluteAndNormalized() {
        assertEquals(Path.of("/tmp/docgen-dev"), AppDirectories.dataDirectory(Platform.LINUX,
                Map.of(AppDirectories.DATA_OVERRIDE, "/tmp/docgen-dev"), HOME));
        for (String invalid : new String[]{"data", "./x", "/var/lib/../../etc"}) {
            assertThrows(IllegalStateException.class, () -> AppDirectories.dataDirectory(Platform.LINUX,
                    Map.of(AppDirectories.DATA_OVERRIDE, invalid), HOME), invalid);
        }
    }

    @Test
    void logsLiveInsideTheDataDirectory() {
        assertEquals(Path.of("/d/logs"), AppDirectories.logDirectory(Path.of("/d")));
    }

    @Test
    void recognizesTheOperatingSystem() {
        assertEquals(Platform.LINUX, Platform.detect("Linux"));
        assertEquals(Platform.WINDOWS, Platform.detect("Windows 11"));
        assertEquals(Platform.MACOS, Platform.detect("Mac OS X"));
        assertEquals(Platform.OTHER, Platform.detect("FreeBSD"));
        assertEquals(Platform.OTHER, Platform.detect(null));
    }
}
