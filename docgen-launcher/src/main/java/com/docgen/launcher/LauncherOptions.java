package com.docgen.launcher;

import java.util.List;

/**
 * Command-line options of the launcher. Desktop users normally start it from
 * the application menu without arguments; the options exist for diagnostics
 * and development. Parsing is strict: unknown options are errors.
 */
record LauncherOptions(Mode mode) {
    enum Mode {
        /** Opens the desktop window. */
        DESKTOP,
        HELP,
        VERSION
    }

    static final String USAGE = """
            Uso: docgen-launcher [opção]

            Sem opções, abre a janela do DocGen.

              --help       Mostra esta ajuda.
              --version    Mostra a versão do launcher.
            """;

    static LauncherOptions parse(List<String> args) {
        Mode mode = Mode.DESKTOP;
        for (String arg : args) {
            Mode requested = switch (arg) {
                case "--help", "-h" -> Mode.HELP;
                case "--version" -> Mode.VERSION;
                default -> throw new IllegalArgumentException("Opção desconhecida: " + printable(arg));
            };
            if (mode != Mode.DESKTOP && mode != requested) {
                throw new IllegalArgumentException("Use apenas uma opção por vez.");
            }
            mode = requested;
        }
        return new LauncherOptions(mode);
    }

    /** Arguments are echoed in errors, so control characters are neutralized and the length bounded. */
    static String printable(String value) {
        StringBuilder result = new StringBuilder();
        value.codePoints().limit(80).forEach(codePoint ->
                result.appendCodePoint(Character.isISOControl(codePoint) ? '?' : codePoint));
        return result.toString();
    }
}
