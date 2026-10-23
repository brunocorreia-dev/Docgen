package com.docgen.desktop;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * The per-start session identifier and secret. The launcher generates it and
 * writes {@link #encode()} as the first line of the child's stdin; the API
 * parses it once at startup. {@link #toString()} never reveals the secret.
 */
public final class DesktopBootstrap {
    private static final String KEYWORD = "bootstrap";

    private final String sessionId;
    private final byte[] secret;

    private DesktopBootstrap(String sessionId, byte[] secret) {
        this.sessionId = sessionId;
        this.secret = secret.clone();
    }

    public static DesktopBootstrap generate(SecureRandom random) {
        return new DesktopBootstrap(
                DesktopProtocol.encode(DesktopProtocol.randomBytes(random, DesktopProtocol.SESSION_ID_BYTES)),
                DesktopProtocol.randomBytes(random, DesktopProtocol.SECRET_BYTES));
    }

    /** Parses the bootstrap line strictly; any deviation is rejected. */
    public static DesktopBootstrap parse(String line) throws DesktopProtocolException {
        if (line == null || line.length() > DesktopProtocol.MAX_LINE_LENGTH) {
            throw new DesktopProtocolException("invalid bootstrap line");
        }
        String[] parts = line.split(" ", -1);
        if (parts.length != 4 || !DesktopProtocol.VERSION.equals(parts[0]) || !KEYWORD.equals(parts[1])) {
            throw new DesktopProtocolException("invalid bootstrap line");
        }
        byte[] session = DesktopProtocol.decode16(parts[2]);
        byte[] secret = DesktopProtocol.decode32(parts[3]);
        if (session == null || secret == null) {
            throw new DesktopProtocolException("invalid bootstrap line");
        }
        try {
            return new DesktopBootstrap(parts[2], secret);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    /** The bootstrap line, without a line terminator. It contains the secret: never log it. */
    public String encode() {
        return DesktopProtocol.VERSION + " " + KEYWORD + " " + sessionId + " " + DesktopProtocol.encode(secret);
    }

    public String sessionId() {
        return sessionId;
    }

    /** Derives the per-purpose keys used by both sides of the protocol. */
    public DesktopSession open() {
        return new DesktopSession(sessionId, secret);
    }

    @Override
    public String toString() {
        return "DesktopBootstrap[sessionId=" + sessionId + ", secret=<redacted>]";
    }
}
