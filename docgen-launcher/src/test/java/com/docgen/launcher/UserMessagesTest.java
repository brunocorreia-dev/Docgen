package com.docgen.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserMessagesTest {
    private static final Pattern TECHNICAL = Pattern.compile("Exception|Error\\b|\\bat [a-z]+\\.|\\.java|null");

    @Test
    void everyStartupFailureHasAPlainPortugueseExplanation() {
        for (BackendStartupException.Reason reason : BackendStartupException.Reason.values()) {
            BackendStartupException failure = new BackendStartupException(reason,
                    "java.lang.IllegalStateException: secret details at com.docgen.Foo(Foo.java:1)", 1, null);
            UserMessages.Notice notice = UserMessages.startup(failure, 90);
            assertFalse(notice.title().isBlank(), reason.name());
            assertFalse(TECHNICAL.matcher(notice.describe()).find(), reason + ": " + notice.describe());
            assertFalse(notice.describe().contains("secret details"), reason.name());
        }
        assertTrue(UserMessages.startup(new BackendStartupException(BackendStartupException.Reason.TIMEOUT, "x"), 45)
                .describe().contains("45 segundos"));
    }

    @Test
    void coversTheRequiredFailureSituations() {
        assertEquals("Porta ocupada", UserMessages.startup(
                new BackendStartupException(BackendStartupException.Reason.PORT_IN_USE, "x"), 90).title());
        assertEquals("Runtime Java incompatível", UserMessages.diagnostics(new LauncherDiagnostics.Check("runtime",
                "Runtime Java", LauncherDiagnostics.Status.FAILURE, LauncherDiagnostics.Category.RUNTIME, "Java 17")).title());
        assertEquals("Arquivos do DocGen ausentes", UserMessages.diagnostics(new LauncherDiagnostics.Check("backend",
                "Backend", LauncherDiagnostics.Status.FAILURE, LauncherDiagnostics.Category.MISSING_FILES, "não encontrado")).title());
        assertEquals("Permissão negada", UserMessages.diagnostics(new LauncherDiagnostics.Check("log-directory",
                "Diretório de logs", LauncherDiagnostics.Status.FAILURE, LauncherDiagnostics.Category.PERMISSION, "/x")).title());
        assertEquals("Falha de autenticação", UserMessages.pairingClosed("conflict").title());
        assertTrue(UserMessages.browserFailed("http://127.0.0.1:1/#/desktop").describe().contains("http://127.0.0.1:1/#/desktop"));
        assertEquals("Backend indisponível", UserMessages.startup(
                new BackendStartupException(BackendStartupException.Reason.TIMEOUT, "x"), 90).title());
    }

    @Test
    void theBrandIconRendersAtEveryWindowSizeAndExportsPng(@TempDir Path temp) throws Exception {
        for (int size : BrandIcon.WINDOW_SIZES) {
            BufferedImage image = BrandIcon.render(size);
            assertEquals(size, image.getWidth());
            assertEquals(0, image.getRGB(0, 0) >>> 24, "transparent corner");
            assertEquals(0xff, image.getRGB(size / 2, size / 6) >>> 24, "opaque body");
        }
        Path png = temp.resolve("icons/docgen.png");
        BrandIcon.main(new String[]{png.toString(), "256"});
        assertEquals(256, ImageIO.read(Files.newInputStream(png)).getWidth());
    }

    @Test
    void progressStatesMarkEarlierStepsDone() {
        LauncherViewState connection = LauncherViewState.progress(LauncherViewState.Step.CONNECTION, "h", "d");
        assertEquals(LauncherViewState.StepStatus.DONE, connection.steps().get(LauncherViewState.Step.BACKEND));
        assertEquals(LauncherViewState.StepStatus.ACTIVE, connection.steps().get(LauncherViewState.Step.CONNECTION));
        assertEquals(LauncherViewState.StepStatus.PENDING, connection.steps().get(LauncherViewState.Step.READY));
        assertFalse(connection.canOpen());
        assertFalse(LauncherViewState.stopping().canQuit());
    }
}
