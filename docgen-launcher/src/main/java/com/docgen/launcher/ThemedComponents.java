package com.docgen.launcher;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.text.View;

/** Small hand-painted Swing pieces, so the window looks the same on every platform look-and-feel. */
final class ThemedComponents {
    private ThemedComponents() {
    }

    static void antialias(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    /** A rounded surface with a hairline border. */
    static final class Card extends JPanel {
        private final Color fill;
        private final Color outline;

        Card(Color fill, Color outline) {
            this.fill = fill;
            this.outline = outline;
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                antialias(g);
                RoundRectangle2D shape = new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, 18, 18);
                g.setColor(fill);
                g.fill(shape);
                g.setColor(outline);
                g.draw(shape);
            } finally {
                g.dispose();
            }
            super.paintComponent(graphics);
        }
    }

    enum ButtonStyle {
        PRIMARY,
        SECONDARY,
        QUIET
    }

    /** Rounded button with a visible cyan focus ring for keyboard users. */
    static final class ThemedButton extends JButton {
        private final ButtonStyle style;

        ThemedButton(String text, ButtonStyle style) {
            super(text);
            this.style = style;
            setFont(LauncherTheme.font(Font.BOLD, 14));
            setForeground(style == ButtonStyle.PRIMARY ? Color.WHITE : LauncherTheme.TEXT);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setMargin(new Insets(10, 18, 10, 18));
            setBorder(BorderFactory.createEmptyBorder(10, 18, 10, 18));
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics metrics = getFontMetrics(getFont());
            int width = metrics.stringWidth(getText()) + 40;
            return new Dimension(Math.max(width, 96), metrics.getHeight() + 20);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                antialias(g);
                float width = getWidth();
                float height = getHeight();
                RoundRectangle2D shape = new RoundRectangle2D.Float(2, 2, width - 4, height - 4, 14, 14);
                boolean enabled = isEnabled();
                boolean hover = enabled && getModel().isRollover();
                switch (style) {
                    case PRIMARY -> {
                        Color start = enabled ? LauncherTheme.BLUE : LauncherTheme.SURFACE_RAISED;
                        Color end = enabled ? LauncherTheme.PURPLE : LauncherTheme.SURFACE_RAISED;
                        g.setPaint(new LinearGradientPaint(0, 0, width, 0, new float[]{0f, 1f},
                                new Color[]{hover ? start.brighter() : start, hover ? end.brighter() : end}));
                        g.fill(shape);
                    }
                    case SECONDARY -> {
                        g.setColor(hover ? LauncherTheme.BORDER : LauncherTheme.SURFACE_RAISED);
                        g.fill(shape);
                        g.setColor(LauncherTheme.BORDER);
                        g.draw(shape);
                    }
                    case QUIET -> {
                        if (hover) {
                            g.setColor(LauncherTheme.SURFACE_RAISED);
                            g.fill(shape);
                        }
                    }
                }
                if (isFocusOwner()) {
                    g.setColor(LauncherTheme.CYAN);
                    g.setStroke(new BasicStroke(2f));
                    g.draw(new RoundRectangle2D.Float(1, 1, width - 2, height - 2, 16, 16));
                }
                g.setFont(getFont());
                g.setColor(enabled ? getForeground() : LauncherTheme.TEXT_FAINT);
                FontMetrics metrics = g.getFontMetrics();
                String text = getText();
                int x = (getWidth() - metrics.stringWidth(text)) / 2;
                int y = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
                g.drawString(text, x, y);
                int mnemonic = getDisplayedMnemonicIndex();
                if (enabled && mnemonic >= 0 && mnemonic < text.length()) {
                    int start = x + metrics.stringWidth(text.substring(0, mnemonic));
                    int underline = metrics.charWidth(text.charAt(mnemonic));
                    g.fillRect(start, y + 2, underline, 1);
                }
            } finally {
                g.dispose();
            }
        }
    }

    /** The status mark of one startup step. */
    static final class StepIcon implements Icon {
        private static final int SIZE = 18;
        private final LauncherViewState.StepStatus status;

        StepIcon(LauncherViewState.StepStatus status) {
            this.status = status;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                antialias(g);
                g.translate(x, y);
                Ellipse2D circle = new Ellipse2D.Float(1, 1, SIZE - 2, SIZE - 2);
                switch (status) {
                    case PENDING -> {
                        g.setColor(LauncherTheme.BORDER);
                        g.setStroke(new BasicStroke(1.6f));
                        g.draw(circle);
                    }
                    case ACTIVE -> {
                        g.setColor(LauncherTheme.CYAN);
                        g.setStroke(new BasicStroke(2f));
                        g.draw(circle);
                        g.fill(new Ellipse2D.Float(5.5f, 5.5f, SIZE - 11, SIZE - 11));
                    }
                    case DONE -> {
                        g.setColor(LauncherTheme.GREEN);
                        g.fill(circle);
                        g.setColor(LauncherTheme.BACKGROUND);
                        g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        Path2D check = new Path2D.Float();
                        check.moveTo(5, 9.5);
                        check.lineTo(8, 12.5);
                        check.lineTo(13.5, 6.5);
                        g.draw(check);
                    }
                    case FAILED -> {
                        g.setColor(LauncherTheme.RED);
                        g.fill(circle);
                        g.setColor(LauncherTheme.BACKGROUND);
                        g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g.drawLine(6, 6, 12, 12);
                        g.drawLine(12, 6, 6, 12);
                    }
                }
            } finally {
                g.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }
    }

    /**
     * Read-only text that wraps to its container's width. A plain JTextArea
     * reports its unwrapped single-line height to BoxLayout before it has a
     * width, which cut messages off; this one measures itself at the width
     * it will actually get (plain text only, never HTML).
     */
    static final class WrappingText extends JTextArea {
        private static final int FALLBACK_WIDTH = 460;

        @Override
        public Dimension getPreferredSize() {
            Container parent = getParent();
            int available = parent == null ? 0
                    : parent.getWidth() - parent.getInsets().left - parent.getInsets().right;
            int width = available > 0 ? available : FALLBACK_WIDTH;
            View root = getUI().getRootView(this);
            root.setSize(width, Float.MAX_VALUE);
            Insets insets = getInsets();
            int height = (int) Math.ceil(root.getPreferredSpan(View.Y_AXIS)) + insets.top + insets.bottom;
            return new Dimension(width, height);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    /** Wrapping, read-only text that behaves like a multi-line label. */
    static JTextArea paragraph(Color color, float size) {
        JTextArea text = new WrappingText();
        text.setEditable(false);
        text.setFocusable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setOpaque(false);
        text.setBorder(BorderFactory.createEmptyBorder());
        text.setForeground(color);
        text.setFont(LauncherTheme.font(Font.PLAIN, size));
        return text;
    }
}
