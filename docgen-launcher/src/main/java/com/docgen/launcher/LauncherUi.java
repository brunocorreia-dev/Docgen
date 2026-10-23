package com.docgen.launcher;

import java.nio.file.Path;
import java.util.List;

/**
 * What the controller needs from a user interface. The Swing window
 * implements it; tests use a recording implementation, so the whole startup
 * and shutdown flow is verified without a display.
 */
interface LauncherUi {
    /** May be called from any thread; implementations hop to their UI thread. */
    void render(LauncherViewState state);

    /** Called on the UI thread when the window is closed while the backend runs. */
    boolean confirmQuit();

    void showLogs(List<String> recentLines, Path logDirectory);

    void showMessage(UserMessages.Notice notice);

    void copyToClipboard(String text);

    /** Releases the window; called once, right before the launcher exits. */
    void close();
}
