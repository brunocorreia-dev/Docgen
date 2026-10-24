package com.docgen.api;

/**
 * What the API may ask of the desktop launcher that started it (ADR 0001).
 * The launcher registers its implementation as a bean before the context
 * starts; without the launcher (tests, development) there is none, and the
 * desktop routes answer that the app is not running as a desktop app.
 */
public interface DesktopControl {
    /** The route a second start of the app calls to ask for a new window. */
    String REOPEN_PATH = "/api/v1/desktop/reopen";
    /** The header that carries the reopen secret from the private instance file. */
    String INSTANCE_SECRET_HEADER = "X-DocGen-Instance-Secret";

    /**
     * Whether {@code candidate} is this instance's reopen secret. Only a second
     * start of the app can know it: it reads the private instance file.
     * Implementations compare in constant time.
     */
    boolean acceptsInstanceSecret(String candidate);

    /** Opens a new window (a new launch token) without blocking the caller. */
    void openWindow();

    /** Stops the server and the app without blocking the caller. */
    void requestShutdown(String reason);
}
