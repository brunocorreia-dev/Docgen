package com.docgen;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeTextTest {
    @Test
    void stripsTerminalControlsAndRedactsSecrets() {
        String token = "gsk_abcdefghijklmnopqrstuvwxyz123456";
        String safe = SafeText.forTerminal("failure\n\u001b[31m Authorization: Bearer " + token + "\u202E");

        assertFalse(safe.contains("\n"));
        assertFalse(safe.contains("\u001b"));
        assertFalse(safe.contains("\u202E"));
        assertFalse(safe.contains(token));
        assertTrue(safe.contains("[REDACTED_SECRET]"));
    }

    @Test
    void boundsUntrustedInputBeforeRedaction() {
        String safe = SafeText.forTerminal("[".repeat(1_000_000));

        assertTrue(safe.length() <= 303);
        assertTrue(safe.endsWith("..."));
    }

    @Test
    void cliSurfacesSanitizedRollbackStatusWithoutLeakingThePrimaryMessage() {
        String token = "gsk_abcdefghijklmnopqrstuvwxyz123456";
        IOException failure = new IOException("provider echoed " + token);
        failure.addSuppressed(new IOException(
                "Documentation rollback was incomplete; recovery files may remain"));

        String safe = DocGenCLI.safeFailureMessage(failure);

        assertFalse(safe.contains(token));
        assertTrue(safe.contains("rollback was incomplete"));
    }
}
