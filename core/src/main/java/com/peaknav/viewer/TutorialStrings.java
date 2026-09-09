package com.peaknav.viewer;

import static com.peaknav.utils.PeakNavUtils.s;

/**
 * The captions of the "?" tutorial, translated through the app's own catalogue.
 *
 * <p>The page ({@code assets/info/app_tutorial.html}) names each slide by key and asks
 * {@code get_string(key)} for the words, exactly as it asks {@code get_image(name)} for
 * the screenshots. Each platform overloads that function before handing the page to its
 * web view, with {@link #asJavaScript()} below - so there is one list of keys, one set of
 * translations in {@code assets/i18n/strings_*.properties}, and no English baked into the
 * HTML.
 *
 * <p>{@link #KEYS} is the whole contract: a slide uses {@code <key>} for its caption and
 * {@code <key>_detail} for the line under it. The slide order lives with the script that
 * writes the page, {@code tools/tutorial_screenshots.py}.
 */
public final class TutorialStrings {

    /** Every caption key the tutorial can ask for, in slide order. */
    public static final String[] KEYS = {
            "Tutorial_gyroscope",
            "Tutorial_elevation",
            "Tutorial_gallery",
            "Tutorial_camera",
            "Tutorial_search",
            "Tutorial_options",
            "Tutorial_options_pane",
            "Tutorial_satellite",
            "Tutorial_share",
            "Tutorial_here",
            "Tutorial_tap",
            "Tutorial_gpx",
            "Tutorial_photo_match",
            "Tutorial_photo_outlines",
            "Tutorial_photo_terrain",
            "Tutorial_photo_pin",
            "Tutorial_photo_close",
    };

    private TutorialStrings() {
    }

    /**
     * The {@code get_string} the page expects, filled in with this device's language:
     * a lookup table of key to translated text, falling back to the key itself so a
     * missing translation shows something rather than nothing.
     *
     * <p>Substitute it for the {@code // OVERLOAD::get_string} line of the page.
     */
    public static String asJavaScript() {
        return asJavaScript(new Lookup() {
            @Override
            public String get(String key) {
                try {
                    return s(key);
                } catch (RuntimeException noCatalogue) {
                    // No app around it (a unit test, a tool): the key is better than a crash.
                    return key;
                }
            }
        });
    }

    /** Where the words come from; the app's catalogue in earnest, something simpler in a test. */
    public interface Lookup {
        String get(String key);
    }

    /** As {@link #asJavaScript()}, with the translations taken from {@code lookup}. */
    public static String asJavaScript(Lookup lookup) {
        StringBuilder js = new StringBuilder("function get_string(k) {\n  var t = {\n");
        for (String key : KEYS) {
            appendEntry(js, key, lookup);
            appendEntry(js, key + "_detail", lookup);
        }
        js.append("  };\n  return (k in t) ? t[k] : k;\n}\n");
        return js.toString();
    }

    private static void appendEntry(StringBuilder js, String key, Lookup lookup) {
        String value = lookup.get(key);
        js.append("    ").append(quote(key)).append(": ")
                .append(quote(value == null ? key : value)).append(",\n");
    }

    /**
     * A JavaScript string literal. The translations are ordinary prose - apostrophes in
     * French, quotation marks, the odd backslash - and one unescaped character would be a
     * syntax error that blanks the whole page, so this is not optional.
     */
    public static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    // Keep it ASCII-safe: the page is handed over as a string by three
                    // different platforms, and only one of them controls the encoding.
                    if (c < 0x20 || c > 0x7E) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.append('"').toString();
    }
}
