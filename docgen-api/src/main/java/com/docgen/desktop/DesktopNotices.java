package com.docgen.desktop;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * What the launcher tells the user when it cannot do its job silently. With a
 * display these are small native dialogs; without one (a headless session)
 * they go to standard error. Access links are never printed: they are tokens.
 */
interface DesktopNotices {
    /** No browser could be opened. {@code freshAddress} makes a new single-use link on demand. */
    void browserUnavailable(Supplier<String> freshAddress);

    void startupFailed(Path logDirectory);

    void alreadyRunningButUnresponsive();

    static DesktopNotices system() {
        return GraphicsEnvironment.isHeadless() ? new Console() : new Dialogs();
    }

    final class Console implements DesktopNotices {
        @Override
        public void browserUnavailable(Supplier<String> freshAddress) {
            System.err.println("DocGen: nenhum navegador pôde ser aberto. Abra o DocGen de novo numa sessão com "
                    + "interface gráfica e navegador.");
        }

        @Override
        public void startupFailed(Path logDirectory) {
            System.err.println("DocGen: não foi possível iniciar. Detalhes técnicos em " + logDirectory);
        }

        @Override
        public void alreadyRunningButUnresponsive() {
            System.err.println("DocGen: já está aberto, mas não respondeu. Tente de novo em instantes.");
        }
    }

    final class Dialogs implements DesktopNotices {
        private static final String TITLE = "DocGen";

        @Override
        public void browserUnavailable(Supplier<String> freshAddress) {
            SwingUtilities.invokeLater(() -> {
                Object[] options = {"Copiar link de acesso", "Fechar"};
                int choice = JOptionPane.showOptionDialog(null,
                        "O DocGen está rodando, mas nenhum navegador pôde ser aberto.\n"
                                + "Copie o link de acesso e cole-o na barra de endereços do seu navegador.\n"
                                + "O link vale por 30 segundos e funciona uma única vez.",
                        TITLE, JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);
                if (choice == 0) {
                    Toolkit.getDefaultToolkit().getSystemClipboard()
                            .setContents(new StringSelection(freshAddress.get()), null);
                }
            });
        }

        @Override
        public void startupFailed(Path logDirectory) {
            JOptionPane.showMessageDialog(null,
                    "Não foi possível iniciar o DocGen.\nDetalhes técnicos ficam em:\n" + logDirectory,
                    TITLE, JOptionPane.ERROR_MESSAGE);
        }

        @Override
        public void alreadyRunningButUnresponsive() {
            JOptionPane.showMessageDialog(null,
                    "O DocGen já está aberto, mas não respondeu.\nAguarde alguns instantes e tente de novo.",
                    TITLE, JOptionPane.WARNING_MESSAGE);
        }
    }
}
