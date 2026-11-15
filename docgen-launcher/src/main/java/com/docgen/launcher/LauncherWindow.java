package com.docgen.launcher;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * The native window: a {@link LauncherPanel} in a frame, plus small themed
 * dialogs. All methods must be called on the event dispatch thread.
 * Closing the window is routed to the controller, which decides whether to
 * ask for confirmation and always stops the backend before exiting.
 */
final class LauncherWindow {
    private final JFrame frame = new JFrame("DocGen");
    private final LauncherPanel panel;

    LauncherWindow(LauncherPanel.Actions actions, Runnable closeRequested) {
        panel = new LauncherPanel(actions);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                closeRequested.run();
            }
        });
        frame.setIconImages(BrandIcon.windowIcons());
        frame.setContentPane(panel);
        frame.getRootPane().setDefaultButton(panel.defaultButton());
        frame.setMinimumSize(new Dimension(520, 600));
        frame.pack();
        frame.setSize(new Dimension(580, Math.max(frame.getHeight(), 620)));
        frame.setLocationRelativeTo(null);
    }

    void show() {
        frame.setVisible(true);
        panel.defaultButton().requestFocusInWindow();
    }

    void render(LauncherViewState state) {
        boolean newPairing = state.pairing() != null
                && (panel.state() == null || panel.state().pairing() == null
                || !panel.state().pairing().pairingId().equals(state.pairing().pairingId()));
        panel.render(state);
        frame.getRootPane().setDefaultButton(panel.defaultButton());
        // Grow (never shrink) when content appears, such as the pairing request.
        int needed = frame.getPreferredSize().height;
        if (frame.getHeight() < needed) {
            frame.setSize(frame.getWidth(), needed);
        }
        if (newPairing) {
            // Bring the request to the person's attention without pre-selecting "Autorizar".
            frame.toFront();
            panel.denyButton().requestFocusInWindow();
        }
    }

    void dispose() {
        frame.dispose();
    }

    Window frame() {
        return frame;
    }

    /** Asks before closing while the backend runs; returns true to quit. */
    boolean confirmQuit() {
        AtomicBoolean confirmed = new AtomicBoolean();
        JDialog dialog = dialog("Encerrar o DocGen?");
        JTextArea text = ThemedComponents.paragraph(LauncherTheme.TEXT_MUTED, 14);
        text.setText("O backend local será encerrado e o workspace aberto no navegador deixará de funcionar. "
                + "Seus projetos continuam salvos.");
        ThemedComponents.ThemedButton quit = new ThemedComponents.ThemedButton("Encerrar", ThemedComponents.ButtonStyle.PRIMARY);
        ThemedComponents.ThemedButton cancel = new ThemedComponents.ThemedButton("Cancelar", ThemedComponents.ButtonStyle.SECONDARY);
        quit.setMnemonic(KeyEvent.VK_E);
        cancel.setMnemonic(KeyEvent.VK_C);
        quit.addActionListener(event -> {
            confirmed.set(true);
            dialog.dispose();
        });
        cancel.addActionListener(event -> dialog.dispose());
        show(dialog, text, List.of(cancel, quit), cancel, new Dimension(440, 220));
        return confirmed.get();
    }

    /** Shows a message the person must acknowledge (used before the main window exists, too). */
    static void message(Window owner, UserMessages.Notice notice) {
        JDialog dialog = new JDialog(owner, notice.title(), JDialog.DEFAULT_MODALITY_TYPE);
        dialog.setIconImages(BrandIcon.windowIcons());
        JTextArea text = ThemedComponents.paragraph(LauncherTheme.TEXT_MUTED, 14);
        text.setText(notice.describe());
        ThemedComponents.ThemedButton ok = new ThemedComponents.ThemedButton("Fechar", ThemedComponents.ButtonStyle.PRIMARY);
        ok.setMnemonic(KeyEvent.VK_F);
        ok.addActionListener(event -> dialog.dispose());
        show(dialog, text, List.of(ok), ok, new Dimension(460, 220));
    }

    /** Recent backend output (already redacted) and where the full logs are. */
    void showLogs(List<String> recentLines, Path logDirectory, Consumer<String> copy, Runnable openDirectory) {
        JDialog dialog = dialog("Logs do DocGen");
        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setOpaque(false);
        JLabel where = new JLabel(logDirectory == null ? "Os logs desta sessão estão apenas em memória."
                : "Arquivos de log: " + logDirectory);
        where.setForeground(LauncherTheme.TEXT_MUTED);
        where.setFont(LauncherTheme.font(Font.PLAIN, 13));
        JTextArea lines = new JTextArea(String.join("\n", recentLines));
        lines.setEditable(false);
        lines.setFont(LauncherTheme.mono(Font.PLAIN, 12));
        lines.setForeground(LauncherTheme.TEXT);
        lines.setBackground(LauncherTheme.SURFACE);
        lines.setCaretColor(LauncherTheme.CYAN);
        lines.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        lines.getAccessibleContext().setAccessibleName("Últimas linhas do log do backend");
        lines.setCaretPosition(lines.getDocument().getLength());
        JScrollPane scroll = new JScrollPane(lines);
        scroll.setBorder(BorderFactory.createLineBorder(LauncherTheme.BORDER));
        scroll.getViewport().setBackground(LauncherTheme.SURFACE);
        body.add(where, BorderLayout.NORTH);
        body.add(scroll, BorderLayout.CENTER);
        ThemedComponents.ThemedButton copyButton = new ThemedComponents.ThemedButton("Copiar", ThemedComponents.ButtonStyle.SECONDARY);
        copyButton.setMnemonic(KeyEvent.VK_C);
        copyButton.addActionListener(event -> copy.accept(lines.getText()));
        ThemedComponents.ThemedButton folder = new ThemedComponents.ThemedButton("Abrir pasta de logs", ThemedComponents.ButtonStyle.SECONDARY);
        folder.setMnemonic(KeyEvent.VK_P);
        folder.setEnabled(logDirectory != null && openDirectory != null);
        folder.addActionListener(event -> openDirectory.run());
        ThemedComponents.ThemedButton close = new ThemedComponents.ThemedButton("Fechar", ThemedComponents.ButtonStyle.PRIMARY);
        close.setMnemonic(KeyEvent.VK_F);
        close.addActionListener(event -> dialog.dispose());
        show(dialog, body, List.of(copyButton, folder, close), close, new Dimension(760, 480));
    }

    private JDialog dialog(String title) {
        JDialog dialog = new JDialog(frame, title, JDialog.DEFAULT_MODALITY_TYPE);
        dialog.setIconImages(BrandIcon.windowIcons());
        return dialog;
    }

    private static void show(JDialog dialog, JComponent body, List<ThemedComponents.ThemedButton> buttons,
                             ThemedComponents.ThemedButton focus, Dimension size) {
        JPanel content = new JPanel(new BorderLayout(0, 16));
        content.setBackground(LauncherTheme.BACKGROUND);
        content.setBorder(BorderFactory.createEmptyBorder(20, 22, 16, 22));
        JLabel title = new JLabel(dialog.getTitle());
        title.setForeground(LauncherTheme.TEXT);
        title.setFont(LauncherTheme.font(Font.BOLD, 17));
        content.add(title, BorderLayout.NORTH);
        content.add(body, BorderLayout.CENTER);
        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        row.setOpaque(false);
        for (int index = 0; index < buttons.size(); index++) {
            if (index > 0) {
                row.add(Box.createHorizontalStrut(10));
            }
            row.add(buttons.get(index));
        }
        content.add(row, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(focus);
        dialog.getRootPane().registerKeyboardAction(event -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setSize(size);
        dialog.setLocationRelativeTo(dialog.getOwner());
        SwingUtilities.invokeLater(focus::requestFocusInWindow);
        dialog.setVisible(true);
    }
}
