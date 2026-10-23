package com.docgen.launcher;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.KeyEvent;
import java.util.EnumMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;

/**
 * Content of the launcher window. It only renders {@link LauncherViewState}
 * values and forwards button presses to {@link Actions}; it never touches the
 * backend itself, and it can be built and painted headlessly in tests.
 * Every action has a keyboard mnemonic (Alt + underlined letter).
 */
final class LauncherPanel extends JPanel {
    interface Actions {
        void openWorkspace();

        void showLogs();

        void quit();

        void retry();

        void approvePairing(String pairingId);

        void denyPairing(String pairingId);

        void copyAddress(String address);
    }

    private final Map<LauncherViewState.Step, JLabel> stepLabels = new EnumMap<>(LauncherViewState.Step.class);
    private final JLabel headline = new JLabel();
    private final JTextArea detail = ThemedComponents.paragraph(LauncherTheme.TEXT_MUTED, 14);
    private final JLabel address = new JLabel();
    private final ThemedComponents.ThemedButton copyAddress =
            new ThemedComponents.ThemedButton("Copiar endereço", ThemedComponents.ButtonStyle.QUIET);
    private final ThemedComponents.ThemedButton open =
            new ThemedComponents.ThemedButton("Abrir DocGen", ThemedComponents.ButtonStyle.PRIMARY);
    private final ThemedComponents.ThemedButton retry =
            new ThemedComponents.ThemedButton("Tentar novamente", ThemedComponents.ButtonStyle.PRIMARY);
    private final ThemedComponents.ThemedButton logs =
            new ThemedComponents.ThemedButton("Ver logs", ThemedComponents.ButtonStyle.SECONDARY);
    private final ThemedComponents.ThemedButton quit =
            new ThemedComponents.ThemedButton("Encerrar", ThemedComponents.ButtonStyle.SECONDARY);
    private final ThemedComponents.Card pairingCard =
            new ThemedComponents.Card(new Color(0x18183a), LauncherTheme.PURPLE);
    private final JLabel pairingCode = new JLabel();
    private final ThemedComponents.ThemedButton approve =
            new ThemedComponents.ThemedButton("Autorizar este navegador", ThemedComponents.ButtonStyle.PRIMARY);
    private final ThemedComponents.ThemedButton deny =
            new ThemedComponents.ThemedButton("Recusar", ThemedComponents.ButtonStyle.SECONDARY);

    private LauncherViewState state;

    LauncherPanel(Actions actions) {
        super(new BorderLayout());
        setBackground(LauncherTheme.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(24, 26, 18, 26));

        JPanel stack = new JPanel(new GridBagLayout());
        stack.setOpaque(false);
        GridBagConstraints at = new GridBagConstraints();
        at.gridx = 0;
        at.fill = GridBagConstraints.HORIZONTAL;
        at.weightx = 1;
        at.insets = new Insets(0, 0, 18, 0);
        stack.add(header(), at);
        stack.add(statusCard(), at);
        stack.add(pairingCard(), at);
        at.insets = new Insets(0, 0, 0, 0);
        stack.add(buttons(), at);
        at.weighty = 1;
        stack.add(Box.createVerticalGlue(), at);
        add(stack, BorderLayout.CENTER);

        JLabel motto = new JLabel("Sua IA. Seu computador. Suas regras.");
        motto.setForeground(LauncherTheme.TEXT_FAINT);
        motto.setFont(LauncherTheme.font(Font.PLAIN, 12));
        motto.setBorder(BorderFactory.createEmptyBorder(14, 0, 0, 0));
        add(motto, BorderLayout.SOUTH);

        mnemonic(open, KeyEvent.VK_A, "Abre o workspace do DocGen no navegador padrão.");
        mnemonic(retry, KeyEvent.VK_T, "Inicia o backend novamente.");
        mnemonic(logs, KeyEvent.VK_L, "Mostra os logs desta sessão.");
        mnemonic(quit, KeyEvent.VK_E, "Encerra o backend e fecha o DocGen.");
        mnemonic(approve, KeyEvent.VK_U, "Concede a sessão ao navegador que mostra este código.");
        mnemonic(deny, KeyEvent.VK_R, "Recusa o pedido de acesso.");
        mnemonic(copyAddress, KeyEvent.VK_C, "Copia o endereço do workspace (sem senha).");
        open.addActionListener(event -> actions.openWorkspace());
        retry.addActionListener(event -> actions.retry());
        logs.addActionListener(event -> actions.showLogs());
        quit.addActionListener(event -> actions.quit());
        copyAddress.addActionListener(event -> {
            if (state != null && state.address() != null) {
                actions.copyAddress(state.address());
            }
        });
        approve.addActionListener(event -> {
            if (state != null && state.pairing() != null) {
                actions.approvePairing(state.pairing().pairingId());
            }
        });
        deny.addActionListener(event -> {
            if (state != null && state.pairing() != null) {
                actions.denyPairing(state.pairing().pairingId());
            }
        });
        render(LauncherViewState.initial());
    }

