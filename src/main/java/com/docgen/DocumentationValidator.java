package com.docgen;

import java.io.IOException;

/**
 * Applies a deliberately small, deterministic Markdown contract before model
 * output is allowed to replace documentation files. This is structural
 * validation, not a claim that generated prose is trustworthy or correct.
 */
public final class DocumentationValidator {
    static final int MAX_OUTPUT_CHARACTERS = 2_000_000;

    private DocumentationValidator() {
    }

    /** Redacts a bounded model response before applying the output contract. */
    public static String redactAndValidate(String content, String artifact) throws IOException {
        String label = artifact == null || artifact.isBlank() ? "documentation" : artifact;
        if (content != null && content.length() > MAX_OUTPUT_CHARACTERS) {
            throw new IOException(label + " output exceeded the " + MAX_OUTPUT_CHARACTERS + " character limit.");
        }
        return validate(SecretRedactor.redact(content), artifact);
    }

    public static String validate(String content, String artifact) throws IOException {
        String label = artifact == null || artifact.isBlank() ? "documentation" : artifact;
        if (content == null || content.isBlank()) {
            throw new IOException(label + " output was empty.");
        }
        if (content.length() > MAX_OUTPUT_CHARACTERS) {
            throw new IOException(label + " output exceeded the " + MAX_OUTPUT_CHARACTERS + " character limit.");
        }
        validateCharacters(content, label);

        String normalized = content.replace("\r\n", "\n").replace('\r', '\n').strip();
        String firstLine = normalized.lines().findFirst().orElse("");
        if (!firstLine.startsWith("# ") || firstLine.substring(2).isBlank()) {
            throw new IOException(label + " output is not an unfenced Markdown document starting with an H1 heading.");
        }
        validatePassiveMarkdown(normalized, label);
        return normalized + "\n";
    }

    private static void validateCharacters(String content, String label) throws IOException {
        for (int index = 0; index < content.length();) {
            char first = content.charAt(index);
            if (Character.isHighSurrogate(first)) {
                if (index + 1 >= content.length() || !Character.isLowSurrogate(content.charAt(index + 1))) {
                    throw new IOException(label + " output contained invalid Unicode.");
                }
            } else if (Character.isLowSurrogate(first)) {
                throw new IOException(label + " output contained invalid Unicode.");
            }

            int codePoint = content.codePointAt(index);
            index += Character.charCount(codePoint);
            if ((Character.isISOControl(codePoint) && codePoint != '\n' && codePoint != '\r' && codePoint != '\t')
                    || Character.getType(codePoint) == Character.FORMAT) {
                throw new IOException(label + " output contained unsafe control characters.");
            }
        }
    }

    private static void validatePassiveMarkdown(String markdown, String label) throws IOException {
        String visible = visibleMarkdown(markdown, label);
        if (containsRawHtmlTag(visible)) {
            throw new IOException(label + " output contained raw HTML; only passive Markdown is allowed.");
        }
        if (visible.contains("![")) {
            throw new IOException(label + " output contained an image; generated documents must not trigger remote loads.");
        }

        // Inspect the complete visible source instead of approximating Markdown's
        // nested link-label grammar. Removing escapes, whitespace and entities is
        // intentionally conservative: false positives fail closed, while an
        // unsafe destination cannot hide behind label shape or simple encoding.
        if (containsDisallowedLinkDestination(visible)) {
            throw new IOException(label + " output contained a non-web or unsafe link destination.");
        }
    }

    private static String visibleMarkdown(String markdown, String label) throws IOException {
        StringBuilder visible = new StringBuilder(markdown.length());
        boolean fenced = false;
        char fenceCharacter = 0;
        int fenceLength = 0;
        boolean angleConstructOpen = false;
        int lineStart = 0;
        while (lineStart <= markdown.length()) {
            int newline = markdown.indexOf('\n', lineStart);
            int lineEnd = newline < 0 ? markdown.length() : newline;
            String line = markdown.substring(lineStart, lineEnd);
            int start = 0;
            while (start < line.length() && start < 3 && line.charAt(start) == ' ') {
                start++;
            }
            int run = fenceRun(line, start);
            if (!fenced) {
                if (!angleConstructOpen && isValidOpeningFence(line, start, run)) {
                    fenced = true;
                    fenceCharacter = line.charAt(start);
                    fenceLength = run;
                } else {
                    // Inline-code parsing has enough escape and delimiter rules
                    // that an approximation can hide active Markdown. Inspect it
                    // conservatively instead; only an unambiguously valid fenced
                    // block is exempt from the passive-output checks.
                    MaskedLine masked = maskSafeInlineCode(line, angleConstructOpen);
                    angleConstructOpen = masked.angleConstructOpen();
                    visible.append(masked.text()).append('\n');
                }
            } else if (run >= fenceLength && start < line.length()
                    && line.charAt(start) == fenceCharacter
                    && line.substring(start + run).isBlank()) {
                fenced = false;
            }
            if (newline < 0) {
                break;
            }
            lineStart = newline + 1;
        }
        if (fenced) {
            throw new IOException(label + " output contained an unclosed fenced code block.");
        }
        return visible.toString();
    }

