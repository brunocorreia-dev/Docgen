package com.docgen.desktop;

import com.docgen.api.PrivateFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** ADR 0001: Chromium app mode, then the default browser, then the platform command; never a shell. */
class BrowserOpenerTest {
    private static final String TOKEN = "abcDEF0123456789_-abcDEF0123456789_-abcDEF0";
    private static final URI LAUNCH = URI.create("http://127.0.0.1:48123/launch#" + TOKEN);

    @TempDir
    Path temp;

    /** A started program that exits with {@code exitCode}, or keeps running when it is null. */
    private static Process process(Integer exitCode) {
        return new Process() {
            @Override
            public OutputStream getOutputStream() {
                return OutputStream.nullOutputStream();
            }

            @Override
            public InputStream getInputStream() {
                return new ByteArrayInputStream(new byte[0]);
            }

            @Override
            public InputStream getErrorStream() {
                return new ByteArrayInputStream(new byte[0]);
            }

            @Override
            public int waitFor() {
                return exitCode == null ? 0 : exitCode;
            }

            @Override
            public boolean waitFor(long timeout, TimeUnit unit) {
                return exitCode != null;
            }

            @Override
            public int exitValue() {
                if (exitCode == null) {
                    throw new IllegalThreadStateException("running");
                }
                return exitCode;
            }

            @Override
            public void destroy() {
            }
        };
    }

    private Path executable(String name) throws IOException {
        Path file = Files.writeString(temp.resolve(name), "#!/bin/sh\n");
        assumeTrue(file.toFile().setExecutable(true, true), "the file system cannot mark files executable");
        return file;
    }

    @Test
    void onlyTheLaunchAddressBuiltByTheLauncherIsAccepted() {
        assertTrue(BrowserOpener.acceptable(LAUNCH));
        for (String address : List.of("https://127.0.0.1:48123/launch#" + TOKEN, "http://localhost:48123/launch#" + TOKEN,
                "http://user:pass@127.0.0.1:48123/launch#" + TOKEN, "http://127.0.0.1:48123/launch?x=1#" + TOKEN,
                "http://127.0.0.1:48123/other#" + TOKEN, "http://127.0.0.1/launch#" + TOKEN,
                "http://127.0.0.1:48123/launch#short", "http://evil.example/launch#" + TOKEN, "file:///etc/passwd")) {
            assertFalse(BrowserOpener.acceptable(URI.create(address)), address);
        }
        assertFalse(BrowserOpener.acceptable(null));
    }

    @Test
    void triesEachOpenerInOrderAndRefusesForeignAddresses() {
        List<String> tried = new ArrayList<>();
        BrowserOpener chain = BrowserOpener.firstThatWorks(List.of(
                address -> tried.add("chromium") && false,
                address -> tried.add("desktop") && true,
                address -> tried.add("command")));

        assertTrue(chain.open(LAUNCH));
        assertEquals(List.of("chromium", "desktop"), tried);
        tried.clear();
        assertFalse(chain.open(URI.create("http://evil.example/")));
        assertTrue(tried.isEmpty(), "a foreign address never reaches a browser");
    }

    @Test
    void chromiumAppModeGetsTheAddressAsOneSeparateArgument() throws IOException {
        Path chrome = executable("google-chrome");
        List<List<String>> started = new ArrayList<>();
        BrowserOpener opener = SystemBrowsers.chromiumApp(List.of(chrome), command -> {
            started.add(command);
            return process(null);
        });

        assertTrue(opener.open(LAUNCH), "a browser that keeps running opened a window");
        assertEquals(List.of(List.of(chrome.toString(), "--app=" + LAUNCH)), started);
    }

    @Test
    void aChromiumThatFailsFallsThroughToTheNextOneAndMissingOnesAreSkipped() throws IOException {
        Path broken = executable("chromium");
        Path edge = executable("microsoft-edge");
        List<String> started = new ArrayList<>();
        BrowserOpener opener = SystemBrowsers.chromiumApp(List.of(temp.resolve("missing"), broken, edge), command -> {
            started.add(command.getFirst());
            return process(command.getFirst().equals(broken.toString()) ? 1 : 0);
        });

        assertTrue(opener.open(LAUNCH));
        assertEquals(List.of(broken.toString(), edge.toString()), started);
        assertFalse(SystemBrowsers.chromiumApp(List.of(), command -> process(0)).open(LAUNCH));
    }

    @Test
    void thePlatformCommandSucceedsOnlyWithExitStatusZero() {
        Optional<List<String>> command = Optional.of(List.of("/usr/bin/xdg-open"));
        List<List<String>> started = new ArrayList<>();

        assertTrue(SystemBrowsers.platformCommand(command, invocation -> {
            started.add(invocation);
            return process(0);
        }).open(LAUNCH));
        assertEquals(List.of(List.of("/usr/bin/xdg-open", LAUNCH.toString())), started);
        assertFalse(SystemBrowsers.platformCommand(command, invocation -> process(3)).open(LAUNCH));
        assertFalse(SystemBrowsers.platformCommand(command, invocation -> {
            throw new IOException("not found");
        }).open(LAUNCH));
        assertFalse(SystemBrowsers.platformCommand(Optional.empty(), invocation -> process(0)).open(LAUNCH));
    }

    @Test
    void programsAreResolvedOnlyFromAbsolutePathEntries() throws IOException {
        Path bin = Files.createDirectories(temp.resolve("bin"));
        assumeTrue(PrivateFiles.posix(), "PATH lookup of xdg-open is a Linux/BSD concern");
        Path xdg = Files.writeString(bin.resolve("xdg-open"), "#!/bin/sh\n");
        assumeTrue(xdg.toFile().setExecutable(true, true));

        assertEquals(Optional.of(xdg), SystemBrowsers.onPath("xdg-open", "relative/bin:" + bin));
        assertEquals(Optional.empty(), SystemBrowsers.onPath("xdg-open", "relative/bin:.:"));
        assertEquals(Optional.empty(), SystemBrowsers.onPath("xdg-open", null));
        assertEquals(Optional.of(List.of(xdg.toString())),
                SystemBrowsers.platformCommand(Platform.LINUX, Map.of("PATH", bin.toString())));
    }

    @Test
    void knowsWhereChromiumBrowsersLiveOnEachSystem() {
        List<Path> windows = SystemBrowsers.chromiumCandidates(Platform.WINDOWS,
                Map.of("ProgramFiles", "/c/Program Files", "ProgramFiles(x86)", "relative"), Path.of("/c/Users/ana"));
        assertEquals(Path.of("/c/Program Files").resolve("Microsoft\\Edge\\Application\\msedge.exe"), windows.getFirst());
        assertEquals(3, windows.size(), "relative roots are ignored");

        List<Path> mac = SystemBrowsers.chromiumCandidates(Platform.MACOS, Map.of(), Path.of("/Users/ana"));
        assertTrue(mac.contains(Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")));
        assertTrue(mac.contains(Path.of("/Users/ana/Applications/Chromium.app/Contents/MacOS/Chromium")));
    }
}
