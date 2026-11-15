package com.docgen.desktop;

/** A line or value that does not satisfy the protocol. Messages never echo the offending input. */
public final class DesktopProtocolException extends Exception {
    public DesktopProtocolException(String message) {
        super(message);
    }
}