    private static int fenceRun(String line, int start) {
        if (start >= line.length() || (line.charAt(start) != '`' && line.charAt(start) != '~')) {
            return 0;
        }
        char marker = line.charAt(start);
        int end = start;
        while (end < line.length() && line.charAt(end) == marker) {
            end++;
        }
        return end - start;
    }

    private static boolean isValidOpeningFence(String line, int start, int run) {
        if (run < 3 || start >= line.length()) {
            return false;
        }
        char marker = line.charAt(start);
        // CommonMark forbids backticks in the info string of a backtick fence.
        // Accepting one here would hide content a renderer treats as active.
        return marker != '`' || line.indexOf('`', start + run) < 0;
    }

    private static MaskedLine maskSafeInlineCode(String line, boolean angleConstructOpen) {
        char[] masked = null;
        int index = 0;
        while (index < line.length()) {
            char character = line.charAt(index);
            if (character == '<') {
                angleConstructOpen = true;
                index++;
                continue;
            }
            if (character == '>') {
                angleConstructOpen = false;
                index++;
                continue;
            }
            if (!isSingleBacktick(line, index) || isBackslashEscaped(line, index)
                    || angleConstructOpen) {
                index++;
                continue;
            }
            int closing = index + 1;
            while (closing < line.length() && !isSingleBacktick(line, closing)) {
                closing++;
            }
            if (closing >= line.length()) {
                break;
            }
            if (masked == null) {
                masked = line.toCharArray();
            }
            for (int position = index; position <= closing; position++) {
                masked[position] = ' ';
            }
            index = closing + 1;
        }
        return new MaskedLine(masked == null ? line : new String(masked), angleConstructOpen);
    }

    private static boolean isSingleBacktick(String line, int index) {
        return index < line.length() && line.charAt(index) == '`'
                && (index == 0 || line.charAt(index - 1) != '`')
                && (index + 1 >= line.length() || line.charAt(index + 1) != '`');
    }

    private static boolean isBackslashEscaped(String line, int index) {
        int backslashes = 0;
        for (int position = index - 1; position >= 0 && line.charAt(position) == '\\'; position--) {
            backslashes++;
        }
        return (backslashes & 1) == 1;
    }

