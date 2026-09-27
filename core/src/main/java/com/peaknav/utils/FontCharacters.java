package com.peaknav.utils;

/**
 * The set of characters baked into every font atlas, and the test for whether a string can
 * actually be drawn with it.
 *
 * <p>The fonts are bitmap atlases made from Liberation Sans, baked when the app is built (see
 * {@code FontSpecs}) or, where a screen needs them larger, generated at startup: a character that was not baked has no glyph and is
 * drawn as the "missing glyph" box. libGDX's {@code FreeTypeFontGenerator.DEFAULT_CHARS} stops at
 * U+00FF (Latin-1 Supplement), which leaves out Latin Extended-A — so every Croatian, Czech,
 * Polish, Hungarian, Slovak, Slovenian, Turkish or Baltic name came out as a row of boxes
 * (Perućko jezero, Sušac, Križevci, Kőszeg …). Latin Extended-A is therefore baked as well.
 *
 * <p>Generating glyphs on demand instead ({@code FreeTypeFontParameter.incremental}) would cover
 * every script the TTF has, but it must not be used here: it writes to the glyph table lazily from
 * whichever thread first draws a character, while {@code LabelTextMeasure} deliberately reads that
 * table from background threads, relying on it being immutable once generated.
 *
 * <p>The atlas side grows with the square root of the glyph count, so the set is kept to what map
 * labels and the translations actually need. To support a new script present in Liberation Sans,
 * add its range to {@code SCRIPT_RANGES} — everything else, including the fallback to a name's
 * English variant, follows from this one definition.
 */
public final class FontCharacters {

    private FontCharacters() {}

    private static final char ASCII_FIRST = ' ';       // space
    private static final char ASCII_LAST = '~';        // tilde
    private static final char LATIN1_FIRST = (char) 0x00A0; // no-break space
    private static final char LATIN1_LAST = 'ÿ';       // y with diaeresis
    private static final char LATIN_EXT_A_FIRST = 'Ā'; // A with macron
    /** Highest code point in the baked ranges (U+017F, long s — end of Latin Extended-A). */
    private static final char LATIN_EXT_A_LAST = 'ſ';

    /**
     * Punctuation beyond Latin-1 that shows up in place names and in the UI: the en/em dashes used
     * in compound range names ("Kamnik–Savinja Alps"), typographic quotes, the ellipsis, the bullet,
     * the euro sign, and the arrows the keyboard-controls overlay labels its aim keys with.
     */
    private static final String EXTRA_PUNCTUATION =
            "–—‘’“”•…€←↑→↓„‚‹›№";

    /**
     * The alphabets of the translations beyond Latin: Romanian's comma-below s and t (Latin
     * Extended-B; Latin Extended-A has only the older cedilla forms), modern Greek with its
     * accented vowels, and Cyrillic for Russian, Ukrainian (Ґ ґ sit apart, at U+0490),
     * Bulgarian and Serbian. Liberation Sans draws them all. Each pair is a first and last
     * code point, inclusive.
     */
    private static final char[][] SCRIPT_RANGES = {
            {(char) 0x0218, (char) 0x021B},   // Ș ș Ț ț
            {(char) 0x0384, (char) 0x03CE},   // Greek: tonos, capitals, small letters
            {(char) 0x0400, (char) 0x045F},   // Cyrillic: Russian, Ukrainian, Bulgarian, Serbian
            {(char) 0x0490, (char) 0x0491},   // Ґ ґ
    };

    /** Past the last code point any baked character has, for the lookup table. */
    private static final int LOOKUP_SIZE = 0x2200;

    /** The string handed to FreeType. {@code \0} must come first, or missingGlyph is never set. */
    public static final String BAKED = build();

    /** Membership test for the baked ranges, so the check is a single array read. */
    private static final boolean[] RENDERABLE = buildLookup();

    private static String build() {
        StringBuilder sb = new StringBuilder(400);
        sb.append((char) 0); // the missing-glyph slot; FreeType requires it first
        for (char c = ASCII_FIRST; c <= ASCII_LAST; c++) sb.append(c);
        // U+0080..U+009F (C1 controls) are deliberately skipped: DEFAULT_CHARS bakes them even
        // though they have no printable glyph, which is pure atlas waste.
        for (char c = LATIN1_FIRST; c <= LATIN1_LAST; c++) sb.append(c);
        for (char c = LATIN_EXT_A_FIRST; c <= LATIN_EXT_A_LAST; c++) sb.append(c);
        for (char[] range : SCRIPT_RANGES) {
            for (char c = range[0]; c <= range[1]; c++) {
                // U+0378..U+0383 and U+038B, U+038D, U+03A2 are unassigned: FreeType would
                // bake the missing-glyph box for each.
                if (Character.isDefined(c)) {
                    sb.append(c);
                }
            }
        }
        sb.append(EXTRA_PUNCTUATION);
        return sb.toString();
    }

    private static boolean[] buildLookup() {
        boolean[] renderable = new boolean[LOOKUP_SIZE];
        for (int i = 0; i < BAKED.length(); i++) {
            renderable[BAKED.charAt(i)] = true;
        }
        return renderable;
    }

    /** Whether the generated fonts have a glyph for this character. */
    public static boolean isRenderable(char c) {
        return c < LOOKUP_SIZE && RENDERABLE[c];
    }

    /**
     * Whether the text has letters of an alphabet other than the Latin one - anything the font
     * cannot draw, and the Greek and Cyrillic it can. Map labels are written in Latin letters
     * whatever the place's own script (PoiObject transliterates them), so a name with Greek or
     * Cyrillic in it goes the way an undrawable one always has: to its {@code name:en} or other
     * Latin form, which reads better than a letter-by-letter transliteration.
     */
    public static boolean containsNonLatin(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (!isRenderable(c) || (c >= 0x0370 && c <= 0x052F)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether any character of the text would be drawn as a missing-glyph box — the signal to
     * prefer a name's {@code name:en} / {@code name:latn} variant over its local spelling.
     * Whitespace is ignored: a tab or newline is not baked but never draws a box either.
     */
    public static boolean containsUnrenderable(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c) && !isRenderable(c)) {
                return true;
            }
        }
        return false;
    }
}
