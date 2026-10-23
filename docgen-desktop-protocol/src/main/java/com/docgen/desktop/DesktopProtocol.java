package com.docgen.desktop;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Wire format shared by the desktop launcher and the API it starts.
 *
 * <p>The launcher generates a fresh session secret for every start and hands
 * it to its child process on stdin, the only channel no other process can
 * read or write (it never appears in arguments, the environment, files or
 * HTTP). Both sides derive one HMAC-SHA256 key per purpose from it:</p>
 *
 * <ul>
 *   <li><b>readiness</b>: the API proves possession of the secret over HTTP
 *   by MACing a fresh launcher nonce bound to its pid and the port the
 *   request arrived on, so a foreign service on the port cannot pass;</li>
 *   <li><b>events</b>: lines the API writes to stdout for the launcher;</li>
 *   <li><b>commands</b>: lines the launcher writes to the API's stdin.</li>
 * </ul>
 *
 * <p>Messages carry a strictly increasing sequence number inside the MAC, so
 * a receiver rejects forged, replayed and reordered lines. Values on the wire
 * are restricted to a small ASCII alphabet, which keeps the parser trivial.</p>
 */
public final class DesktopProtocol {
    public static final String VERSION = "docgen-desktop/1";
    /** Marks protocol lines among ordinary log output on the API's stdout. */
    public static final String MESSAGE_PREFIX = "@@" + VERSION + " ";
    public static final int MAX_LINE_LENGTH = 512;
    public static final int NONCE_BYTES = 32;

    static final int SECRET_BYTES = 32;
    static final int SESSION_ID_BYTES = 16;
    static final Pattern TYPE = Pattern.compile("[A-Z][A-Z_]{0,31}");
    static final Pattern FIELD = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final Pattern BASE64URL_22 = Pattern.compile("[A-Za-z0-9_-]{22}");
    private static final Pattern BASE64URL_43 = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private DesktopProtocol() {
    }

    /** A fresh 256-bit challenge for the readiness handshake. */
    public static String newNonce(SecureRandom random) {
        return encode(randomBytes(random, NONCE_BYTES));
    }

    public static boolean isNonce(String value) {
        return decode32(value) != null;
    }

    /** True for lines that claim to be protocol messages; they are still verified before use. */
    public static boolean isMessageLine(String line) {
        return line != null && line.startsWith(MESSAGE_PREFIX);
    }

    static byte[] randomBytes(SecureRandom random, int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    static String encode(byte[] bytes) {
        return ENCODER.encodeToString(bytes);
    }

    /** Decodes canonical, unpadded base64url of exactly 32 bytes; anything else yields {@code null}. */
    static byte[] decode32(String value) {
        return value != null && BASE64URL_43.matcher(value).matches() ? canonical(value) : null;
    }

    static byte[] decode16(String value) {
        return value != null && BASE64URL_22.matcher(value).matches() ? canonical(value) : null;
    }

    private static byte[] canonical(String value) {
        byte[] bytes = DECODER.decode(value);
        // Reject encodings whose unused trailing bits are set, so every value has exactly one spelling.
        return encode(bytes).equals(value) ? bytes : null;
    }

    static byte[] hmac(SecretKeySpec key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(message.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException | InvalidKeyException unavailable) {
            throw new IllegalStateException("HmacSHA256 is unavailable", unavailable);
        }
    }

    static SecretKeySpec key(byte[] material) {
        return new SecretKeySpec(material, "HmacSHA256");
    }
}
