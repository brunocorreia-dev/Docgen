package com.docgen.desktop;

import java.util.Locale;

/** The operating system family, from the JVM's {@code os.name}. */
enum Platform {
    LINUX, WINDOWS, MACOS, OTHER;

    static Platform current() {
        return detect(System.getProperty("os.name", ""));
    }

    static Platform detect(String osName) {
        String name = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (name.startsWith("linux")) {
            return LINUX;
        }
        if (name.startsWith("windows")) {
            return WINDOWS;
        }
        if (name.startsWith("mac") || name.startsWith("darwin")) {
            return MACOS;
        }
        return OTHER;
    }
}
