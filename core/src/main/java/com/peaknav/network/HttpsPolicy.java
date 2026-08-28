package com.peaknav.network;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;

import java.util.Locale;

/**
 * Where the app refuses cleartext {@code http://}, and why it is platform-specific.
 *
 * <p>iOS App Transport Security blocks {@code http://} connections at the OS level, with no
 * error the networking layer can surface - a custom {@code http} tile or download source
 * simply produces a dead layer. So on iOS an {@code http} URL is rejected the moment it is
 * entered, with a message that says what is wrong, rather than being accepted and then
 * failing silently later. Every other platform still allows {@code http} (a user's own LAN
 * tile server, for instance), so this rule is iOS-only by design.
 */
public final class HttpsPolicy {

    private HttpsPolicy() {}

    /** Shown when an {@code http} URL is refused - one message, reused at every entry point. */
    public static final String HTTP_BLOCKED_MESSAGE =
            "On iPhone and iPad only https:// addresses work: iOS blocks unencrypted "
                    + "http:// connections, so an http:// source can never load. "
                    + "Please use an https:// URL.";

    /** True on the platforms whose OS blocks cleartext http - iOS today. */
    public static boolean requiresHttps() {
        return Gdx.app != null && Gdx.app.getType() == Application.ApplicationType.iOS;
    }

    /** True when this URL would be blocked by the current platform: iOS and an http:// address. */
    public static boolean isBlockedHttp(String url) {
        if (url == null || !requiresHttps()) {
            return false;
        }
        return url.trim().toLowerCase(Locale.ROOT).startsWith("http://");
    }
}
