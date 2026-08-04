package com.peaknav.viewer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.I18NBundle;

import java.util.Locale;
import java.util.MissingResourceException;

public class I18NWrapper {

    /**
     * Language to use instead of the system one, or null to follow the system.
     *
     * <p>The app should speak whatever language the device is set to, and does. A scripted
     * render should not: it ran on an Italian desktop and labelled the planets Mercurio and
     * Venere, so the same shot came out differently depending on whose machine produced it.
     * The headless renderer sets this; nothing else does, so the app is unaffected.
     */
    private static volatile Locale localeOverride = null;

    /** Fixes the language for this process. Call before the app is created. */
    public static void setLocaleOverride(Locale locale) {
        localeOverride = locale;
    }

    /** The language this process was pinned to, or null if it follows the system. */
    public static Locale getLocaleOverride() {
        return localeOverride;
    }

    private final I18NBundle i18NBundle;

    public I18NWrapper() {
        I18NBundle i18NBundle;
        Locale locale = localeOverride;
        try {
            i18NBundle = locale == null
                    ? I18NBundle.createBundle(Gdx.files.internal("i18n/strings"))
                    : I18NBundle.createBundle(Gdx.files.internal("i18n/strings"), locale);
        } catch (MissingResourceException missingResourceException) {
            // No translation for that language: English rather than nothing.
            i18NBundle = I18NBundle.createBundle(Gdx.files.internal("i18n/strings"), Locale.UK);
        }
        this.i18NBundle = i18NBundle;
        I18NBundle.setExceptionOnMissingKey(false);
    }

    public String s(String key) {
        if (i18NBundle == null)
            return key;
        return i18NBundle.get(key);
    }
}
