package com.docgen.desktop;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The browsers DocGen tries, in the order of ADR 0001:
 *
 * <ol>
 *   <li>a known Chromium-based browser in app mode ({@code --app=<url>}), which looks like an app
 *   window (Edge ships with Windows 10 and 11);</li>
 *   <li>the default browser through {@link Desktop#browse};</li>
 *   <li>the platform's own command ({@code xdg-open}, {@code open} or {@code rundll32}).</li>
 * </ol>
 *
 * <p>Every program is a fixed executable with a resolved absolute path and the address as one separate
 * argument: no shell runs, and relative {@code PATH} entries (which would resolve against the working
 * directory) are ignored.</p>
 */
final class SystemBrowsers {
    private static final List<String> LINUX_CHROMIUM = List.of("google-chrome-stable", "google-chrome", "chromium",
            "chromium-browser", "microsoft-edge-stable", "microsoft-edge", "brave-browser");
    private static final List<String> MACOS_CHROMIUM = List.of(
            "Google Chrome.app/Contents/MacOS/Google Chrome", "Chromium.app/Contents/MacOS/Chromium",
            "Microsoft Edge.app/Contents/MacOS/Microsoft Edge", "Brave Browser.app/Contents/MacOS/Brave Browser");
    private static final List<String> WINDOWS_CHROMIUM = List.of("Microsoft\\Edge\\Application\\msedge.exe",
            "Google\\Chrome\\Application\\chrome.exe", "BraveSoftware\\Brave-Browser\\Application\\brave.exe");
    /** A Chromium that keeps running started a window; one that exits non-zero this fast did not. */
    private static final Duration APP_SETTLE = Duration.ofMillis(1500);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);

    private SystemBrowsers() {
    }

    /** Starts a program; replaced in tests. */
    @FunctionalInterface
    interface ProcessStarter {
        Process start(List<String> command) throws IOException;

        static ProcessStarter system() {
            return command -> {
                Process process = new ProcessBuilder(command)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                process.getOutputStream().close();
                return process;
            };
        }
    }

    static BrowserOpener opener(Platform platform, Map<String, String> environment, Path userHome) {
        ProcessStarter starter = ProcessStarter.system();
        return BrowserOpener.firstThatWorks(List.of(
                chromiumApp(chromiumCandidates(platform, environment, userHome), starter),
                SystemBrowsers::desktopBrowse,
                platformCommand(platformCommand(platform, environment), starter)));
    }

    static BrowserOpener chromiumApp(List<Path> candidates, ProcessStarter starter) {
        return address -> {
            for (Path executable : candidates) {
                if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
                    continue;
                }
                try {
                    Process browser = starter.start(List.of(executable.toString(), "--app=" + address));
                    if (!browser.waitFor(APP_SETTLE.toMillis(), TimeUnit.MILLISECONDS) || browser.exitValue() == 0) {
                        return true;
                    }
                } catch (IOException notStartable) {
                    // Try the next browser.
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return false;
        };
    }

    static boolean desktopBrowse(URI address) {
        try {
            if (!GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(address);
                return true;
            }
            return false;
        } catch (IOException | RuntimeException | LinkageError unsupported) {
            return false;
        }
    }

    static BrowserOpener platformCommand(Optional<List<String>> command, ProcessStarter starter) {
        return address -> {
            if (command.isEmpty()) {
                return false;
            }
            List<String> invocation = new ArrayList<>(command.get());
            invocation.add(address.toString());
            try {
                Process opener = starter.start(invocation);
                // xdg-open, open and rundll32 return quickly; a non-zero status means no browser was found.
                return opener.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS) && opener.exitValue() == 0;
            } catch (IOException notStartable) {
                return false;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        };
    }

    static List<Path> chromiumCandidates(Platform platform, Map<String, String> environment, Path userHome) {
        List<Path> candidates = new ArrayList<>();
        switch (platform) {
            case WINDOWS -> {
                for (String root : List.of("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA")) {
                    absolute(environment.get(root)).ifPresent(base ->
                            WINDOWS_CHROMIUM.forEach(relative -> candidates.add(base.resolve(relative))));
                }
            }
            case MACOS -> {
                for (Path base : List.of(Path.of("/Applications"), userHome.resolve("Applications"))) {
                    MACOS_CHROMIUM.forEach(relative -> candidates.add(base.resolve(relative)));
                }
            }
            case LINUX, OTHER -> LINUX_CHROMIUM.forEach(name ->
                    onPath(name, environment.get("PATH")).ifPresent(candidates::add));
        }
        return List.copyOf(candidates);
    }

    static Optional<List<String>> platformCommand(Platform platform, Map<String, String> environment) {
        return switch (platform) {
            case WINDOWS -> absolute(environment.get("SystemRoot"))
                    .map(root -> root.resolve("System32").resolve("rundll32.exe"))
                    .filter(Files::isRegularFile)
                    .map(rundll -> List.of(rundll.toString(), "url.dll,FileProtocolHandler"));
            case MACOS -> Optional.of(Path.of("/usr/bin/open")).filter(Files::isExecutable)
                    .map(open -> List.of(open.toString()));
            case LINUX, OTHER -> onPath("xdg-open", environment.get("PATH")).map(open -> List.of(open.toString()));
        };
    }

    /** The first executable called {@code name} in an absolute {@code PATH} entry. */
    static Optional<Path> onPath(String name, String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        for (String entry : path.split(File.pathSeparator)) {
            Optional<Path> directory = absolute(entry);
            if (directory.isPresent()) {
                Path candidate = directory.get().resolve(name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> absolute(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            Path path = Path.of(value);
            return path.isAbsolute() ? Optional.of(path.normalize()) : Optional.empty();
        } catch (InvalidPathException invalid) {
            return Optional.empty();
        }
    }
}
