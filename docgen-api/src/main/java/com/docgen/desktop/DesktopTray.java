package com.docgen.desktop;

import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.util.Optional;

/**
 * Optional system-tray icon with "Abrir DocGen" and "Sair do DocGen", so a
 * closed browser tab does not hide that the app is running. Only installed
 * where the desktop supports a tray (many GNOME setups do not); anywhere else
 * nothing happens and the app works the same.
 */
final class DesktopTray {
    private final SystemTray tray;
    private final TrayIcon icon;

    private DesktopTray(SystemTray tray, TrayIcon icon) {
        this.tray = tray;
        this.icon = icon;
    }

    static Optional<DesktopTray> install(Runnable openWindow, Runnable quit) {
        try {
            if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
                return Optional.empty();
            }
            PopupMenu menu = new PopupMenu();
            MenuItem open = new MenuItem("Abrir DocGen");
            open.addActionListener(event -> openWindow.run());
            MenuItem exit = new MenuItem("Sair do DocGen");
            exit.addActionListener(event -> quit.run());
            menu.add(open);
            menu.addSeparator();
            menu.add(exit);
            SystemTray tray = SystemTray.getSystemTray();
            TrayIcon icon = new TrayIcon(drawIcon(), "DocGen", menu);
            icon.setImageAutoSize(true);
            icon.addActionListener(event -> openWindow.run());
            tray.add(icon);
            return Optional.of(new DesktopTray(tray, icon));
        } catch (AWTException | RuntimeException | LinkageError unsupported) {
            return Optional.empty();
        }
    }

    void remove() {
        try {
            tray.remove(icon);
        } catch (RuntimeException alreadyGone) {
            // The tray is going away with the process.
        }
    }

    /** Drawn in code so the app ships no binary icon yet (the installers bring the real one). */
    private static BufferedImage drawIcon() {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(0x4F46E5));
            graphics.fillRoundRect(2, 2, 60, 60, 16, 16);
            graphics.setColor(new Color(0x22D3EE));
            graphics.setStroke(new BasicStroke(3));
            graphics.drawRoundRect(4, 4, 56, 56, 14, 14);
            graphics.setColor(Color.WHITE);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 38));
            graphics.drawString("D", 19, 46);
        } finally {
            graphics.dispose();
        }
        return image;
    }
}
