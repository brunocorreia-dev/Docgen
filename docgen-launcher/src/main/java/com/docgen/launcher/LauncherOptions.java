package com.docgen.launcher;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Command-line options of the launcher. Desktop users normally start it from
 * the application menu without arguments; the options exist for diagnostics,
 * smoke tests and development. Parsing is strict: unknown options are errors.
 *
 * @param backendJar     explicit backend jar (development), or {@code null} to use the installation layout
 * @param port           fixed port, or {@code null} to use DOCGEN_API_PORT or an ephemeral port
 * @param startupTimeout maximum time for the backend to become ready
 */
record LauncherOptions(Mode mode, Path backendJar, Integer port, Duration startupTimeout) {
    enum Mode {
        /** Opens the desktop window. */
        DESKTOP,
        /** Prints the environment diagnostics and exits. */
        DIAGNOSE,
        /** Starts the backend, verifies its identity, stops it and exits (no window needed). */
        CHECK,
        HELP,
        VERSION
    }

    static final String USAGE = """
            Uso: docgen-launcher [opções]

            Sem opções, abre a janela do DocGen.

              --diagnose               Verifica o ambiente (sistema, Java, arquivos e permissões) e sai.
              --check                  Inicia o backend, valida a identidade dele, encerra-o e sai (sem janela).
              --port <porta>           Porta fixa em 127.0.0.1 (padrão: DOCGEN_API_PORT ou uma porta livre).
              --startup-timeout <s>    Tempo máximo de inicialização do backend, em segundos (10 a 600; padrão 90).
              --backend-jar <jar>      Usa este docgen-api.jar em vez do instalado (desenvolvimento).
              --help                   Mostra esta ajuda.
              --version                Mostra a versão do launcher.
            """;

    static LauncherOptions parse(List<String> args) {
        Mode mode = Mode.DESKTOP;
        Path backendJar = null;
        Integer port = null;
        Duration startupTimeout = BackendConfiguration.DEFAULT_STARTUP_TIMEOUT;
        boolean timeoutGiven = false;
        for (int index = 0; index < args.size(); index++) {
            String arg = args.get(index);
            String value = null;
            int equals = arg.indexOf('=');
            if (arg.startsWith("--") && equals > 0) {
                value = arg.substring(equals + 1);
                arg = arg.substring(0, equals);
            }
            switch (arg) {
                case "--backend-jar", "--port", "--startup-timeout" -> {
                    if (value == null) {
                        if (index + 1 >= args.size()) {
                            throw new IllegalArgumentException(arg + " precisa de um valor.");
                        }
                        value = args.get(++index);
                    }
                    switch (arg) {
                        case "--backend-jar" -> {
                            if (backendJar != null) {
                                throw new IllegalArgumentException("--backend-jar foi informado mais de uma vez.");
                            }
                            backendJar = path(value);
                        }
                        case "--port" -> {
                            if (port != null) {
                                throw new IllegalArgumentException("--port foi informado mais de uma vez.");
                            }
                            port = number(value, 1, 65_535, "--port precisa ser um número entre 1 e 65535.");
                        }
                        default -> {
                            if (timeoutGiven) {
                                throw new IllegalArgumentException("--startup-timeout foi informado mais de uma vez.");
                            }
                            timeoutGiven = true;
                            startupTimeout = Duration.ofSeconds(number(value, 10, 600,
                                    "--startup-timeout precisa ser um número de segundos entre 10 e 600."));
                        }
                    }
                }
                case "--help", "-h" -> mode = switchMode(mode, Mode.HELP, value);
                case "--version" -> mode = switchMode(mode, Mode.VERSION, value);
                case "--diagnose" -> mode = switchMode(mode, Mode.DIAGNOSE, value);
                case "--check" -> mode = switchMode(mode, Mode.CHECK, value);
                default -> throw new IllegalArgumentException("Opção desconhecida: " + printable(arg));
            }
        }
        return new LauncherOptions(mode, backendJar, port, startupTimeout);
    }

    private static Mode switchMode(Mode current, Mode requested, String value) {
        if (value != null) {
            throw new IllegalArgumentException("A opção não aceita valor.");
        }
        if (current != Mode.DESKTOP && current != requested) {
            throw new IllegalArgumentException("Use apenas um modo por vez.");
        }
        return requested;
    }

    private static int number(String value, int min, int max, String message) {
        if (value == null || value.isEmpty() || value.length() > 6 || !value.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException(message);
        }
        int number = Integer.parseInt(value);
        if (number < min || number > max) {
            throw new IllegalArgumentException(message);
        }
        return number;
    }

    private static Path path(String value) {
        if (value.isBlank() || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Caminho inválido para --backend-jar.");
        }
        try {
            return Path.of(value);
        } catch (InvalidPathException invalid) {
            throw new IllegalArgumentException("Caminho inválido para --backend-jar.");
        }
    }

    /** Arguments are echoed in errors, so control characters are neutralized and the length bounded. */
    static String printable(String value) {
        StringBuilder result = new StringBuilder();
        value.codePoints().limit(80).forEach(codePoint ->
                result.appendCodePoint(Character.isISOControl(codePoint) ? '?' : codePoint));
        return result.toString();
    }
}
