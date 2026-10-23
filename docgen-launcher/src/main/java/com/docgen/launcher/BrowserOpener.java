package com.docgen.launcher;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Opens the workspace in the default browser. Only loopback workspace
 * addresses built by the launcher are accepted (no user info, no query, no
 * credentials: the fragment is just the route). The fallback commands are
 * fixed executables with the address as a separate argument; no shell runs.
 */
interface BrowserOpener {
    Pattern WORKSPACE = Pattern.compile("http://127\\.0\\.0\\.1:[0-9]{1,5}/#/desktop");

    /** @return true when the request was handed to a browser */
    boolean open(URI address);

    static boolean acceptable(URI address) {
        return address != null && WORKSPACE.matcher(address.toString()).matches();
    }

    static BrowserOpener system(PlatformDetector.Platform platform, Consumer<String> log) {
        return address -> {
            if (!acceptable(address)) {
                log.accept("refused to open an unexpected address");
                return false;
            }
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(address);
                    return true;
                }
            } catch (IOException | RuntimeException unsupported) {
                log.accept("the desktop browser integration failed (" + unsupported.getClass().getSimpleName()
                        + "); trying the platform command");
            }
            List<String> command = switch (platform.os()) {
                case WINDOWS -> List.of("rundll32", "url.dll,FileProtocolHandler", address.toString());
                case MACOS -> List.of("open", address.toString());
                default -> List.of("xdg-open", address.toString());
            };
            try {
                Process opener = new ProcessBuilder(command)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                opener.getOutputStream().close();
                // xdg-open and open return quickly; a non-zero status means no browser was found.
                if (opener.waitFor(10, TimeUnit.SECONDS)) {
                    return opener.exitValue() == 0;
                }
                return true;
            } catch (IOException unavailable) {
                log.accept("no browser command is available (" + command.getFirst() + ")");
                return false;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        };
    }
}
