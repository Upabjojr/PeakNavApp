import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.viewer.TutorialStrings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * The tutorial's captions: every key translated in every language, and the JavaScript
 * the platforms inject built safely from prose that contains apostrophes and accents.
 */
class TestTutorialStrings {

    private static final String[] LANGUAGES = {"en", "it", "fr", "de", "es", "pt", "no"};

    private static Properties strings(String language) throws Exception {
        File file = new File("../assets/i18n/strings_" + language + ".properties");
        if (!file.exists()) {
            file = new File("assets/i18n/strings_" + language + ".properties");
        }
        Properties p = new Properties();
        p.load(new java.io.StringReader(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)));
        return p;
    }

    @Test
    @DisplayName("Every caption and explanation is translated in all seven languages")
    void everyKeyIsTranslatedEverywhere() throws Exception {
        List<String> missing = new ArrayList<>();
        for (String language : LANGUAGES) {
            Properties p = strings(language);
            for (String key : TutorialStrings.KEYS) {
                for (String full : new String[]{key, key + "_detail"}) {
                    String value = p.getProperty(full);
                    if (value == null || value.trim().isEmpty()) {
                        missing.add(language + ":" + full);
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), "untranslated tutorial captions: " + missing);
    }

    @Test
    @DisplayName("The English captions are not accidentally copied into the other languages")
    void translationsAreNotJustEnglish() throws Exception {
        Properties english = strings("en");
        for (String language : new String[]{"it", "fr", "de", "es", "pt", "no"}) {
            Properties p = strings(language);
            int same = 0;
            for (String key : TutorialStrings.KEYS) {
                if (english.getProperty(key + "_detail").equals(p.getProperty(key + "_detail"))) {
                    same++;
                }
            }
            // A word like "Gyroscope" may legitimately match; a whole sentence should not.
            assertTrue(same <= 2, language + " repeats " + same + " English explanations verbatim");
        }
    }

    @Test
    @DisplayName("A string with quotes, backslashes and accents survives into JavaScript")
    void quotingIsSafe() {
        assertEquals("\"plain\"", TutorialStrings.quote("plain"));
        assertEquals("\"say \\\"hi\\\"\"", TutorialStrings.quote("say \"hi\""));
        assertEquals("\"a\\\\b\"", TutorialStrings.quote("a\\b"));
        assertEquals("\"line\\nbreak\"", TutorialStrings.quote("line\nbreak"));
        // Accented prose must not go through raw: the page is handed over as a string by
        // three platforms and only one of them controls the encoding.
        assertEquals("\"caf\\u00e9\"", TutorialStrings.quote("caf\u00e9"));
        assertTrue(TutorialStrings.quote("l'altitude").contains("l'altitude"),
                "an apostrophe is fine inside double quotes");
    }

    @Test
    @DisplayName("The injected snippet defines get_string and mentions every key")
    void snippetShape() {
        String js = TutorialStrings.asJavaScript();
        assertTrue(js.startsWith("function get_string(k) {"), js.substring(0, Math.min(40, js.length())));
        assertTrue(js.contains("return (k in t) ? t[k] : k;"), "must fall back to the key");
        for (String key : TutorialStrings.KEYS) {
            assertTrue(js.contains("\"" + key + "\""), "no entry for " + key);
            assertTrue(js.contains("\"" + key + "_detail\""), "no entry for " + key + "_detail");
        }
        // Balanced quotes: an unescaped one would blank the page rather than fail loudly.
        int quotes = 0;
        for (int i = 0; i < js.length(); i++) {
            if (js.charAt(i) == '"' && (i == 0 || js.charAt(i - 1) != '\\')) {
                quotes++;
            }
        }
        assertEquals(0, quotes % 2, "unbalanced quotes in the generated JavaScript");
    }

    @Test
    @DisplayName("The page asks for its captions by key and carries the injection point")
    void pageUsesTheKeys() throws Exception {
        File page = new File("../assets/info/app_tutorial.html");
        if (!page.exists()) {
            page = new File("assets/info/app_tutorial.html");
        }
        String html = new String(Files.readAllBytes(page.toPath()), StandardCharsets.UTF_8);
        assertTrue(html.contains("// OVERLOAD::get_string"), "the platforms need this marker");
        assertTrue(html.contains("get_string(slide.key)"), "captions must come from the catalogue");
        assertTrue(html.contains("get_string(slide.key + \"_detail\")"), "explanations too");
    }
}