    private static boolean containsRawHtmlTag(String value) {
        int state = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '<') {
                // Once a plausible tag/comment has begun, a '<' inside a
                // quoted attribute must not erase that evidence. Before the
                // tag name is established, restarting also catches '<<tag>'.
                if (state != 3) {
                    state = 1;
                }
                continue;
            }
            if (state == 0) {
                continue;
            }
            if (state == 1) {
                if (Character.isWhitespace(character)) {
                    continue;
                }
                if (character == '/') {
                    state = 2;
                    continue;
                }
                if (character == '!' || character == '?') {
                    state = 3;
                    continue;
                }
                if (isAsciiLetter(character)) {
                    state = 3;
                    continue;
                }
                state = 0;
                continue;
            }
            if (state == 2) {
                if (Character.isWhitespace(character)) {
                    continue;
                }
                if (isAsciiLetter(character)) {
                    state = 3;
                    continue;
                }
                state = 0;
                continue;
            }
            if (character == '>') {
                return true;
            }
        }
        return false;
    }

    private static boolean containsDisallowedLinkDestination(String markdown) {
        for (int index = 0; index < markdown.length(); index++) {
            if (markdown.charAt(index) != ']') {
                continue;
            }
            int marker = index + 1;
            while (marker < markdown.length() && Character.isWhitespace(markdown.charAt(marker))) {
                marker++;
            }
            if (marker < markdown.length() && markdown.charAt(marker) == '(') {
                if (isDisallowedDestination(markdown, marker + 1)) {
                    return true;
                }
            } else if (marker < markdown.length() && markdown.charAt(marker) == ':') {
                if (isDisallowedDestination(markdown, marker + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isDisallowedDestination(String markdown, int start) {
        while (start < markdown.length() && Character.isWhitespace(markdown.charAt(start))) {
            start++;
        }
        if (start < markdown.length() && markdown.charAt(start) == '<') {
            start++;
        }
        long packed = 0;
        int length = 0;
        for (int index = start; index < markdown.length();) {
            int codePoint;
            boolean fromEntity = false;
            if (markdown.charAt(index) == '&') {
                DecodedEntity entity = decodeEntity(markdown, index);
                if (entity != null) {
                    codePoint = entity.codePoint();
                    index = entity.endIndex();
                    fromEntity = true;
                } else {
                    codePoint = '&';
                    index++;
                }
            } else {
                codePoint = markdown.codePointAt(index);
                index += Character.charCount(codePoint);
            }

            if (codePoint < 0 || codePoint == '\\') {
                continue;
            }
            if (isIgnorableForScheme(codePoint)) {
                // Character references such as &Tab; are ignored by browsers
                // while interpreting a scheme. Literal whitespace instead ends
                // the Markdown destination and must not consume the next line.
                if (fromEntity) {
                    continue;
                }
                return false;
            }
            codePoint = Character.toLowerCase(codePoint);
            if (codePoint == ':') {
                return length > 0 && !isAllowedScheme(packed, length);
            }
            if (length == 0 ? codePoint >= 128 || !isAsciiLetter((char) codePoint)
                    : !isSchemeCharacter(codePoint)) {
                return false;
            }
            length++;
            if (length <= 8) {
                packed = packed << 8 | codePoint;
            }
        }
        return false;
    }

    private static boolean isSchemeCharacter(int codePoint) {
        return codePoint < 128 && (isAsciiLetter((char) codePoint)
                || codePoint >= '0' && codePoint <= '9'
                || codePoint == '+' || codePoint == '-' || codePoint == '.');
    }

    private static boolean isAllowedScheme(long packed, int length) {
        return length == 4 && packed == packAscii("http")
                || length == 5 && packed == packAscii("https")
                || length == 6 && packed == packAscii("mailto");
    }

    private static long packAscii(String value) {
        long packed = 0;
        for (int index = 0; index < value.length(); index++) {
            packed = packed << 8 | value.charAt(index);
        }
        return packed;
    }

    private static boolean isAsciiLetter(char value) {
        return value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z';
    }

    private static DecodedEntity decodeEntity(String value, int start) {
        if (value.regionMatches(true, start, "&colon;", 0, 7)) {
            return new DecodedEntity(':', start + 7);
        }
        if (value.regionMatches(true, start, "&tab;", 0, 5)) {
            return new DecodedEntity(-1, start + 5);
        }
        if (value.regionMatches(true, start, "&newline;", 0, 9)) {
            return new DecodedEntity(-1, start + 9);
        }
        if (value.regionMatches(true, start, "&plus;", 0, 6)) {
            return new DecodedEntity('+', start + 6);
        }
        if (value.regionMatches(true, start, "&period;", 0, 8)) {
            return new DecodedEntity('.', start + 8);
        }
        if (start + 2 >= value.length() || value.charAt(start + 1) != '#') {
            return null;
        }

        int digitsStart = start + 2;
        int radix = 10;
        if (digitsStart < value.length()
                && (value.charAt(digitsStart) == 'x' || value.charAt(digitsStart) == 'X')) {
            radix = 16;
            digitsStart++;
        }
        int digitsEnd = digitsStart;
        int maximumDigits = radix == 16 ? 6 : 7;
        while (digitsEnd < value.length() && digitsEnd - digitsStart < maximumDigits
                && Character.digit(value.charAt(digitsEnd), radix) >= 0) {
            digitsEnd++;
        }
        if (digitsEnd == digitsStart) {
            return null;
        }
        int entityEnd = digitsEnd < value.length() && value.charAt(digitsEnd) == ';'
                ? digitsEnd + 1 : digitsEnd;
        try {
            int codePoint = Integer.parseInt(value.substring(digitsStart, digitsEnd), radix);
            if (!Character.isValidCodePoint(codePoint)) {
                return new DecodedEntity(-1, entityEnd);
            }
            return new DecodedEntity(codePoint, entityEnd);
        } catch (NumberFormatException ignored) {
            return new DecodedEntity(-1, entityEnd);
        }
    }

    private static boolean isIgnorableForScheme(int codePoint) {
        return Character.isWhitespace(codePoint)
                || Character.isISOControl(codePoint)
                || Character.getType(codePoint) == Character.FORMAT;
    }

    private record DecodedEntity(int codePoint, int endIndex) {
    }

    private record MaskedLine(String text, boolean angleConstructOpen) {
    }
}
