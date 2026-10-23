package com.docgen.desktop;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;

/**
 * Keys derived from one {@link DesktopBootstrap}, plus the sequence state of
 * each direction. Outgoing messages are numbered 1, 2, 3...; incoming ones
 * are accepted only with a valid MAC and a sequence number greater than the
 * last accepted one, which rejects forgeries, replays and reordering.
 */
public final class DesktopSession {
    private final String sessionId;
    private final SecretKeySpec readinessKey;
    private final Map<DesktopMessage.Direction, SecretKeySpec> messageKeys = new EnumMap<>(DesktopMessage.Direction.class);
    private final Map<DesktopMessage.Direction, Long> lastSent = new EnumMap<>(DesktopMessage.Direction.class);
    private final Map<DesktopMessage.Direction, Long> lastAccepted = new EnumMap<>(DesktopMessage.Direction.class);

    DesktopSession(String sessionId, byte[] secret) {
        this.sessionId = sessionId;
        SecretKeySpec master = DesktopProtocol.key(secret);
        this.readinessKey = derive(master, "readiness");
        messageKeys.put(DesktopMessage.Direction.EVENT, derive(master, "events"));
        messageKeys.put(DesktopMessage.Direction.COMMAND, derive(master, "commands"));
        for (DesktopMessage.Direction direction : DesktopMessage.Direction.values()) {
            lastSent.put(direction, 0L);
            lastAccepted.put(direction, 0L);
        }
    }

    private static SecretKeySpec derive(SecretKeySpec master, String purpose) {
        byte[] material = DesktopProtocol.hmac(master, DesktopProtocol.VERSION + " key " + purpose);
        try {
            return DesktopProtocol.key(material);
        } finally {
            Arrays.fill(material, (byte) 0);
        }
    }

    public String sessionId() {
        return sessionId;
    }

    /** Encodes the next outgoing message in {@code direction}; the result has no line terminator. */
    public synchronized String encode(DesktopMessage.Direction direction, String type, String... fields) {
        long sequence = lastSent.get(direction) + 1;
        String line = encode(new DesktopMessage(direction, sequence, type, List.of(fields)));
        lastSent.put(direction, sequence);
        return line;
    }

    /** Encodes an explicit message; exposed to the package so tests can build replays and gaps. */
    String encode(DesktopMessage message) {
        StringBuilder line = new StringBuilder(DesktopProtocol.MESSAGE_PREFIX)
                .append(message.direction().code()).append(' ')
                .append(message.sequence()).append(' ')
                .append(message.type());
        for (String field : message.fields()) {
            line.append(' ').append(field);
        }
        line.append(' ').append(DesktopProtocol.encode(mac(message)));
        if (line.length() > DesktopProtocol.MAX_LINE_LENGTH) {
            throw new IllegalArgumentException("message too long");
        }
        return line.toString();
    }

    /**
     * Verifies and accepts one incoming line. Lines with a bad MAC, the wrong
     * direction or a sequence number that does not increase are rejected, and
     * a rejected line never advances the sequence.
     */
    public synchronized DesktopMessage accept(String line, DesktopMessage.Direction expected) throws DesktopProtocolException {
        DesktopMessage message = parse(line, expected);
        if (message.sequence() <= lastAccepted.get(expected)) {
            throw new DesktopProtocolException("replayed or reordered message");
        }
        lastAccepted.put(expected, message.sequence());
        return message;
    }

    private DesktopMessage parse(String line, DesktopMessage.Direction expected) throws DesktopProtocolException {
        if (line == null || line.length() > DesktopProtocol.MAX_LINE_LENGTH || !DesktopProtocol.isMessageLine(line)) {
            throw new DesktopProtocolException("not a protocol message");
        }
        String[] parts = line.substring(DesktopProtocol.MESSAGE_PREFIX.length()).split(" ", -1);
        if (parts.length < 4 || !expected.code().equals(parts[0])) {
            throw new DesktopProtocolException("malformed message");
        }
        long sequence = sequence(parts[1]);
        List<String> fields = new ArrayList<>(Arrays.asList(parts).subList(3, parts.length - 1));
        DesktopMessage message;
        try {
            message = new DesktopMessage(expected, sequence, parts[2], fields);
        } catch (IllegalArgumentException invalid) {
            throw new DesktopProtocolException("malformed message");
        }
        byte[] presented = DesktopProtocol.decode32(parts[parts.length - 1]);
        if (presented == null || !MessageDigest.isEqual(presented, mac(message))) {
            throw new DesktopProtocolException("message authentication failed");
        }
        return message;
    }

    private static long sequence(String value) throws DesktopProtocolException {
        if (value.isEmpty() || value.length() > 18 || !value.chars().allMatch(c -> c >= '0' && c <= '9')
                || value.charAt(0) == '0') {
            throw new DesktopProtocolException("malformed message");
        }
        return Long.parseLong(value);
    }

    private byte[] mac(DesktopMessage message) {
        StringBuilder input = new StringBuilder(DesktopProtocol.VERSION).append('\n')
                .append(message.direction().code()).append('\n')
                .append(sessionId).append('\n')
                .append(message.sequence()).append('\n')
                .append(message.type());
        for (String field : message.fields()) {
            input.append('\n').append(field);
        }
        return DesktopProtocol.hmac(messageKeys.get(message.direction()), input.toString());
    }

    /**
     * The API's answer to a readiness challenge: a MAC over the launcher's
     * nonce, bound to the session, the API's process id and the local port on
     * which the challenge arrived.
     */
    public String readinessProof(String nonce, long pid, int port) {
        if (!DesktopProtocol.isNonce(nonce)) {
            throw new IllegalArgumentException("invalid nonce");
        }
        return DesktopProtocol.encode(readinessMac(nonce, pid, port));
    }

    /** Constant-time verification of {@link #readinessProof}; malformed proofs are simply invalid. */
    public boolean verifyReadinessProof(String nonce, long pid, int port, String proof) {
        byte[] presented = DesktopProtocol.decode32(proof);
        return DesktopProtocol.isNonce(nonce) && presented != null
                && MessageDigest.isEqual(presented, readinessMac(nonce, pid, port));
    }

    private byte[] readinessMac(String nonce, long pid, int port) {
        return DesktopProtocol.hmac(readinessKey, DesktopProtocol.VERSION + "\nreadiness\n" + sessionId + "\n"
                + nonce + "\n" + pid + "\n" + port);
    }

    @Override
    public String toString() {
        return "DesktopSession[sessionId=" + sessionId + "]";
    }
}
