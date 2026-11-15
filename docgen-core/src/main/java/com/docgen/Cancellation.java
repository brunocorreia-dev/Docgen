package com.docgen;

import java.util.ArrayList;
import java.util.List;

/**
 * Lets another thread stop a long Ollama call (a download or a chat reply).
 * Cancelling closes the HTTP connection, which is what makes Ollama stop:
 * verified with Ollama 0.32.11 for {@code /api/pull}, where a closed
 * connection stopped the download and the next pull resumed it.
 */
public final class Cancellation {
    private final List<Runnable> actions = new ArrayList<>();
    private boolean cancelled;

    public void cancel() {
        List<Runnable> pending;
        synchronized (this) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            pending = List.copyOf(actions);
            actions.clear();
        }
        pending.forEach(Runnable::run);
    }

    public synchronized boolean isCancelled() {
        return cancelled;
    }

    /** Runs {@code action} on cancellation, or right away if already cancelled; returns how to unregister it. */
    Runnable onCancel(Runnable action) {
        synchronized (this) {
            if (!cancelled) {
                actions.add(action);
                return () -> {
                    synchronized (this) {
                        actions.remove(action);
                    }
                };
            }
        }
        action.run();
        return () -> {
        };
    }
}
