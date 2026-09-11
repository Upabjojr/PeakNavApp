import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.Preferences;
import com.peaknav.roads.RoadStyle;
import com.peaknav.roads.RoadStyle.Swatch;
import com.peaknav.roads.RoadTileRasterizer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** The road and trail colours, dashes and names the user sets in the options menu. */
class TestRoadStyle {

    /** A preferences store that keeps everything in a map. */
    private static final class MapPreferences implements Preferences {
        final Map<String, Object> values = new HashMap<>();

        @Override public Preferences putBoolean(String k, boolean v) { values.put(k, v); return this; }
        @Override public Preferences putInteger(String k, int v) { values.put(k, v); return this; }
        @Override public Preferences putLong(String k, long v) { values.put(k, v); return this; }
        @Override public Preferences putFloat(String k, float v) { values.put(k, v); return this; }
        @Override public Preferences putString(String k, String v) { values.put(k, v); return this; }
        @Override public Preferences put(Map<String, ?> v) { values.putAll(v); return this; }
        @Override public boolean getBoolean(String k) { return getBoolean(k, false); }
        @Override public int getInteger(String k) { return getInteger(k, 0); }
        @Override public long getLong(String k) { return getLong(k, 0L); }
        @Override public float getFloat(String k) { return getFloat(k, 0f); }
        @Override public String getString(String k) { return getString(k, ""); }
        @Override public boolean getBoolean(String k, boolean d) {
            return values.containsKey(k) ? (Boolean) values.get(k) : d;
        }
        @Override public int getInteger(String k, int d) {
            return values.containsKey(k) ? (Integer) values.get(k) : d;
        }
        @Override public long getLong(String k, long d) {
            return values.containsKey(k) ? (Long) values.get(k) : d;
        }
        @Override public float getFloat(String k, float d) {
            return values.containsKey(k) ? (Float) values.get(k) : d;
        }
        @Override public String getString(String k, String d) {
            return values.containsKey(k) ? (String) values.get(k) : d;
        }
        @Override public Map<String, ?> get() { return values; }
        @Override public boolean contains(String k) { return values.containsKey(k); }
        @Override public void clear() { values.clear(); }
        @Override public void remove(String k) { values.remove(k); }
        @Override public void flush() { }
    }

    @Test
    @DisplayName("a fresh install grades trails yellow, red and blue, dashed and gently moving")
    void defaults() {
        RoadStyle style = new RoadStyle();
        style.load(new MapPreferences());
        assertEquals(0xFFD02EFF, style.color(Swatch.TRAILS_EASY));
        assertEquals(0xE8322BFF, style.color(Swatch.TRAILS_MOUNTAIN));
        assertEquals(0x2E7BF0FF, style.color(Swatch.TRAILS_ALPINE));
        assertEquals(RoadStyle.DASH_COUNT_DEFAULT, style.dashCount());
        assertEquals(60f, style.dashPeriodMeters(), 1e-4f);
        assertTrue(style.dashSpeed() > 0f, "the dashes move by default");
        assertTrue(style.isRoadNames());
    }

    @Test
    @DisplayName("every setting survives a restart")
    void persists() {
        MapPreferences prefs = new MapPreferences();
        RoadStyle style = new RoadStyle();
        style.setColor(Swatch.ROADS, 0x3DBE4BFF);
        style.setDashCount(7);
        style.setDashSpeed(0f);
        style.setRoadNames(false);
        style.save(prefs);

        RoadStyle reloaded = new RoadStyle();
        reloaded.load(prefs);
        assertEquals(0x3DBE4BFF, reloaded.color(Swatch.ROADS));
        assertEquals(style.color(Swatch.TRACKS), reloaded.color(Swatch.TRACKS));
        assertEquals(7, reloaded.dashCount());
        assertEquals(0f, reloaded.dashSpeed(), 0f);
        assertFalse(reloaded.isRoadNames());
    }

    @Test
    @DisplayName("tapping a colour walks the whole palette and comes back round")
    void cycling() {
        RoadStyle style = new RoadStyle();
        int start = style.color(Swatch.TRAILS_MOUNTAIN);
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < RoadStyle.PALETTE.length; i++) {
            seen.add(style.cycleColor(Swatch.TRAILS_MOUNTAIN));
        }
        assertEquals(RoadStyle.PALETTE.length, seen.size(), "every palette colour, once");
        assertEquals(start, style.color(Swatch.TRAILS_MOUNTAIN), "and back to where it began");

