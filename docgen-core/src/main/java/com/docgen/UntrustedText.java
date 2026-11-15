package com.docgen;

import java.text.Normalizer;

/**
 * Normalizes text that crosses a trust boundary (user input, extracted PDF
 * text, model output) before it is stored, prompted or displayed. It strips
 * control and invisible format characters (including bidirectional overrides),
 * drops unpaired surrogates, unifies line endings, and bounds the length on a
 * code point boundary. It does not make text safe to interpret as markup;
 * renderers must still treat it as plain text.
 */
public final class UntrustedText {
    private UntrustedText() {
    }

    public record Bounded(String text, boolean truncated) {
    }

    /** Single-line value: every whitespace run, including newlines, becomes one space. */
    public static String line(String value, int maxCharacters) {
        return normalize(value, maxCharacters, false).text();
    }

    /** Multi-line value: keeps newlines and tabs, collapses runs of blank lines. */
    public static String block(String value, int maxCharacters) {
        return normalize(value, maxCharacters, true).text();
    }

    public static Bounded normalize(String value, int maxCharacters, boolean multiline) {
        if (maxCharacters <= 0) {
            throw new IllegalArgumentException("maxCharacters must be positive");
        }
        if (value == null || value.isEmpty()) {
            return new Bounded("", false);
        }
        String composed = Normalizer.normalize(value, Normalizer.Form.NFC);
        StringBuilder out = new StringBuilder(Math.min(composed.length(), maxCharacters));
        boolean truncated = false;
        boolean pendingSpace = false;
        int pendingNewlines = 0;
        for (int offset = 0; offset < composed.length();) {
            int codePoint = composed.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == '\r') {
                if (offset < composed.length() && composed.charAt(offset) == '\n') {
                    continue;
                }
                codePoint = '\n';
            }
            if (codePoint == '\n' && multiline) {
                pendingNewlines++;
                pendingSpace = false;
                continue;
            }
            if (isDropped(codePoint)) {
                continue;
            }
            boolean whitespace = Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
                    || Character.isISOControl(codePoint);
            if (whitespace) {
                pendingSpace = true;
                continue;
            }
            String separator = "";
            if (!out.isEmpty()) {
                if (pendingNewlines > 0) {
                    separator = pendingNewlines > 1 ? "\n\n" : "\n";
                } else if (pendingSpace) {
                    separator = " ";
                }
            }
            pendingNewlines = 0;
            pendingSpace = false;
            if (out.length() + separator.length() + Character.charCount(codePoint) > maxCharacters) {
                truncated = true;
                break;
            }
            out.append(separator).appendCodePoint(codePoint);
        }
        return new Bounded(out.toString(), truncated);
    }

    private static boolean isDropped(int codePoint) {
        if (codePoint == '\t' || codePoint == '\n') {
            return false;
        }
        int type = Character.getType(codePoint);
        return type == Character.FORMAT
                || type == Character.SURROGATE
                || type == Character.PRIVATE_USE
                || type == Character.UNASSIGNED
                || (Character.isISOControl(codePoint) && !Character.isWhitespace(codePoint))
                || (codePoint & 0xFFFE) == 0xFFFE
                || (codePoint >= 0xFDD0 && codePoint <= 0xFDEF);
    }
}