    private static void mnemonic(JButton button, int key, String description) {
        button.setMnemonic(key);
        button.setToolTipText(description);
        button.getAccessibleContext().setAccessibleDescription(description);
    }

    private JPanel header() {
        JPanel header = new JPanel(new BorderLayout(14, 0));
        header.setOpaque(false);
        JLabel icon = new JLabel(new ImageIcon(BrandIcon.render(56)));
        icon.getAccessibleContext().setAccessibleName("Logotipo do DocGen");
        header.add(icon, BorderLayout.WEST);
        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("DocGen");
        title.setForeground(LauncherTheme.TEXT);
        title.setFont(LauncherTheme.font(Font.BOLD, 28));
        JLabel subtitle = new JLabel("Seu assistente local e gratuito.");
        subtitle.setForeground(LauncherTheme.TEXT_MUTED);
        subtitle.setFont(LauncherTheme.font(Font.PLAIN, 15));
        titles.add(title);
        titles.add(subtitle);
        header.add(titles, BorderLayout.CENTER);
        return header;
    }

    private JPanel statusCard() {
        ThemedComponents.Card card = new ThemedComponents.Card(LauncherTheme.SURFACE, LauncherTheme.BORDER);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        JLabel section = new JLabel("ESTADO DO SISTEMA");
        section.setForeground(LauncherTheme.TEXT_FAINT);
        section.setFont(LauncherTheme.font(Font.BOLD, 11));
        section.setAlignmentX(LEFT_ALIGNMENT);
        card.add(section);
        card.add(Box.createVerticalStrut(10));
        for (LauncherViewState.Step step : LauncherViewState.Step.values()) {
            JLabel label = new JLabel(step.label());
            label.setFont(LauncherTheme.font(Font.PLAIN, 14));
            label.setIconTextGap(10);
            label.setAlignmentX(LEFT_ALIGNMENT);
            label.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
            stepLabels.put(step, label);
            card.add(label);
        }
        card.add(Box.createVerticalStrut(14));
        headline.setFont(LauncherTheme.font(Font.BOLD, 17));
        headline.setAlignmentX(LEFT_ALIGNMENT);
        card.add(headline);
        card.add(Box.createVerticalStrut(4));
        detail.setAlignmentX(LEFT_ALIGNMENT);
        card.add(detail);
        JPanel addressRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        addressRow.setOpaque(false);
        addressRow.setAlignmentX(LEFT_ALIGNMENT);
        address.setForeground(LauncherTheme.CYAN);
        address.setFont(LauncherTheme.mono(Font.PLAIN, 13));
        addressRow.add(address);
        addressRow.add(Box.createHorizontalStrut(8));
        addressRow.add(copyAddress);
        card.add(Box.createVerticalStrut(6));
        card.add(addressRow);
        return card;
    }

