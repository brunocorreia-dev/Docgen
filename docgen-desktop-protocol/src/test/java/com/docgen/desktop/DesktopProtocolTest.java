package com.docgen.desktop;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopProtocolTest {
    private static final SecureRandom RANDOM = new SecureRandom();

    @Test
    void bootstrapRoundTripsAndNeverPrintsTheSecret() throws DesktopProtocolException {
        DesktopBootstrap bootstrap = DesktopBootstrap.generate(RANDOM);
        String line = bootstrap.encode();
        DesktopBootstrap parsed = DesktopBootstrap.parse(line);

        assertEquals(bootstrap.sessionId(), parsed.sessionId());
        assertEquals(line, parsed.encode());
        String secret = line.substring(line.lastIndexOf(' ') + 1);
        assertFalse(bootstrap.toString().contains(secret));
        assertFalse(bootstrap.open().toString().contains(secret));
        assertTrue(bootstrap.toString().contains("<redacted>"));
    }

    @Test
    void everyStartGetsADifferentSessionAndSecret() {
        String first = DesktopBootstrap.generate(RANDOM).encode();
        String second = DesktopBootstrap.generate(RANDOM).encode();
        assertNotEquals(first.split(" ")[2], second.split(" ")[2]);
        assertNotEquals(first.split(" ")[3], second.split(" ")[3]);
    }

    @Test
    void rejectsMalformedBootstrapLines() {
        String valid = DesktopBootstrap.generate(RANDOM).encode();
        String[] parts = valid.split(" ");
        String nonCanonicalSecret = parts[3].substring(0, 42) + flipLowBits(parts[3].charAt(42));
        for (String line : new String[]{
                null, "", valid + " ", " " + valid, valid.replace("docgen-desktop/1", "docgen-desktop/2"),
                valid.replace("bootstrap", "Bootstrap"), parts[0] + " bootstrap " + parts[2],
                parts[0] + " bootstrap " + parts[2] + " " + parts[3].substring(1),
                parts[0] + " bootstrap " + parts[2] + " " + nonCanonicalSecret,
                parts[0] + " bootstrap " + parts[2] + "  " + parts[3],
                valid + "x".repeat(DesktopProtocol.MAX_LINE_LENGTH)}) {
            assertThrows(DesktopProtocolException.class, () -> DesktopBootstrap.parse(line), String.valueOf(line));
        }
    }

    @Test
    void messagesAreAuthenticatedPerSessionAndDirection() throws DesktopProtocolException {
        DesktopBootstrap bootstrap = DesktopBootstrap.generate(RANDOM);
        DesktopSession backend = bootstrap.open();
        DesktopSession launcher = DesktopBootstrap.parse(bootstrap.encode()).open();

        String ready = backend.encode(DesktopMessage.Direction.EVENT, "READY", "48123", "4242");
        assertTrue(DesktopProtocol.isMessageLine(ready));
        DesktopMessage accepted = launcher.accept(ready, DesktopMessage.Direction.EVENT);
        assertEquals("READY", accepted.type());
        assertEquals(List.of("48123", "4242"), accepted.fields());
        assertEquals(1, accepted.sequence());

        // An event can never be replayed as a command (different key and direction marker).
        String reflected = ready.replace(" E 1 ", " C 1 ");
        assertThrows(DesktopProtocolException.class, () -> backend.accept(reflected, DesktopMessage.Direction.COMMAND));
        assertThrows(DesktopProtocolException.class, () -> backend.accept(ready, DesktopMessage.Direction.COMMAND));

        // Another session's keys do not verify.
        DesktopSession stranger = DesktopBootstrap.generate(RANDOM).open();
        String foreign = stranger.encode(DesktopMessage.Direction.EVENT, "READY", "1", "1");
        assertThrows(DesktopProtocolException.class, () -> launcher.accept(foreign, DesktopMessage.Direction.EVENT));
    }

    @Test
    void rejectsTamperedReplayedAndReorderedMessages() throws DesktopProtocolException {
        DesktopSession sender = DesktopBootstrap.generate(RANDOM).open();
        DesktopSession receiver = sender;

        String first = sender.encode(DesktopMessage.Direction.COMMAND, "APPROVE", "id-1", "ABCD-EFGH");
        String second = sender.encode(DesktopMessage.Direction.COMMAND, "DENY", "id-2");
        String third = sender.encode(DesktopMessage.Direction.COMMAND, "ARM");

        String tampered = first.replace("id-1", "id-9");
        assertThrows(DesktopProtocolException.class, () -> receiver.accept(tampered, DesktopMessage.Direction.COMMAND));

        assertEquals("DENY", receiver.accept(second, DesktopMessage.Direction.COMMAND).type());
        assertThrows(DesktopProtocolException.class, () -> receiver.accept(second, DesktopMessage.Direction.COMMAND),
                "replay");
        assertThrows(DesktopProtocolException.class, () -> receiver.accept(first, DesktopMessage.Direction.COMMAND),
                "reordered");
        assertEquals("ARM", receiver.accept(third, DesktopMessage.Direction.COMMAND).type());
    }

    @Test
    void rejectsMalformedMessageLines() {
        DesktopSession session = DesktopBootstrap.generate(RANDOM).open();
        String valid = session.encode(DesktopMessage.Direction.EVENT, "PAIRING_REQUESTED", "abc", "WXYZ-2345", "1700000000");
        String mac = valid.substring(valid.lastIndexOf(' ') + 1);
        for (String line : new String[]{
                null, "", "plain log line", DesktopProtocol.MESSAGE_PREFIX, valid + " ", valid.replace(" E ", " E  "),
                DesktopProtocol.MESSAGE_PREFIX + "E 01 READY " + mac,
                DesktopProtocol.MESSAGE_PREFIX + "E 0 READY " + mac,
                DesktopProtocol.MESSAGE_PREFIX + "E -1 READY " + mac,
                DesktopProtocol.MESSAGE_PREFIX + "E 1 ready " + mac,
                DesktopProtocol.MESSAGE_PREFIX + "E 1 READY a<b " + mac,
                DesktopProtocol.MESSAGE_PREFIX + "E 1 READY short",
                valid.substring(0, valid.length() - 1)}) {
            assertThrows(DesktopProtocolException.class, () -> session.accept(line, DesktopMessage.Direction.EVENT),
                    String.valueOf(line));
        }
    }

    @Test
    void refusesToEncodeValuesOutsideTheAlphabet() {
        DesktopSession session = DesktopBootstrap.generate(RANDOM).open();
        assertThrows(IllegalArgumentException.class, () -> session.encode(DesktopMessage.Direction.EVENT, "READY", "has space"));
        assertThrows(IllegalArgumentException.class, () -> session.encode(DesktopMessage.Direction.EVENT, "READY", "line\nbreak"));
        assertThrows(IllegalArgumentException.class, () -> session.encode(DesktopMessage.Direction.EVENT, "lower"));
        assertThrows(IllegalArgumentException.class, () -> session.encode(DesktopMessage.Direction.EVENT, "READY", ""));
    }

    @Test
    void readinessProofsBindNoncePidPortAndSession() {
        DesktopBootstrap bootstrap = DesktopBootstrap.generate(RANDOM);
        DesktopSession session = bootstrap.open();
        String nonce = DesktopProtocol.newNonce(RANDOM);
        String proof = session.readinessProof(nonce, 4242, 48123);

        assertTrue(session.verifyReadinessProof(nonce, 4242, 48123, proof));
        assertFalse(session.verifyReadinessProof(DesktopProtocol.newNonce(RANDOM), 4242, 48123, proof), "other nonce");
        assertFalse(session.verifyReadinessProof(nonce, 4243, 48123, proof), "other pid");
        assertFalse(session.verifyReadinessProof(nonce, 4242, 48124, proof), "other port");
        assertFalse(DesktopBootstrap.generate(RANDOM).open().verifyReadinessProof(nonce, 4242, 48123, proof), "other secret");
        for (String forged : new String[]{null, "", "ok", "true", proof + "A", proof.substring(1), proof.toUpperCase()}) {
            if (!proof.equals(forged)) {
                assertFalse(session.verifyReadinessProof(nonce, 4242, 48123, forged), String.valueOf(forged));
            }
        }
    }

    @Test
    void readinessKeysAreSeparatedFromMessageKeys() {
        // A proof oracle on the readiness key must never yield a valid message MAC.
        DesktopSession session = DesktopBootstrap.generate(RANDOM).open();
        String nonce = DesktopProtocol.newNonce(RANDOM);
        String proof = session.readinessProof(nonce, 1, 1);
        String forged = DesktopProtocol.MESSAGE_PREFIX + "C 1 ARM " + proof;
        assertThrows(DesktopProtocolException.class, () -> session.accept(forged, DesktopMessage.Direction.COMMAND));
    }

    @Test
    void noncesAreCanonicalBase64urlOf32Bytes() {
        String nonce = DesktopProtocol.newNonce(RANDOM);
        assertTrue(DesktopProtocol.isNonce(nonce));
        assertFalse(DesktopProtocol.isNonce(nonce.substring(1)));
        assertFalse(DesktopProtocol.isNonce(nonce + "="));
        assertFalse(DesktopProtocol.isNonce(nonce.substring(0, 42) + "+"));
        assertFalse(DesktopProtocol.isNonce(null));
        assertThrows(IllegalArgumentException.class, () -> DesktopBootstrap.generate(RANDOM).open().readinessProof("x", 1, 1));
    }

    /** Changes only the unused low bits of the final base64 character, producing a non-canonical spelling. */
    private static char flipLowBits(char last) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        int value = alphabet.indexOf(last);
        return alphabet.charAt((value & ~0b11) | ((value & 0b11) ^ 0b01));
    }
}
