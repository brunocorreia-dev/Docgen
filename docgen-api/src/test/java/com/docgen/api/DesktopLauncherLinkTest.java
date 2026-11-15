package com.docgen.api;

import com.docgen.desktop.DesktopMessage;
import com.docgen.desktop.DesktopProtocol;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopLauncherLinkTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void establishesTheSessionFromTheFirstStdinLine() throws Exception {
        FakeLauncherSide launcher = new FakeLauncherSide();
        DesktopLauncherLink link = new DesktopLauncherLink(launcher.streams(), TIMEOUT, launcher::recordShutdown);

        assertEquals(launcher.session().sessionId(), link.session().sessionId());
        link.announceReady(48123);
        DesktopMessage ready = launcher.awaitEvent("READY", TIMEOUT);
        assertEquals("48123", ready.field(0));
        assertEquals(String.valueOf(ProcessHandle.current().pid()), ready.field(1));
        assertFalse(launcher.output().contains(launcher.secret()));
    }

    @Test
    void refusesToStartWithoutALauncher() {
        FakeLauncherSide silent = new FakeLauncherSide(false);
        IllegalStateException timeout = assertThrows(IllegalStateException.class,
                () -> new DesktopLauncherLink(silent.streams(), Duration.ofMillis(300), silent::recordShutdown));
        assertTrue(timeout.getMessage().contains("requires the DocGen launcher"));

        FakeLauncherSide closed = new FakeLauncherSide(false);
        closed.closeLifeline();
        assertThrows(IllegalStateException.class,
                () -> new DesktopLauncherLink(closed.streams(), TIMEOUT, closed::recordShutdown));
        assertEquals(0, closed.shutdowns());
    }

    @Test
    void refusesAnInvalidSessionLineWithoutEchoingIt() {
        FakeLauncherSide forged = new FakeLauncherSide(false);
        forged.writeLine(DesktopProtocol.VERSION + " bootstrap not-a-session not-a-secret");
        IllegalStateException invalid = assertThrows(IllegalStateException.class,
                () -> new DesktopLauncherLink(forged.streams(), TIMEOUT, forged::recordShutdown));
        assertFalse(invalid.getMessage().contains("not-a-secret"));
    }

    @Test
    void dispatchesOnlyAuthenticatedCommands() throws Exception {
        FakeLauncherSide launcher = new FakeLauncherSide();
        DesktopLauncherLink link = new DesktopLauncherLink(launcher.streams(), TIMEOUT, launcher::recordShutdown);
        BlockingQueue<DesktopMessage> received = new LinkedBlockingQueue<>();
        link.addListener(command -> received.add(command));

        String forged = DesktopProtocol.MESSAGE_PREFIX + "C 1 APPROVE id code " + "A".repeat(43);
        launcher.writeLine(forged);
        launcher.writeLine("x".repeat(5_000));
        String approve = launcher.session().encode(DesktopMessage.Direction.COMMAND, "APPROVE", "id-1", "ABCD-EFGH");
        launcher.writeLine(approve);
        launcher.writeLine(approve);

        DesktopMessage accepted = received.poll(5, TimeUnit.SECONDS);
        assertNotNull(accepted);
        assertEquals("APPROVE", accepted.type());
        assertNull(received.poll(300, TimeUnit.MILLISECONDS), "forged, oversized and replayed lines are dropped");
    }

    @Test
    void shutsDownOnceWhenTheLauncherClosesTheChannel() throws Exception {
        FakeLauncherSide launcher = new FakeLauncherSide();
        new DesktopLauncherLink(launcher.streams(), TIMEOUT, launcher::recordShutdown);

        launcher.closeLifeline();

        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (launcher.shutdowns() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        Thread.sleep(100);
        assertEquals(1, launcher.shutdowns());
    }

    @Test
    void aClosedLinkIgnoresTheLaterEndOfStream() throws Exception {
        FakeLauncherSide launcher = new FakeLauncherSide();
        DesktopLauncherLink link = new DesktopLauncherLink(launcher.streams(), TIMEOUT, launcher::recordShutdown);
        link.close();
        launcher.closeLifeline();
        Thread.sleep(300);
        assertEquals(0, launcher.shutdowns(), "context shutdown must not trigger a second exit");
    }

    @Test
    void requiresALoopbackServerAddress() {
        DesktopConfig.requireLoopback("127.0.0.1");
        DesktopConfig.requireLoopback("::1");
        for (String address : new String[]{null, "", "0.0.0.0", "8.8.8.8", "::"}) {
            assertThrows(IllegalStateException.class, () -> DesktopConfig.requireLoopback(address), String.valueOf(address));
        }
    }
}
