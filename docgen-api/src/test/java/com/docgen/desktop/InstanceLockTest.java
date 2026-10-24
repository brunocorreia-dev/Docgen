package com.docgen.desktop;

import com.docgen.api.PrivateFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** ADR 0001: one DocGen per user, and a crash never leaves the lock behind. */
class InstanceLockTest {
    @TempDir
    Path data;

    @Test
    void onlyOneHolderAtATime() throws IOException {
        try (InstanceLock first = InstanceLock.tryAcquire(data).orElseThrow()) {
            assertTrue(InstanceLock.tryAcquire(data).isEmpty());
        }
        Optional<InstanceLock> again = InstanceLock.tryAcquire(data);
        assertTrue(again.isPresent(), "released on close");
        again.get().close();
    }

    @Test
    void anotherProcessHoldingTheLockIsDetectedAndItsCrashReleasesIt() throws Exception {
        String java = ProcessHandle.current().info().command().orElse("java");
        Process holder = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                LockHolder.class.getName(), data.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            BufferedReader output = new BufferedReader(new InputStreamReader(holder.getInputStream(),
                    StandardCharsets.UTF_8));
            assertEquals("locked", output.readLine());
            assertTrue(InstanceLock.tryAcquire(data).isEmpty(), "a second DocGen must not start a server");
        } finally {
            holder.destroyForcibly();
            holder.waitFor(10, TimeUnit.SECONDS);
        }
        Optional<InstanceLock> afterCrash = InstanceLock.tryAcquire(data);
        assertTrue(afterCrash.isPresent(), "the OS releases the lock of a killed process");
        afterCrash.get().close();
    }

    @Test
    void refusesALockFilePlantedAsASymbolicLink() throws IOException {
        assumeTrue(PrivateFiles.posix(), "symbolic links need privileges on Windows");
        Files.createSymbolicLink(data.resolve(InstanceLock.FILE), Files.writeString(data.resolve("target"), "x"));

        assertThrows(IOException.class, () -> InstanceLock.tryAcquire(data));
    }

    /** Holds the lock in a separate process until killed. */
    public static final class LockHolder {
        public static void main(String[] args) throws Exception {
            InstanceLock lock = InstanceLock.tryAcquire(Path.of(args[0])).orElseThrow();
            System.out.println("locked");
            System.out.flush();
            Thread.sleep(120_000);
            lock.close();
        }
    }
}
