package com.docgen.launcher;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherOptionsTest {
    @Test
    void noArgumentsOpensTheDesktopWindow() {
        assertEquals(LauncherOptions.Mode.DESKTOP, LauncherOptions.parse(List.of()).mode());
    }

    @Test
    void recognizesHelpAndVersion() {
        assertEquals(LauncherOptions.Mode.HELP, LauncherOptions.parse(List.of("--help")).mode());
        assertEquals(LauncherOptions.Mode.HELP, LauncherOptions.parse(List.of("-h")).mode());
        assertEquals(LauncherOptions.Mode.VERSION, LauncherOptions.parse(List.of("--version")).mode());
    }

    @Test
    void rejectsUnknownAndConflictingOptions() {
        assertThrows(IllegalArgumentException.class, () -> LauncherOptions.parse(List.of("--bogus")));
        assertThrows(IllegalArgumentException.class, () -> LauncherOptions.parse(List.of("--help", "--version")));
        assertThrows(IllegalArgumentException.class, () -> LauncherOptions.parse(List.of("--diagnose=yes")));
    }

    @Test
    void parsesTheBackendJarOverrideInBothForms() {
        LauncherOptions separate = LauncherOptions.parse(List.of("--diagnose", "--backend-jar", "/opt/docgen-api.jar"));
        assertEquals(LauncherOptions.Mode.DIAGNOSE, separate.mode());
        assertEquals(Path.of("/opt/docgen-api.jar"), separate.backendJar());
        assertEquals(Path.of("/x/api.jar"), LauncherOptions.parse(List.of("--backend-jar=/x/api.jar")).backendJar());
        assertThrows(IllegalArgumentException.class, () -> LauncherOptions.parse(List.of("--backend-jar")));
        assertThrows(IllegalArgumentException.class, () -> LauncherOptions.parse(List.of("--backend-jar=")));
        assertThrows(IllegalArgumentException.class,
                () -> LauncherOptions.parse(List.of("--backend-jar=/a.jar", "--backend-jar=/b.jar")));
    }

    @Test
    void parsesCheckModePortAndStartupTimeout() {
        LauncherOptions options = LauncherOptions.parse(List.of("--check", "--port", "8080", "--startup-timeout=30"));
        assertEquals(LauncherOptions.Mode.CHECK, options.mode());
        assertEquals(8080, options.port());
        assertEquals(java.time.Duration.ofSeconds(30), options.startupTimeout());
        LauncherOptions defaults = LauncherOptions.parse(List.of());
        assertEquals(null, defaults.port());
        assertEquals(BackendConfiguration.DEFAULT_STARTUP_TIMEOUT, defaults.startupTimeout());
        for (List<String> invalid : List.of(List.of("--port", "0"), List.of("--port=70000"), List.of("--port", "-1"),
                List.of("--port", "80a"), List.of("--startup-timeout", "5"), List.of("--startup-timeout=601"),
                List.of("--port", "1", "--port", "2"), List.of("--check", "--diagnose"))) {
            assertThrows(IllegalArgumentException.class, () -> LauncherOptions.parse(invalid), invalid.toString());
        }
    }

    @Test
    void neutralizesControlCharactersInEchoedArguments() {
        String printable = LauncherOptions.printable("--x\u001b[31m\n" + "y".repeat(200));
        assertFalse(printable.chars().anyMatch(Character::isISOControl));
        assertEquals(80, printable.length());
    }

    @Test
    void reportsTheBuildVersion() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = DocGenLauncher.run(List.of("--version"), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertEquals(DocGenLauncher.EXIT_OK, code);
        assertTrue(out.toString(StandardCharsets.UTF_8).startsWith("DocGen Launcher 2."));
    }

    @Test
    void unknownOptionsExitWithUsageError() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = DocGenLauncher.run(List.of("--bogus"), new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(DocGenLauncher.EXIT_USAGE, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Opção desconhecida: --bogus"));
    }
}
