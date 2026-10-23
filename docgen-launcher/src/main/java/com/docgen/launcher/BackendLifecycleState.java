package com.docgen.launcher;

/** Lifecycle of the backend process owned by one launcher. */
enum BackendLifecycleState {
    /** No process is running (initial state, or after a clean stop). */
    STOPPED,
    /** The process was started and its identity is not yet verified. */
    STARTING,
    /** The process announced its port and proved possession of the session secret. */
    READY,
    /** The launcher is shutting its process down. */
    STOPPING,
    /** Startup failed or the process ended unexpectedly; it is no longer running. */
    FAILED
}
