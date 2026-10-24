package com.docgen.desktop;

import java.net.URI;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Opens a DocGen window. Only the launch address the launcher builds itself
 * is accepted: {@code http://127.0.0.1:<port>/launch#<token>}, with no user
 * info, no query and nothing else in the fragment.
 */
@FunctionalInterface
interface BrowserOpener {
    Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");

    /** @return true when a browser accepted the address */
    boolean open(URI address);

    static boolean acceptable(URI address) {
        return address != null
                && "http".equals(address.getScheme())
                && ReopenClient.HOST.equals(address.getHost())
                && address.getPort() >= 1 && address.getPort() <= 65_535
                && address.getRawUserInfo() == null
                && "/launch".equals(address.getRawPath())
                && address.getRawQuery() == null
                && address.getRawFragment() != null && TOKEN.matcher(address.getRawFragment()).matches();
    }

    /** Tries each opener in order until one succeeds; refuses any address that is not a launch address. */
    static BrowserOpener firstThatWorks(List<BrowserOpener> openers) {
        List<BrowserOpener> ordered = List.copyOf(openers);
        return address -> {
            if (!acceptable(address)) {
                return false;
            }
            for (BrowserOpener opener : ordered) {
                if (opener.open(address)) {
                    return true;
                }
            }
            return false;
        };
    }
}
