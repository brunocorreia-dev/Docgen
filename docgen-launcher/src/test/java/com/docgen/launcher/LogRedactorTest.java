package com.docgen.launcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogRedactorTest {
    @Test
    void masksRegisteredSecretsWhereverTheyAppear() {
        LogRedactor redactor = new LogRedactor();
        redactor.addSecret("s3cr3t-session-value-0123456789");
        String line = redactor.redact("got s3cr3t-session-value-0123456789 twice: xs3cr3t-session-value-0123456789x");
        assertFalse(line.contains("s3cr3t-session-value"));
        assertEquals(2, line.split("\\[REDACTED]", -1).length - 1);
    }

    @Test
    void ignoresSecretsTooShortToBeMeaningful() {
        LogRedactor redactor = new LogRedactor();
        redactor.addSecret("abc");
        redactor.addSecret(null);
        assertEquals("abc", redactor.redact("abc"));
    }

    @Test
    void masksCommonCredentialShapes() {
        LogRedactor redactor = new LogRedactor();
        // Over-masking is acceptable; the token itself must never survive.
        String header = redactor.redact("Authorization: Bearer abcDEF123456789_-.~");
        assertFalse(header.contains("abcDEF123456789"));
        assertTrue(header.startsWith("Authorization: [REDACTED]"));
        assertEquals("Bearer [REDACTED]", redactor.redact("Bearer abcDEF123456789_-.~"));
        assertEquals("Basic [REDACTED]", redactor.redact("Basic dXNlcjpwYXNzd29yZA=="));
        assertEquals("{\"accessToken\":\"[REDACTED]\"}", redactor.redact("{\"accessToken\":\"tok-123456789\"}"));
        assertEquals("password=[REDACTED] next", redactor.redact("password=hunter2-long next"));
        assertEquals("\"pollSecret\": \"[REDACTED]\"", redactor.redact("\"pollSecret\": \"zzzzzzzzzzzz\""));
    }

    @Test
    void stripsTerminalControlCharacters() {
        String line = new LogRedactor().redact("\u001b[31mred\u001b[0m\tnext\r\u0007");
        assertEquals("[31mred[0m\tnext", line);
        assertTrue(new LogRedactor().redact(null).isEmpty());
    }
}
