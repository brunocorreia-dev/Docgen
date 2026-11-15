package com.docgen.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** 256-bit random tokens and the SHA-256 hashes under which they are stored. */
final class Secrets {
    /** 32 random bytes in unpadded base64url. */
    static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private Secrets() {
    }

    static String random256() {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    static boolean wellFormed(String token) {
        return token != null && FORMAT.matcher(token).matches();
    }

    static String hash(String token) {
        return HexFormat.of().formatHex(digest(token));
    }

    /** Compares the hashes, so the time taken does not depend on where the inputs differ. */
    static boolean matches(String candidate, String expected) {
        if (candidate == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(digest(candidate), digest(expected));
    }

    private static byte[] digest(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }
}
