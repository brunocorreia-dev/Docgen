package com.docgen;

/** Keeps untrusted exception/provider text from controlling or flooding a terminal. */
final class SafeText {
    private static final int MAX_TERMINAL_CHARACTERS = 300;
    private static final int MAX_DIAGNOSTIC_INPUT_CHARACTERS = 4_096;

    private SafeText() {
    }

    static String forTerminal(String value) {
        if (value == null || value.isEmpty()) {
            return "operation failed without a diagnostic message";
        }
        boolean inputTruncated = value.length() > MAX_DIAGNOSTIC_INPUT_CHARACTERS;
        String bounded = inputTruncated ? value.substring(0, MAX_DIAGNOSTIC_INPUT_CHARACTERS) : value;
        if (!bounded.isEmpty() && Character.isHighSurrogate(bounded.charAt(bounded.length() - 1))) {
            bounded = bounded.substring(0, bounded.length() - 1);
        }
        String redacted = SecretRedactor.redact(bounded);
        StringBuilder safe = new StringBuilder(Math.min(redacted.length(), MAX_TERMINAL_CHARACTERS));
        boolean previousWhitespace = false;
        boolean truncated = inputTruncated;
        for (int offset = 0; offset < redacted.length();) {
            int codePoint = redacted.codePointAt(offset);
            offset += Character.charCount(codePoint);
            boolean unsafe = Character.isISOControl(codePoint)
                    || Character.getType(codePoint) == Character.FORMAT;
            boolean whitespace = unsafe || Character.isWhitespace(codePoint);
            if (whitespace) {
                if (!previousWhitespace && !safe.isEmpty() && safe.length() < MAX_TERMINAL_CHARACTERS) {
                    safe.append(' ');
                }
                previousWhitespace = true;
            } else {
                if (safe.length() + Character.charCount(codePoint) > MAX_TERMINAL_CHARACTERS) {
                    truncated = true;
                    break;
                }
                safe.appendCodePoint(codePoint);
                previousWhitespace = false;
            }
        }
        String result = safe.toString().strip();
        if (result.isEmpty()) {
            return "operation failed without a safe diagnostic message";
        }
        return truncated ? result + "..." : result;
    }
}
