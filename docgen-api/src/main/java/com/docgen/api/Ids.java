package com.docgen.api;

import java.util.UUID;
import java.util.regex.Pattern;

/** Opaque identifiers: random UUIDs, validated before they reach a query. */
final class Ids {
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private Ids() {
    }

    static String random() {
        return UUID.randomUUID().toString();
    }

    /** Malformed identifiers are indistinguishable from unknown ones. */
    static String require(String value) {
        if (value == null || !UUID_TEXT.matcher(value).matches()) {
            throw new ResourceNotFoundException();
        }
        return value;
    }
}
