package com.docgen.launcher;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Strict parser for the one JSON shape the launcher reads: a flat object of
 * string values. Duplicate keys, other value types, trailing data and
 * malformed escapes are rejected. The launcher has no JSON dependency, and
 * this keeps the attack surface of the readiness response minimal.
 */
final class FlatJson {
    static final int MAX_LENGTH = 4096;

    private final String text;
    private int position;

    private FlatJson(String text) {
        this.text = text;
    }

    static Map<String, String> parseObject(String text) {
        if (text == null || text.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("invalid JSON");
        }
        FlatJson parser = new FlatJson(text);
        Map<String, String> result = parser.object();
        parser.whitespace();
        if (parser.position != text.length()) {
            throw new IllegalArgumentException("invalid JSON");
        }
        return result;
    }

    private Map<String, String> object() {
        whitespace();
        expect('{');
        Map<String, String> values = new LinkedHashMap<>();
        whitespace();
        if (peek() == '}') {
            position++;
            return values;
        }
        while (true) {
            whitespace();
            String key = string();
            whitespace();
            expect(':');
            whitespace();
            String value = string();
            if (values.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("invalid JSON");
            }
            whitespace();
            char next = next();
            if (next == '}') {
                return values;
            }
            if (next != ',') {
                throw new IllegalArgumentException("invalid JSON");
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder value = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return value.toString();
            }
            if (c < 0x20) {
                throw new IllegalArgumentException("invalid JSON");
            }
            if (c != '\\') {
                value.append(c);
                continue;
            }
            char escape = next();
            switch (escape) {
                case '"', '\\', '/' -> value.append(escape);
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'u' -> {
                    if (position + 4 > text.length()) {
                        throw new IllegalArgumentException("invalid JSON");
                    }
                    String hex = text.substring(position, position + 4);
                    if (!hex.chars().allMatch(h -> Character.digit(h, 16) >= 0)) {
                        throw new IllegalArgumentException("invalid JSON");
                    }
                    value.append((char) Integer.parseInt(hex, 16));
                    position += 4;
                }
                default -> throw new IllegalArgumentException("invalid JSON");
            }
        }
    }

    private void whitespace() {
        while (position < text.length() && " \t\n\r".indexOf(text.charAt(position)) >= 0) {
            position++;
        }
    }

    private char peek() {
        if (position >= text.length()) {
            throw new IllegalArgumentException("invalid JSON");
        }
        return text.charAt(position);
    }

    private char next() {
        char c = peek();
        position++;
        return c;
    }

    private void expect(char expected) {
        if (next() != expected) {
            throw new IllegalArgumentException("invalid JSON");
        }
    }
}
