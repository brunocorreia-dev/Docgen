package com.docgen.launcher;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.Set;

/** Colors and type of the launcher window, matching the web workspace (docgen-web app.css). */
final class LauncherTheme {
    static final Color BACKGROUND = new Color(0x060914);
    static final Color SURFACE = new Color(0x10162a);
    static final Color SURFACE_RAISED = new Color(0x151c34);
    static final Color BORDER = new Color(0x26304f);
    static final Color TEXT = new Color(0xe8ebf8);
    static final Color TEXT_MUTED = new Color(0x9aa4c7);
    static final Color TEXT_FAINT = new Color(0x6c7699);
    static final Color BLUE = new Color(0x4f7cff);
    static final Color PURPLE = new Color(0x8b5cf6);
    static final Color CYAN = new Color(0x22d3ee);
    static final Color GREEN = new Color(0x34d399);
    static final Color RED = new Color(0xf87171);
    static final Color AMBER = new Color(0xfbbf24);

    private static final List<String> PREFERRED_SANS = List.of("Inter", "Cantarell", "Noto Sans", "Segoe UI",
            "Ubuntu", "DejaVu Sans", "Helvetica Neue", "Arial");
    private static final List<String> PREFERRED_MONO = List.of("JetBrains Mono", "Fira Code", "Noto Sans Mono",
            "DejaVu Sans Mono", "Consolas", "Menlo");
    private static final String SANS = pick(PREFERRED_SANS, Font.SANS_SERIF);
    private static final String MONO = pick(PREFERRED_MONO, Font.MONOSPACED);

    private LauncherTheme() {
    }

    static Font font(int style, float size) {
        return new Font(SANS, style, Math.round(size));
    }

    static Font mono(int style, float size) {
        return new Font(MONO, style, Math.round(size));
    }

    private static String pick(List<String> preferred, String fallback) {
        try {
            Set<String> available = Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
            return preferred.stream().filter(available::contains).findFirst().orElse(fallback);
        } catch (RuntimeException | Error unavailable) {
            // Some minimal runtimes have no font configuration at all; logical fonts still work.
            return fallback;
        }
    }
}
