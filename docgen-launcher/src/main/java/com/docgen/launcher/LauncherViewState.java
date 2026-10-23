package com.docgen.launcher;

import java.util.EnumMap;
import java.util.Map;

/**
 * Everything the window shows, as one immutable value. Each startup step
 * mirrors a real state of the launcher (there are no invented percentages):
 * environment checks, the backend process, the identity handshake, ready.
 *
 * @param headline  one short line in Portuguese, e.g. "Verificando ambiente..."
 * @param detail    explanation for the person; never a stack trace
 * @param address   workspace address once ready (it carries no credential), else {@code null}
 * @param pairing   a browser request waiting for a decision, else {@code null}
 */
record LauncherViewState(
        Map<Step, StepStatus> steps,
        Tone tone,
        String headline,
        String detail,
        String address,
        boolean canOpen,
        boolean canRetry,
        boolean canQuit,
        Pairing pairing
) {
    enum Step {
        ENVIRONMENT("Verificando ambiente"),
        BACKEND("Inicializando backend"),
        CONNECTION("Verificando conexão"),
        READY("DocGen pronto");

        private final String label;

        Step(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    enum StepStatus {
        PENDING("pendente"),
        ACTIVE("em andamento"),
        DONE("concluído"),
        FAILED("falhou");

        private final String label;

        StepStatus(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    enum Tone {
        PROGRESS,
        SUCCESS,
        WARNING,
        ERROR
    }

    record Pairing(String pairingId, String code) {
    }

    LauncherViewState {
        Map<Step, StepStatus> copy = new EnumMap<>(Step.class);
        for (Step step : Step.values()) {
            copy.put(step, steps == null ? StepStatus.PENDING : steps.getOrDefault(step, StepStatus.PENDING));
        }
        steps = Map.copyOf(copy);
    }

    static LauncherViewState initial() {
        return progress(Step.ENVIRONMENT, "Verificando ambiente...", "Conferindo o sistema, o Java e os arquivos do DocGen.");
    }

    /** {@code current} is active, every earlier step is done and every later one is pending. */
    static LauncherViewState progress(Step current, String headline, String detail) {
        Map<Step, StepStatus> steps = new EnumMap<>(Step.class);
        for (Step step : Step.values()) {
            steps.put(step, step.ordinal() < current.ordinal() ? StepStatus.DONE
                    : step == current ? StepStatus.ACTIVE : StepStatus.PENDING);
        }
        return new LauncherViewState(steps, Tone.PROGRESS, headline, detail, null, false, false, true, null);
    }

    static LauncherViewState ready(String address) {
        Map<Step, StepStatus> steps = new EnumMap<>(Step.class);
        for (Step step : Step.values()) {
            steps.put(step, StepStatus.DONE);
        }
        return new LauncherViewState(steps, Tone.SUCCESS, "DocGen pronto!",
                "Clique em “Abrir DocGen” para usar o workspace no navegador.", address, true, false, true, null);
    }

    static LauncherViewState failed(Step failedStep, UserMessages.Notice notice) {
        Map<Step, StepStatus> steps = new EnumMap<>(Step.class);
        for (Step step : Step.values()) {
            steps.put(step, step.ordinal() < failedStep.ordinal() ? StepStatus.DONE
                    : step == failedStep ? StepStatus.FAILED : StepStatus.PENDING);
        }
        return new LauncherViewState(steps, Tone.ERROR, notice.title(), notice.describe(), null, false, true, true, null);
    }

    static LauncherViewState stopping() {
        return new LauncherViewState(null, Tone.PROGRESS, "Encerrando o DocGen...",
                "Finalizando o backend com segurança.", null, false, false, false, null);
    }

    LauncherViewState withPairing(Pairing value) {
        return new LauncherViewState(steps, tone, headline, detail, address, canOpen, canRetry, canQuit, value);
    }

    /** A notice shown while the backend keeps running (for example, the browser could not be opened). */
    LauncherViewState withNotice(Tone noticeTone, UserMessages.Notice notice) {
        return new LauncherViewState(steps, noticeTone, notice.title(), notice.describe(), address, canOpen, canRetry,
                canQuit, pairing);
    }
}
