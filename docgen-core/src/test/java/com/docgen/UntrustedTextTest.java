package com.docgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UntrustedTextTest {
    @Test
    void stripsControlAndBidirectionalFormatCharacters() {
        String hostile = "safe\u0000\u001b[31m text\u202Egnp.exe\u200B";

        String normalized = UntrustedText.line(hostile, 100);

        // Controls are dropped (not turned into spaces); the visible remainder stays inert text.
        assertEquals("safe[31m textgnp.exe", normalized);
    }

    @Test
    void lineModeCollapsesNewlinesWhileBlockModeKeepsParagraphs() {
        String value = "first\r\nsecond\n\n\n\nthird   word";

        assertEquals("first second third word", UntrustedText.line(value, 100));
        assertEquals("first\nsecond\n\nthird word", UntrustedText.block(value, 100));
    }

    @Test
    void boundsLengthOnACodePointBoundaryAndReportsTruncation() {
        UntrustedText.Bounded bounded = UntrustedText.normalize("ab😀cd", 3, false);

        assertEquals("ab", bounded.text());
        assertTrue(bounded.truncated());
        assertFalse(UntrustedText.normalize("abc", 3, false).truncated());
    }

    @Test
    void dropsUnpairedSurrogatesAndNoncharacters() {
        assertEquals("ab", UntrustedText.line("a\uD800b￿", 10));
    }
}