        style.setColor(Swatch.ROADS, 0x12345678);
        assertEquals(RoadStyle.PALETTE[0], style.cycleColor(Swatch.ROADS),
                "a colour from outside the palette moves to its start");
        style.resetColors();
        assertEquals(Swatch.ROADS.defaultColor, style.color(Swatch.ROADS));
    }

    @Test
    @DisplayName("dash count and speed stay within what the shader can draw")
    void clamps() {
        RoadStyle style = new RoadStyle();
        style.setDashCount(0);
        assertEquals(RoadStyle.DASH_COUNT_MIN, style.dashCount());
        style.setDashCount(99);
        assertEquals(RoadStyle.DASH_COUNT_MAX, style.dashCount());
        assertEquals(RoadTileRasterizer.DASH_BASE_METERS / RoadStyle.DASH_COUNT_MAX,
                style.dashPeriodMeters(), 1e-4f);
        style.setDashSpeed(-1f);
        assertEquals(0f, style.dashSpeed(), 0f);
        style.setDashSpeed(10f);
        assertEquals(RoadStyle.DASH_SPEED_MAX, style.dashSpeed(), 0f);
        style.setDashSpeed(Float.NaN);
        assertEquals(RoadStyle.DASH_SPEED_DEFAULT, style.dashSpeed(), 0f);
    }

    @Test
    @DisplayName("label frequency: four steps, the third by default, kept across a restart")
    void labelFrequency() {
        RoadStyle style = new RoadStyle();
        assertEquals(RoadStyle.LABEL_FREQUENCY_DEFAULT, style.labelFrequency());
        assertEquals(2, style.labelStride(), "by default every second planned spot");
        style.setLabelFrequency(RoadStyle.LABEL_FREQUENCY_MIN);
        assertEquals(8, style.labelStride());
        int fewest = style.maxLabels();
        style.setLabelFrequency(RoadStyle.LABEL_FREQUENCY_MAX);
        assertEquals(1, style.labelStride());
        assertTrue(style.maxLabels() > fewest, "more labels allowed as the frequency rises");
        style.setLabelFrequency(99);
        assertEquals(RoadStyle.LABEL_FREQUENCY_MAX, style.labelFrequency());
        style.setLabelFrequency(-5);
        assertEquals(RoadStyle.LABEL_FREQUENCY_MIN, style.labelFrequency());

        MapPreferences prefs = new MapPreferences();
        style.setLabelFrequency(1);
        style.save(prefs);
        RoadStyle reloaded = new RoadStyle();
        reloaded.load(prefs);
        assertEquals(1, reloaded.labelFrequency());
    }

    @Test
    @DisplayName("a trail's plate takes its grade's colour, as the user set it")
    void trailColours() {
        RoadStyle style = new RoadStyle();
        assertEquals(style.color(Swatch.TRAILS_EASY), style.trailColor(0f));
        assertEquals(style.color(Swatch.TRAILS_MOUNTAIN), style.trailColor(0.5f));
        assertEquals(style.color(Swatch.TRAILS_ALPINE), style.trailColor(1f));
        style.setColor(Swatch.TRAILS_MOUNTAIN, 0x8E4FE0FF);
        assertEquals(0x8E4FE0FF, style.trailColor(0.5f));
    }

    @Test
    @DisplayName("text on a plate is dark on the light colours and white on the dark ones")
    void textOnPlates() {
        assertTrue(RoadStyle.prefersDarkText(0xFFD02EFF), "yellow");
        assertTrue(RoadStyle.prefersDarkText(0xD98C3AFF), "ochre");
        assertTrue(RoadStyle.prefersDarkText(0xFFFFFFFF), "white");
        assertFalse(RoadStyle.prefersDarkText(0xE8322BFF), "red");
        assertFalse(RoadStyle.prefersDarkText(0x2E7BF0FF), "blue");
        assertFalse(RoadStyle.prefersDarkText(0x1C1C1CFF), "black");
    }

    @Test
    @DisplayName("any colour keeps an outline: dark around light colours, light around dark ones")
    void casing() {
        for (int color : RoadStyle.PALETTE) {
            float contrast = Math.abs(RoadStyle.luminance(color)
                    - RoadStyle.luminance(RoadStyle.casingFor(color)));
            assertTrue(contrast > 0.3f, String.format("outline of %08X too close to it", color));
        }
        assertTrue(RoadStyle.luminance(RoadStyle.casingFor(0xFFFFFFFF)) < 0.3f);
        assertTrue(RoadStyle.luminance(RoadStyle.casingFor(0x1C1C1CFF)) > 0.7f);
    }
}