    private JPanel pairingCard() {
        pairingCard.setLayout(new BoxLayout(pairingCard, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("Pedido de acesso do navegador");
        title.setForeground(LauncherTheme.TEXT);
        title.setFont(LauncherTheme.font(Font.BOLD, 15));
        title.setAlignmentX(LEFT_ALIGNMENT);
        JTextArea explanation = ThemedComponents.paragraph(LauncherTheme.TEXT_MUTED, 13);
        explanation.setText("Um navegador pediu acesso ao DocGen. Só autorize se ele estiver mostrando exatamente este código:");
        explanation.setAlignmentX(LEFT_ALIGNMENT);
        pairingCode.setForeground(LauncherTheme.TEXT);
        pairingCode.setFont(LauncherTheme.mono(Font.BOLD, 30));
        pairingCode.setAlignmentX(LEFT_ALIGNMENT);
        pairingCode.setBorder(BorderFactory.createEmptyBorder(8, 0, 10, 0));
        JPanel decision = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        decision.setOpaque(false);
        decision.setAlignmentX(LEFT_ALIGNMENT);
        decision.add(approve);
        decision.add(Box.createHorizontalStrut(10));
        decision.add(deny);
        pairingCard.add(title);
        pairingCard.add(Box.createVerticalStrut(6));
        pairingCard.add(explanation);
        pairingCard.add(pairingCode);
        pairingCard.add(decision);
        return pairingCard;
    }

    private JPanel buttons() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        row.add(open);
        row.add(retry);
        row.add(Box.createHorizontalStrut(10));
        row.add(logs);
        row.add(Box.createHorizontalStrut(10));
        row.add(quit);
        return row;
    }

    /** Applies a state; must run on the event dispatch thread. */
    void render(LauncherViewState next) {
        this.state = next;
        for (Map.Entry<LauncherViewState.Step, JLabel> entry : stepLabels.entrySet()) {
            LauncherViewState.StepStatus status = next.steps().get(entry.getKey());
            JLabel label = entry.getValue();
            label.setIcon(new ThemedComponents.StepIcon(status));
            label.setForeground(status == LauncherViewState.StepStatus.PENDING ? LauncherTheme.TEXT_FAINT : LauncherTheme.TEXT);
            label.getAccessibleContext().setAccessibleName(entry.getKey().label() + ": " + status.label());
        }
        headline.setText(next.headline());
        headline.setForeground(switch (next.tone()) {
            case PROGRESS -> LauncherTheme.TEXT;
            case SUCCESS -> LauncherTheme.GREEN;
            case WARNING -> LauncherTheme.AMBER;
            case ERROR -> LauncherTheme.RED;
        });
        headline.getAccessibleContext().setAccessibleName(next.headline());
        detail.setText(next.detail());
        detail.getAccessibleContext().setAccessibleName(next.detail());
        address.setText(next.address() == null ? "" : next.address());
        address.setVisible(next.address() != null);
        copyAddress.setVisible(next.address() != null);
        open.setVisible(!next.canRetry());
        open.setEnabled(next.canOpen());
        retry.setVisible(next.canRetry());
        quit.setEnabled(next.canQuit());
        pairingCard.setVisible(next.pairing() != null);
        pairingCode.setText(next.pairing() == null ? "" : next.pairing().code());
        pairingCode.getAccessibleContext().setAccessibleName(next.pairing() == null ? ""
                : "Código de confirmação " + String.join(" ", next.pairing().code().split("")));
        revalidate();
        repaint();
    }

    LauncherViewState state() {
        return state;
    }

    /** The button Enter activates: the main action of the current state. */
    JButton defaultButton() {
        return state != null && state.canRetry() ? retry : open;
    }

    JLabel stepLabel(LauncherViewState.Step step) {
        return stepLabels.get(step);
    }

    JButton openButton() {
        return open;
    }

    JButton retryButton() {
        return retry;
    }

    JButton quitButton() {
        return quit;
    }

    JButton approveButton() {
        return approve;
    }

    JButton denyButton() {
        return deny;
    }

    JPanel pairingPanel() {
        return pairingCard;
    }

    JLabel headlineLabel() {
        return headline;
    }

    String detailText() {
        return detail.getText();
    }
}
