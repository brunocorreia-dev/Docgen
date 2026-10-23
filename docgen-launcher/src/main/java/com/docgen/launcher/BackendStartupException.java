package com.docgen.launcher;

/**
 * Why the backend could not be started or stopped running. The message is
 * technical and log-safe (no secrets, no process output); the window maps the
 * {@link Reason} to its own user-facing text.
 */
final class BackendStartupException extends Exception {
    enum Reason {
        /** The configured port already accepts connections; nothing was started. */
        PORT_IN_USE,
        /** Another DocGen process holds the database lock. */
        DATA_IN_USE,
        /** The backend could not read or write its files. */
        PERMISSION_DENIED,
        /** The Java runtime cannot run the backend. */
        INCOMPATIBLE_RUNTIME,
        /** The process could not be executed at all. */
        LAUNCH_FAILED,
        /** The process ended before becoming ready. */
        EXITED,
        /** The process did not become ready in time. */
        TIMEOUT,
        /** Something answered, but it could not prove it is the process this launcher started. */
        HANDSHAKE_FAILED,
        /** A ready backend ended without being asked to. */
        CRASHED,
        /** Startup was cancelled because the launcher is shutting down. */
        CANCELLED
    }

    private final Reason reason;
    private final Integer exitCode;

    BackendStartupException(Reason reason, String message) {
        this(reason, message, null, null);
    }

    BackendStartupException(Reason reason, String message, Integer exitCode, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.exitCode = exitCode;
    }

    Reason reason() {
        return reason;
    }

    /** The process exit code when the process ended, otherwise {@code null}. */
    Integer exitCode() {
        return exitCode;
    }
}
