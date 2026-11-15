package com.docgen;

import java.io.IOException;

/** Model output was received but rejected by an output validator. */
public final class UnsafeOutputException extends IOException {
    public UnsafeOutputException(String message, Throwable cause) {
        super(message, cause);
    }
}
