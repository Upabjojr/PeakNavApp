package com.peaknav.utils;

/**
 * The set of characters baked into every font atlas, and the test for whether a string can
 * actually be drawn with it.
 *
 * <p>The fonts are bitmap atlases generated once at startup from Liberation Sans (see
 * {@code StyleSingleton.generateAllFonts}): a character that was not baked has no glyph and is
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
 * labels actually need. To support a new script (Greek and Cyrillic are both present in Liberation
 * Sans), add its range in {@link #build()} — everything else, including the fallback to a name's
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
            "–—‘’“”•…€←↑→↓";

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
        sb.append(EXTRA_PUNCTUATION);
        return sb.toString();
    }

    private static boolean[] buildLookup() {
        boolean[] renderable = new boolean[LATIN_EXT_A_LAST + 1];
        for (int i = 0; i < BAKED.length(); i++) {
            char c = BAKED.charAt(i);
            if (c <= LATIN_EXT_A_LAST) {
                renderable[c] = true;
            }
        }
        return renderable;
    }

    /** Whether the generated fonts have a glyph for this character. */
    public static boolean isRenderable(char c) {
        if (c <= LATIN_EXT_A_LAST) {
            return RENDERABLE[c];
        }
        return EXTRA_PUNCTUATION.indexOf(c) >= 0;
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
