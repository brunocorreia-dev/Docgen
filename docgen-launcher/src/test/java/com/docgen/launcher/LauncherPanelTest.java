package com.docgen.launcher;

import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The window content is exercised headlessly: no frame is opened, but everything is laid out and painted. */
class LauncherPanelTest {
    private final List<String> actions = new ArrayList<>();

    private LauncherPanel panel() throws InterruptedException, InvocationTargetException {
        AtomicReference<LauncherPanel> created = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> created.set(new LauncherPanel(new LauncherPanel.Actions() {
            @Override
            public void openWorkspace() {
                actions.add("open");
            }

            @Override
            public void showLogs() {
                actions.add("logs");
            }

            @Override
            public void quit() {
                actions.add("quit");
            }

            @Override
            public void retry() {
                actions.add("retry");
            }

            @Override
            public void approvePairing(String pairingId) {
                actions.add("approve:" + pairingId);
            }

            @Override
            public void denyPairing(String pairingId) {
                actions.add("deny:" + pairingId);
            }

            @Override
            public void copyAddress(String address) {
                actions.add("copy:" + address);
            }
        })));
        return created.get();
    }

    private static void onEdt(Runnable action) throws InterruptedException, InvocationTargetException {
        SwingUtilities.invokeAndWait(action);
    }

    @Test
    void startsWithRealProgressAndNothingToOpen() throws Exception {
        LauncherPanel panel = panel();
        onEdt(() -> {
            assertEquals("Verificando ambiente...", panel.headlineLabel().getText());
            assertFalse(panel.openButton().isEnabled());
            assertFalse(panel.retryButton().isVisible());
            assertFalse(panel.pairingPanel().isVisible());
            assertEquals("Verificando ambiente: em andamento",
                    panel.stepLabel(LauncherViewState.Step.ENVIRONMENT).getAccessibleContext().getAccessibleName());
            assertEquals("DocGen pronto: pendente",
                    panel.stepLabel(LauncherViewState.Step.READY).getAccessibleContext().getAccessibleName());
        });
    }

    @Test
    void readyEnablesOpeningAndShowsAnAddressWithoutCredentials() throws Exception {
        LauncherPanel panel = panel();
        onEdt(() -> {
            panel.render(LauncherViewState.ready("http://127.0.0.1:48123/#/desktop"));
            assertEquals("DocGen pronto!", panel.headlineLabel().getText());
            assertTrue(panel.openButton().isEnabled());
            assertEquals(panel.openButton(), panel.defaultButton());
            panel.openButton().doClick();
            assertEquals(List.of("open"), actions);
        });
    }

    @Test
    void failuresOfferRetryAndExplainWithoutStackTraces() throws Exception {
        LauncherPanel panel = panel();
        BackendStartupException timeout = new BackendStartupException(BackendStartupException.Reason.TIMEOUT,
                "java.util.concurrent.TimeoutException at com.docgen...");
        onEdt(() -> {
            panel.render(LauncherViewState.failed(LauncherViewState.Step.BACKEND, UserMessages.startup(timeout, 90)));
            assertEquals("Backend indisponível", panel.headlineLabel().getText());
            assertTrue(panel.retryButton().isVisible());
            assertFalse(panel.openButton().isVisible());
            assertEquals(panel.retryButton(), panel.defaultButton());
            assertFalse(panel.detailText().contains("Exception"));
            assertEquals("Inicializando backend: falhou",
                    panel.stepLabel(LauncherViewState.Step.BACKEND).getAccessibleContext().getAccessibleName());
            assertEquals("Verificando ambiente: concluído",
                    panel.stepLabel(LauncherViewState.Step.ENVIRONMENT).getAccessibleContext().getAccessibleName());
            panel.retryButton().doClick();
            assertEquals(List.of("retry"), actions);
        });
    }

    @Test
    void showsAPairingRequestWithItsCodeAndRoutesTheDecision() throws Exception {
        LauncherPanel panel = panel();
        onEdt(() -> {
            panel.render(LauncherViewState.ready("http://127.0.0.1:1/#/desktop")
                    .withPairing(new LauncherViewState.Pairing("p-1", "ABCD-EFGH")));
            assertTrue(panel.pairingPanel().isVisible());
            panel.denyButton().doClick();
            panel.approveButton().doClick();
            assertEquals(List.of("deny:p-1", "approve:p-1"), actions);
        });
    }

    @Test
    void everyActionHasAKeyboardMnemonic() throws Exception {
        LauncherPanel panel = panel();
        onEdt(() -> {
            for (var button : List.of(panel.openButton(), panel.retryButton(), panel.quitButton(),
                    panel.approveButton(), panel.denyButton())) {
                assertNotEquals(0, button.getMnemonic(), button.getText());
                assertTrue(button.isFocusable());
            }
        });
    }

    @Test
    void paintsEveryStateOffscreen() throws Exception {
        LauncherPanel panel = panel();
        Path previews = Path.of("target", "ui-preview");
        Files.createDirectories(previews);
        List<LauncherViewState> states = List.of(
                LauncherViewState.initial(),
                LauncherViewState.progress(LauncherViewState.Step.CONNECTION, "Verificando conexão...",
                        "Confirmando que o backend é o processo iniciado por este aplicativo."),
                LauncherViewState.ready("http://127.0.0.1:48123/#/desktop")
                        .withPairing(new LauncherViewState.Pairing("p-1", "ABCD-EFGH")),
                LauncherViewState.failed(LauncherViewState.Step.CONNECTION, UserMessages.startup(
                        new BackendStartupException(BackendStartupException.Reason.HANDSHAKE_FAILED, "x"), 90)));
        for (int index = 0; index < states.size(); index++) {
            LauncherViewState state = states.get(index);
            AtomicReference<BufferedImage> image = new AtomicReference<>();
            onEdt(() -> {
                panel.render(state);
                panel.setSize(580, 720);
                // Two passes, as in a real window: wrapped text measures itself once its container has a width.
                // Container.validate() needs a native peer, so the headless test lays the tree out itself.
                for (int pass = 0; pass < 2; pass++) {
                    invalidateAll(panel);
                    layout(panel);
                }
                BufferedImage canvas = new BufferedImage(580, 720, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = canvas.createGraphics();
                panel.paint(g);
                g.dispose();
                image.set(canvas);
            });
            write(image.get(), previews.resolve("launcher-" + index + ".png"));
            assertNotEquals(LauncherTheme.BACKGROUND.getRGB(), image.get().getRGB(300, 160) | 0xff000000,
                    "the status card is painted");
        }
    }

    private static void layout(Component component) {
        component.doLayout();
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                layout(child);
            }
        }
    }

    private static void invalidateAll(Component component) {
        component.invalidate();
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                invalidateAll(child);
            }
        }
    }

    private static void write(BufferedImage image, Path file) throws IOException {
        ImageIO.write(image, "png", file.toFile());
    }
}
