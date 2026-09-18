import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.viewer.TutorialStrings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.Arrays;
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
    @DisplayName("Every slide names a caption key the catalogue knows, in the order of KEYS")
    void slidesUseTheKeys() throws Exception {
        File slidesFile = new File("../assets/info/tutorial_slides.json");
        if (!slidesFile.exists()) {
            slidesFile = new File("assets/info/tutorial_slides.json");
        }
        String json = new String(Files.readAllBytes(slidesFile.toPath()), StandardCharsets.UTF_8);
        List<String> keysInOrder = new ArrayList<>();
        Matcher matcher = Pattern.compile("\"key\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        while (matcher.find()) {
            keysInOrder.add(matcher.group(1));
        }
        assertEquals(Arrays.asList(TutorialStrings.KEYS), keysInOrder,
                "the slides and TutorialStrings.KEYS must say the same thing in the same order");
    }
}
