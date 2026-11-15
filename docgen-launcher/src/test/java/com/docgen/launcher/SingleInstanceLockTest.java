package com.docgen.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SingleInstanceLockTest {
    @TempDir
    Path temp;

    @Test
    void onlyOneLauncherHoldsTheLock() throws Exception {
        Path state = temp.resolve("state");
        try (SingleInstanceLock first = SingleInstanceLock.acquire(state).orElseThrow()) {
            assertTrue(SingleInstanceLock.acquire(state).isEmpty());
        }
        Optional<SingleInstanceLock> again = SingleInstanceLock.acquire(state);
        assertTrue(again.isPresent(), "released on close");
        again.get().close();
    }

    @Test
    void anotherProcessHoldingTheLockIsDetectedAndACrashReleasesIt() throws Exception {
        Path state = temp.resolve("state");
        Process holder = new ProcessBuilder(FakeBackend.javaExecutable(), "-cp", FakeBackend.classpath(),
                LockHolder.class.getName(), state.toString()).redirectErrorStream(true).start();
        try {
            BufferedReader output = new BufferedReader(new InputStreamReader(holder.getInputStream(), StandardCharsets.UTF_8));
            assertEquals("locked", output.readLine());
            assertTrue(SingleInstanceLock.acquire(state).isEmpty(), "a second launcher must not start");
        } finally {
            holder.destroyForcibly();
            holder.waitFor(10, TimeUnit.SECONDS);
        }
        Optional<SingleInstanceLock> afterCrash = SingleInstanceLock.acquire(state);
        assertTrue(afterCrash.isPresent(), "the OS releases the lock of a killed launcher");
        afterCrash.get().close();
    }

    @Test
    void refusesALockFilePlantedAsASymbolicLink() throws Exception {
        Path state = Files.createDirectories(temp.resolve("state"));
        Files.createSymbolicLink(state.resolve(SingleInstanceLock.FILE), Files.writeString(temp.resolve("target"), "x"));
        assertThrows(java.io.IOException.class, () -> SingleInstanceLock.acquire(state));
    }

    @Test
    void theBrowserOnlyReceivesLoopbackWorkspaceAddresses() {
        assertTrue(BrowserOpener.acceptable(URI.create("http://127.0.0.1:48123/#/desktop")));
        for (String address : List.of("https://127.0.0.1:48123/#/desktop", "http://localhost:48123/#/desktop",
                "http://user:pass@127.0.0.1:48123/#/desktop", "http://127.0.0.1:48123/?token=x#/desktop",
                "http://127.0.0.1:48123/#/desktop?token=x", "http://evil.example/#/desktop", "file:///etc/passwd")) {
            assertFalse(BrowserOpener.acceptable(URI.create(address)), address);
        }
        List<String> logged = new java.util.ArrayList<>();
        assertFalse(BrowserOpener.system(PlatformDetector.detect("Linux", "amd64"), logged::add)
                .open(URI.create("http://evil.example/#/desktop")));
        assertEquals(List.of("refused to open an unexpected address"), logged);
    }

    /** Holds the lock in a separate process until killed. */
    public static final class LockHolder {
        public static void main(String[] args) throws Exception {
            SingleInstanceLock lock = SingleInstanceLock.acquire(Path.of(args[0])).orElseThrow();
            System.out.println("locked");
            System.out.flush();
            Thread.sleep(120_000);
            lock.close();
        }
    }
}
