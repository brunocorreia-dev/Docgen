package com.docgen.launcher;

import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import javax.swing.SwingUtilities;

/** {@link LauncherUi} backed by the Swing {@link LauncherWindow}; every window call runs on the EDT. */
final class SwingLauncherUi implements LauncherUi {
    private LauncherWindow window;
    private LauncherViewState pending;

    /** Creates and shows the window; must be called on the event dispatch thread. */
    void attach(LauncherController controller) {
        window = new LauncherWindow(controller, controller::closeRequested);
        if (pending != null) {
            window.render(pending);
        }
        window.show();
    }

    @Override
    public void render(LauncherViewState state) {
        SwingUtilities.invokeLater(() -> {
            if (window == null) {
                pending = state;
            } else {
                window.render(state);
            }
        });
    }

    @Override
    public boolean confirmQuit() {
        return window == null || window.confirmQuit();
    }

    @Override
    public void showLogs(List<String> recentLines, Path logDirectory) {
        if (window != null) {
            window.showLogs(recentLines, logDirectory, this::copyToClipboard,
                    logDirectory == null ? null : () -> openDirectory(logDirectory));
        }
    }

    @Override
    public void showMessage(UserMessages.Notice notice) {
        SwingUtilities.invokeLater(() -> LauncherWindow.message(window == null ? null : window.frame(), notice));
    }

    @Override
    public void copyToClipboard(String text) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        } catch (IllegalStateException | SecurityException unavailable) {
            // The clipboard can be busy or unavailable; the text stays visible in the window.
        }
    }

    @Override
    public void close() {
        SwingUtilities.invokeLater(() -> {
            if (window != null) {
                window.dispose();
            }
        });
    }

    private static void openDirectory(Path directory) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(directory.toFile());
            }
        } catch (IOException | RuntimeException unsupported) {
            // The path is shown in the dialog, so it can still be opened by hand.
        }
    }
}
