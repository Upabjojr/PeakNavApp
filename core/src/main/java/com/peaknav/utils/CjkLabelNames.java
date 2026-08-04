package com.peaknav.utils;

import com.ibm.icu.text.Transliterator;

/**
 * Chooses the Latin label for a place whose name the map font cannot draw.
 *
 * <p>The problem this solves is specifically Japanese. The generic fallback - ICU's
 * {@code Any-Latin} - reads Han characters as Chinese pinyin, which is right for China
 * and wrong everywhere kanji spell Japanese words: 高座山 (Takazasu-yama) came out
 * "gao zuo shan". A kanji's Japanese reading depends on the word it is in, which no
 * character table knows; only the data can say. So the order here is: names the data
 * gives in Latin, then Japanese kana the data gives (kana readings are unambiguous,
 * ICU romanizes them correctly), and when a Japanese name is kanji-only with no reading
 * anywhere, NO label - a made-up Chinese reading on a Japanese mountain is worse than
 * none. Chinese and Korean names never reach that rule and keep their correct
 * romanizations.
 */
public final class CjkLabelNames {

    private CjkLabelNames() {}

    /** Kana to Latin only - deterministic, unlike kanji. Folded to ASCII like all labels. */
    private static final Transliterator KANA_LATIN =
            Transliterator.getInstance("Any-Latin; Latin-ASCII");

    /**
     * The name to draw for a place whose {@code name} tag the font cannot render, or
     * null when no honest Latin form exists and the label should not be drawn at all.
     *
     * @param name     the place's name tag (unrenderable, or this is not called)
     * @param nameEn   {@code name:en}, or null
     * @param nameJaRm {@code name:ja_rm} / {@code alt_name:ja_rm} - rōmaji, or null
     * @param nameLatn {@code name:*-Latn} variants (pinyin for Chinese places), or null
     * @param nameHira {@code name:ja-Hira} - the reading in hiragana, or null
     * @param hasJaTag whether ANY {@code *:ja*} tag was present - the data itself
     *                 saying the place is Japanese
     * @param lat      of the place, for the geographic test when no tag says
     * @param lon      of the place
     */
    public static String bestLatinName(String name, String nameEn, String nameJaRm,
                                       String nameLatn, String nameHira,
                                       boolean hasJaTag, float lat, float lon) {
        if (nameEn != null && !nameEn.isEmpty()) {
            return nameEn;
        }
        if (nameJaRm != null && !nameJaRm.isEmpty()) {
            return capitalize(nameJaRm);
        }
        if (nameLatn != null && !nameLatn.isEmpty()) {
            return nameLatn;
        }
        // A hiragana reading is as good as rōmaji: kana spell sounds, one way each, so
        // this romanization - unlike a kanji's - cannot be wrong.
        if (nameHira != null && !nameHira.isEmpty()) {
            String romanized = KANA_LATIN.transliterate(nameHira).trim();
            if (!romanized.isEmpty() && !PeakNavUtils.containsUnrenderableCharacters(romanized)) {
                return capitalize(romanized);
            }
        }
        if (containsKana(name)) {
            // The name itself carries kana: Japanese for certain. The kana romanize
            // correctly, but any kanji among them would still come out as pinyin
            // syllables spliced into a Japanese word - so only a fully-kana name passes.
            if (!containsHan(name)) {
                return capitalize(KANA_LATIN.transliterate(name).trim());
            }
            return null;
        }
        if (containsHan(name) && (hasJaTag || isInJapan(lat, lon))) {
            // Kanji-only, in Japan, with no reading given anywhere: there is no honest
            // Latin form to offer. The generic transliterator would print the characters'
            // CHINESE readings, which is not this mountain's name in any language.
            return null;
        }
        // Chinese, Korean, and everything else: the caller's generic transliteration
        // (pinyin, revised romanization, ...) is correct for them.
        return name;
    }

    /** Hiragana, katakana, and the halfwidth katakana forms. */
    public static boolean containsKana(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x3040 && c <= 0x30FF) || (c >= 0xFF66 && c <= 0xFF9D)) {
                return true;
            }
        }
        return false;
    }

    /** CJK unified ideographs, the characters whose reading depends on the language. */
    public static boolean containsHan(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            int cp = s.codePointAt(i);
            if ((cp >= 0x4E00 && cp <= 0x9FFF)
                    || (cp >= 0x3400 && cp <= 0x4DBF)
                    || (cp >= 0x20000 && cp <= 0x2A6DF)) {
                return true;
            }
            if (Character.charCount(cp) == 2) {
                i++;
            }
        }
        return false;
    }

    /**
     * Japan by coordinates, for kanji-only names whose tags do not say what language
     * they are. Two boxes: the main arc from Kyushu to Hokkaido, and the Ryukyu chain.
     * The western edges are drawn to EXCLUDE the near misses - Busan at 129.08°E,
     * Jeju, Taiwan, the Chinese coast - accepting that a kanji-only, untagged name on
     * Tsushima (129.3°E, inside by a hair) is the price of not mislabelling Korea.
     * Names with any {@code *:ja} tag never need this test.
     */
    public static boolean isInJapan(float lat, float lon) {
        if (lat >= 30f && lat <= 45.8f && lon >= 129.2f && lon <= 146.2f) {
            return true;
        }
        // The Ryukyus, Okinawa included; Taiwan ends west of 122.2°E.
        return lat >= 24f && lat < 30f && lon >= 122.7f && lon <= 131.5f;
    }

    /** "myoujin-yama" -> "Myoujin-yama", the capitalisation the data's own labels use. */
    private static String capitalize(String s) {
        if (s.isEmpty() || Character.isUpperCase(s.charAt(0))) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
