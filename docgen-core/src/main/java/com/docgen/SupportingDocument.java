package com.docgen;

/**
 * Plain text extracted from a user attachment (for example a PDF). The text is
 * untrusted data: it is only ever placed inside a delimited data block of a
 * prompt and is never treated as instructions.
 */
public record SupportingDocument(String displayName, String text, boolean truncated) {
    public static final int MAX_DISPLAY_NAME = 120;

    public SupportingDocument {
        displayName = UntrustedText.line(displayName, MAX_DISPLAY_NAME);
        if (displayName.isEmpty()) {
            displayName = "document";
        }
        text = text == null ? "" : text;
    }
}
