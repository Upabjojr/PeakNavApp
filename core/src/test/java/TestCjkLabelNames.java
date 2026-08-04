import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.utils.CjkLabelNames;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The choice of Latin label for names the map font cannot draw.
 *
 * <p>The rule under test exists because of one bug: ICU's generic transliteration reads
 * kanji as Chinese, so every Japanese mountain without a Latin name in the data was
 * labelled with pinyin - 高座山, Takazasu-yama, appeared as "gao zuo shan". A kanji's
 * Japanese reading is a property of the word, not the character; when the data does not
 * supply it, there is nothing true to print.
 */
class TestCjkLabelNames {

    private static String best(String name, String en, String jaRm, String latn,
                               String hira, boolean jaTag, float lat, float lon) {
        return CjkLabelNames.bestLatinName(name, en, jaRm, latn, hira, jaTag, lat, lon);
    }

    @Test
    @DisplayName("Latin names from the data win, in order of how Japanese they are")
    void prefersDataNames() {
        assertEquals("Mount Mikuni",
                best("三国山", "Mount Mikuni", "mikuni-yama", null, "みくにやま", true, 35.4f, 138.9f),
                "name:en first - it is what an international user typed into OSM");
        assertEquals("Myoujin-yama",
                best("明神山", null, "myoujin-yama", null, null, true, 35.4f, 138.9f),
                "rōmaji from the mapper, capitalised for display");
        assertEquals("Fuji-san",
                best("富士山", null, null, "Fuji-san", null, true, 35.36f, 138.73f),
                "a name:*-Latn variant serves when there is no rōmaji tag");
    }

    @Test
    @DisplayName("a hiragana reading romanizes correctly - kana, unlike kanji, spell sounds")
    void romanizesHiragana() {
        String label = best("三国山", null, null, null, "みくにやま", true, 35.4f, 138.9f);
        assertEquals("Mikuniyama", label);
    }

    @Test
    @DisplayName("kanji-only Japanese names with no reading anywhere get no label at all")
    void suppressesUnreadableJapanese() {
        // 高座山 near Fuji: only a name tag. This was "gao zuo shan" on screen.
        assertNull(best("高座山", null, null, null, null, false, 35.43f, 138.85f),
                "in Japan by coordinates, kanji-only: no honest Latin form exists");
        assertNull(best("高座山", null, null, null, null, true, 0f, 0f),
                "a *:ja tag marks it Japanese wherever it is");
        // Kanji mixed with kana is Japanese by script alone - and still unreadable,
        // because the kanji part would come out as pinyin spliced into romaji.
        assertNull(best("三ツ峠山", null, null, null, null, false, 35.55f, 138.8f));
    }

    @Test
    @DisplayName("a fully-kana name needs no tags and no dictionary")
    void kanaOnlyNamesRomanize() {
        String label = best("エベレスト", null, null, null, null, false, 27.99f, 86.93f);
        assertFalse(label == null || label.isEmpty());
        assertTrue(label.matches("[A-Za-z].*"), "kana romanize wherever they are: " + label);
    }

    @Test
    @DisplayName("Chinese and Korean names pass through to their own correct romanizations")
    void leavesChineseAndKoreanAlone() {
        assertEquals("泰山", best("泰山", null, null, null, null, false, 36.25f, 117.1f),
                "Han in China passes through - pinyin is right there");
        assertEquals("한라산", best("한라산", null, null, null, null, false, 33.36f, 126.53f),
                "Hangul is not Han; the generic romanization handles it");
    }

    @Test
    @DisplayName("the Japan boxes exclude the near misses on the mainland side")
    void japanBoxesAreDrawnTight() {
        assertTrue(CjkLabelNames.isInJapan(35.36f, 138.73f), "Fuji");
        assertTrue(CjkLabelNames.isInJapan(43.06f, 141.35f), "Sapporo");
        assertTrue(CjkLabelNames.isInJapan(26.5f, 127.9f), "Okinawa");
        assertFalse(CjkLabelNames.isInJapan(35.10f, 129.04f), "Busan");
        assertFalse(CjkLabelNames.isInJapan(33.36f, 126.53f), "Hallasan, Jeju");
        assertFalse(CjkLabelNames.isInJapan(25.04f, 121.51f), "Taipei");
        assertFalse(CjkLabelNames.isInJapan(41.8f, 123.4f), "Shenyang");
        assertFalse(CjkLabelNames.isInJapan(48.0f, 135.0f), "Khabarovsk");
    }

    @Test
    @DisplayName("script detection: kana and Han recognised, everything else not")
    void detectsScripts() {
        assertTrue(CjkLabelNames.containsKana("三ツ峠山"), "the ツ is katakana");
        assertTrue(CjkLabelNames.containsKana("みくにやま"));
        assertFalse(CjkLabelNames.containsKana("高座山"), "kanji only");
        assertTrue(CjkLabelNames.containsHan("高座山"));
        assertFalse(CjkLabelNames.containsHan("한라산"), "Hangul is not Han");
        assertFalse(CjkLabelNames.containsHan("Matterhorn"));
    }
}
