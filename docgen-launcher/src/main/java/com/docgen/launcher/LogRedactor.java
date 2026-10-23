package com.docgen.launcher;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.regex.Pattern;

/**
 * Last line of defense before anything reaches a log file or the log viewer.
 * The backend is not expected to print secrets, but if it ever did, the exact
 * session secrets registered here and common credential shapes are masked.
 * Control characters (terminal escapes) are removed as well.
 */
final class LogRedactor {
    static final String MASK = "[REDACTED]";
    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("(?i)(\\bbearer\\s+)[A-Za-z0-9._~+/=-]{8,}"),
            Pattern.compile("(?i)(\\bbasic\\s+)[A-Za-z0-9+/=]{8,}"),
            Pattern.compile("(?i)(\"?(?:access_?token|accesstoken|poll_?secret|pollsecret|password|passwd|secret|api_?key|apikey|authorization)\"?\\s*[:=]\\s*\"?)[^\\s\",;]{4,}"));

    private final Set<String> secrets = new CopyOnWriteArraySet<>();

    /** Registers an exact value that must never appear in logs (ignored when too short to be meaningful). */
    void addSecret(String secret) {
        if (secret != null && secret.length() >= 8) {
            secrets.add(secret);
        }
    }

    String redact(String line) {
        if (line == null) {
            return "";
        }
        String result = line;
        for (String secret : secrets) {
            result = result.replace(secret, MASK);
        }
        for (Pattern pattern : PATTERNS) {
            result = pattern.matcher(result).replaceAll("$1" + MASK);
        }
        StringBuilder printable = new StringBuilder(result.length());
        result.codePoints().forEach(codePoint -> {
            if (codePoint == '\t' || !Character.isISOControl(codePoint)) {
                printable.appendCodePoint(codePoint);
            }
        });
        return printable.toString();
    }
}
