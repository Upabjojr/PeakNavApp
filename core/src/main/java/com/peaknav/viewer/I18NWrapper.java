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

    /** The language the interface ended up speaking; see {@link #getLanguage()}. */
    private final String language;

    /** Serbian written in Latin: the one Serbian translation is Cyrillic, and is transliterated. */
    private final boolean serbianLatin;

    public I18NWrapper() {
        I18NBundle i18NBundle;
        Locale locale = localeOverride != null ? localeOverride : translationLocale(chosenOrDeviceLocale());
        this.language = locale.getLanguage();
        this.serbianLatin = isSerbianLatin(locale);
        try {
            i18NBundle = I18NBundle.createBundle(Gdx.files.internal("i18n/strings"), locale);
        } catch (MissingResourceException missingResourceException) {
            // No translation for that language: English rather than nothing.
            i18NBundle = I18NBundle.createBundle(Gdx.files.internal("i18n/strings"), Locale.UK);
        }
        this.i18NBundle = i18NBundle;
        I18NBundle.setExceptionOnMissingKey(false);
    }

    /**
     * The languages the app is translated into, as their two-letter codes, each with its name
     * in itself - how a reader finds their own language in a list of languages they do not
     * read. English first, then by name.
     */
    public static final String[][] LANGUAGES = {
            {"en", "English"}, {"bg", "Български"}, {"cs", "Čeština"}, {"da", "Dansk"},
            {"de", "Deutsch"}, {"el", "Ελληνικά"}, {"es", "Español"}, {"fr", "Français"},
            {"hr", "Hrvatski"}, {"it", "Italiano"}, {"nl", "Nederlands"}, {"no", "Norsk"},
            {"pl", "Polski"}, {"pt", "Português"}, {"ro", "Română"}, {"ru", "Русский"},
            {"sk", "Slovenčina"}, {"sl", "Slovenščina"}, {"sr", "Српски"}, {"fi", "Suomi"},
            {"sv", "Svenska"}, {"uk", "Українська"},
    };

    /** The name of a language in itself, or null for a code not among {@link #LANGUAGES}. */
    public static String nameOf(String code) {
        for (String[] language : LANGUAGES) {
            if (language[0].equals(code)) {
                return language[1];
            }
        }
        return null;
    }

    /**
     * The language the user chose in the options, over the device's; the device's where they
     * chose none. Chosen, it is the reader's language everywhere - the menus, the place search,
     * which script labels are written in.
     */
    private static Locale chosenOrDeviceLocale() {
        com.peaknav.utils.PreferencesManager preferences = com.peaknav.utils.PreferencesManager.P;
        String chosen = preferences == null ? "" : preferences.getLanguage();
        if (chosen != null && !chosen.isEmpty() && nameOf(chosen) != null) {
            return new Locale(chosen);
        }
        return Locale.getDefault();
    }

    /**
     * The locale whose strings file holds the translation for {@code locale}.
     *
     * <p>Norwegian reaches the app as "nb" (Bokmal) or "nn" (Nynorsk) - on iOS, and on Android
     * since 7 - but its translation is strings_no. There is no base strings file for a lookup
     * to end in, so asking for "nb" found nothing and the app fell back to English.
     */
    static Locale translationLocale(Locale locale) {
        String language = locale.getLanguage();
        if ("nb".equals(language) || "nn".equals(language)) {
            return new Locale("no", locale.getCountry());
        }
        return locale;
    }

    /**
     * The two-letter language the app is speaking, for asking a service outside the app for
     * names in it - the place search does, so that a search made in Italian comes back in
     * Italian rather than in whatever the place's own country writes.
     *
     * <p>The language asked for, not the translation that was found: a Danish device with no
     * Danish strings reads the app in English, but still deserves Danish place names where
     * they exist.
     */
    public String getLanguage() {
        return language;
    }

    public String s(String key) {
        if (i18NBundle == null)
            return key;
        String text = i18NBundle.get(key);
        return serbianLatin ? toSerbianLatin(text) : text;
    }

    /**
     * Whether this is Serbian in the Latin script, as a device set to "srpski (latinica)"
     * reports it: "sr_RS_#Latn", or "sr_Latn_RS" from older runtimes.
     *
     * <p>libGDX's bundles ignore the script, so such a device was given strings_sr, which is
     * Cyrillic. The string is searched rather than {@code Locale.getScript()} asked, which
     * RoboVM's class library may not have.
     */
    static boolean isSerbianLatin(Locale locale) {
        return "sr".equals(locale.getLanguage())
                && locale.toString().toLowerCase(Locale.ROOT).contains("latn");
    }

    private static final String CYRILLIC = "АБВГДЂЕЖЗИЈКЛЉМНЊОПРСТЋУФХЦЧЏШабвгдђежзијклљмнњопрстћуфхцчџш";
    private static final String[] LATIN = {
            "A", "B", "V", "G", "D", "Đ", "E", "Ž", "Z", "I", "J", "K", "L", "Lj", "M", "N", "Nj",
            "O", "P", "R", "S", "T", "Ć", "U", "F", "H", "C", "Č", "Dž", "Š",
            "a", "b", "v", "g", "d", "đ", "e", "ž", "z", "i", "j", "k", "l", "lj", "m", "n", "nj",
            "o", "p", "r", "s", "t", "ć", "u", "f", "h", "c", "č", "dž", "š",
    };

    /**
     * Serbian Cyrillic in Latin letters. The script maps one to one, apart from the three
     * capitals written as two letters: Lj, Nj, Dž, all capitals in a word written in capitals.
     */
    static String toSerbianLatin(String text) {
        StringBuilder out = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int index = CYRILLIC.indexOf(c);
            if (index < 0) {
                if (out != null) {
                    out.append(c);
                }
                continue;
            }
            if (out == null) {
                out = new StringBuilder(text.length() + 8).append(text, 0, i);
            }
            String latin = LATIN[index];
            if (latin.length() == 2 && Character.isUpperCase(latin.charAt(0))
                    && i + 1 < text.length() && Character.isUpperCase(text.charAt(i + 1))) {
                latin = latin.toUpperCase(Locale.ROOT);
            }
            out.append(latin);
        }
        return out == null ? text : out.toString();
    }
}
