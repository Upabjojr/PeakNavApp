package com.peaknav.utils;

import com.ibm.icu.lang.UScript;
import com.ibm.icu.util.ULocale;
import com.peaknav.viewer.labels.LabelTextRasterizer;
import com.peaknav.viewer.labels.LabelTextRasterizers;

import java.util.HashSet;
import java.util.Set;

/**
 * Which script a map label is written in.
 *
 * <p>A place's name is shown in its own script when the reader reads that script - the
 * language the device is set to uses it: 富士山 on a Japanese phone, Эльбрус on a Russian one,
 * Όλυμπος on a Greek one - and something can draw it: the app's fonts for Greek and Cyrillic,
 * the platform's text for the rest ({@link LabelTextRasterizer}). Everyone else gets the Latin
 * form the labels always had: an Italian reader is not helped by Georgian letters.
 *
 * <p>The reader's language is the device's, not the app's translation: a Japanese phone shows
 * the app's menus in English, the app having no Japanese, but can still read Japanese names.
 */
public final class LabelScripts {

    private LabelScripts() {
    }

    private static volatile String cachedLanguage;
    private static volatile Set<Integer> cachedScripts;

    /** Whether {@code name}, which has non-Latin letters, is shown as it is written. */
    public static boolean keepsOwnScript(String name) {
        if (name == null || !FontCharacters.containsNonLatin(name)) {
            return false;
        }
        String language = readerLanguage();
        if (language == null || !readerReads(name, language)) {
            return false;
        }
        if (!FontCharacters.containsUnrenderable(name)) {
            return true;   // Greek, Cyrillic: the app's fonts draw it
        }
        LabelTextRasterizer rasterizer = LabelTextRasterizers.get();
        return rasterizer != null && rasterizer.canDraw(name);
    }

    /** Folds any script to Latin letters, as PoiObject's labels are. Built on first use. */
    private static volatile com.ibm.icu.text.Transliterator latin;

    /**
     * A road's or trail's name as the road-name layer can draw it. That layer draws letter by
     * letter along the road's curve with the app's fonts, which the platform's text cannot do,
     * so a name is kept as written only when those fonts draw it and its reader reads it -
     * Cyrillic for a Russian reader. Otherwise its {@code name:en}, else its letters folded to
     * Latin: a name in a script the fonts lack was a row of boxes. Null for a name in Chinese
     * characters with no Latin form: see below.
     */
    public static String roadName(String name, String nameEn) {
        if (name == null) {
            return null;
        }
        boolean undrawable = FontCharacters.containsUnrenderable(name);
        boolean unread = FontCharacters.containsNonLatin(name) && !readerReads(name);
        if (!undrawable && !unread) {
            return name;
        }
        if (nameEn != null && !nameEn.isEmpty() && !FontCharacters.containsNonLatin(nameEn)) {
            return nameEn;
        }
        if (CjkLabelNames.containsHan(name)) {
            // Chinese characters read as Chinese: right in China, wrong everywhere they spell
            // Japanese (大沢林道 came out "da ze lin dao"), and a road does not know which it
            // is in. No name rather than a wrong one - it used to be a row of boxes.
            return null;
        }
        com.ibm.icu.text.Transliterator t = latin;
        if (t == null) {
            t = com.ibm.icu.text.Transliterator.getInstance("Any-Latin; Latin-ASCII");
            latin = t;
        }
        return t.transliterate(name);
    }

    private static boolean readerReads(String name) {
        String language = readerLanguage();
        return language != null && readerReads(name, language);
    }

    private static String readerLanguage() {
        try {
            return PeakNavUtils.getC().i18n.getLanguage();
        } catch (RuntimeException notYet) {   // no app yet: the tests, the tools
            return null;
        }
    }

    /**
     * Whether every letter of the name is in a script the language is written in. Digits,
     * punctuation and spaces belong to no script (COMMON) and do not count; neither do Latin
     * letters, which every reader reads.
     */
    static boolean readerReads(String name, String language) {
        Set<Integer> scripts = scriptsOf(language);
        if (scripts.isEmpty()) {
            return false;
        }
        boolean any = false;
        for (int i = 0; i < name.length(); ) {
            int codePoint = name.codePointAt(i);
            i += Character.charCount(codePoint);
            int script = UScript.getScript(codePoint);
            if (script == UScript.COMMON || script == UScript.INHERITED || script == UScript.LATIN) {
                continue;
            }
            if (!scripts.contains(script)) {
                return false;
            }
            any = true;
        }
        return any;
    }

    private static Set<Integer> scriptsOf(String language) {
        if (language.equals(cachedLanguage) && cachedScripts != null) {
            return cachedScripts;
        }
        Set<Integer> scripts = new HashSet<>();
        int[] codes = UScript.getCode(new ULocale(language));
        if (codes != null) {
            for (int code : codes) {
                scripts.add(code);
            }
        }
        // Japanese is written in three at once; ICU names them as one (JPAN) for the locale.
        if (scripts.contains(UScript.JAPANESE) || "ja".equals(language)) {
            scripts.add(UScript.HAN);
            scripts.add(UScript.HIRAGANA);
            scripts.add(UScript.KATAKANA);
        }
        if (scripts.contains(UScript.KOREAN) || "ko".equals(language)) {
            scripts.add(UScript.HANGUL);
            scripts.add(UScript.HAN);
        }
        if (scripts.contains(UScript.SIMPLIFIED_HAN) || scripts.contains(UScript.TRADITIONAL_HAN)
                || "zh".equals(language)) {
            scripts.add(UScript.HAN);
        }
        cachedScripts = scripts;
        cachedLanguage = language;
        return scripts;
    }
}
