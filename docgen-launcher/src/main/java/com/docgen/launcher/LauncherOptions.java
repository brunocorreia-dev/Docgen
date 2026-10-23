package com.docgen.launcher;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/**
 * Command-line options of the launcher. Desktop users normally start it from
 * the application menu without arguments; the options exist for diagnostics
 * and development. Parsing is strict: unknown options are errors.
 *
 * @param backendJar explicit backend jar (development), or {@code null} to use the installation layout
 */
record LauncherOptions(Mode mode, Path backendJar) {
    enum Mode {
        /** Opens the desktop window. */
        DESKTOP,
        /** Prints the environment diagnostics and exits. */
        DIAGNOSE,
        HELP,
        VERSION
    }

    static final String USAGE = """
            Uso: docgen-launcher [opções]

            Sem opções, abre a janela do DocGen.

              --diagnose            Verifica o ambiente (sistema, Java, arquivos e permissões) e sai.
              --backend-jar <jar>   Usa este docgen-api.jar em vez do instalado (desenvolvimento).
              --help                Mostra esta ajuda.
              --version             Mostra a versão do launcher.
            """;

    static LauncherOptions parse(List<String> args) {
        Mode mode = Mode.DESKTOP;
        Path backendJar = null;
        for (int index = 0; index < args.size(); index++) {
            String arg = args.get(index);
            String value = null;
            int equals = arg.indexOf('=');
            if (arg.startsWith("--") && equals > 0) {
                value = arg.substring(equals + 1);
                arg = arg.substring(0, equals);
            }
            switch (arg) {
                case "--backend-jar" -> {
                    if (value == null) {
                        if (index + 1 >= args.size()) {
                            throw new IllegalArgumentException("--backend-jar precisa de um caminho.");
                        }
                        value = args.get(++index);
                    }
                    if (backendJar != null) {
                        throw new IllegalArgumentException("--backend-jar foi informado mais de uma vez.");
                    }
                    backendJar = path(value);
                }
                case "--help", "-h" -> mode = switchMode(mode, Mode.HELP, value);
                case "--version" -> mode = switchMode(mode, Mode.VERSION, value);
                case "--diagnose" -> mode = switchMode(mode, Mode.DIAGNOSE, value);
                default -> throw new IllegalArgumentException("Opção desconhecida: " + printable(arg));
            }
        }
        return new LauncherOptions(mode, backendJar);
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
