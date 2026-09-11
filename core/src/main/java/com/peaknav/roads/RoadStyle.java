package com.peaknav.roads;

import static com.peaknav.utils.Constants.PREFERENCES.VIEWER_ROAD_COLOR_ROADS;
import static com.peaknav.utils.Constants.PREFERENCES.VIEWER_ROAD_COLOR_TRACKS;
import static com.peaknav.utils.Constants.PREFERENCES.VIEWER_ROAD_COLOR_TRAILS_ALPINE;
import static com.peaknav.utils.Constants.PREFERENCES.VIEWER_ROAD_COLOR_TRAILS_EASY;
import static com.peaknav.utils.Constants.PREFERENCES.VIEWER_ROAD_COLOR_TRAILS_MOUNTAIN;
import static com.peaknav.utils.Constants.PREFERENCES.VIEWER_ROAD_DASH_COUNT;
import static com.peaknav.utils.Constants.PREFERENCES.VIEWER_ROAD_DASH_SPEED;
import static com.peaknav.utils.Constants.PREFERENCES.VIEWER_ROAD_LABEL_FREQUENCY;
import static com.peaknav.utils.Constants.PREFERENCES.VIEWER_ROAD_NAMES;

import com.badlogic.gdx.Preferences;

/**
 * How the roads and trails look: a colour for each kind of way, the density and speed of the
 * trail dashes, and whether their names are written on the map. All of it reaches the terrain
 * shader as uniforms, so a change shows on the next frame without redrawing a single tile.
 *
 * <p>The defaults follow the waymarks hikers already know. Trails are graded the Swiss way -
 * yellow for hiking, red for mountain hiking, blue for alpine routes - and dashed, as on every
 * paper trail map; roads are a warm white with a dark outline, which reads on satellite imagery
 * and on the white relief alike; tracks are ochre, the colour of the dirt they are made of.
 */
public final class RoadStyle {

    /** A colour the user can change, and the preference it is kept under. */
    public enum Swatch {
        ROADS(VIEWER_ROAD_COLOR_ROADS, 0xFFF4D6FF),
        TRACKS(VIEWER_ROAD_COLOR_TRACKS, 0xD98C3AFF),
        TRAILS_EASY(VIEWER_ROAD_COLOR_TRAILS_EASY, 0xFFD02EFF),
        TRAILS_MOUNTAIN(VIEWER_ROAD_COLOR_TRAILS_MOUNTAIN, 0xE8322BFF),
        TRAILS_ALPINE(VIEWER_ROAD_COLOR_TRAILS_ALPINE, 0x2E7BF0FF);

        public final String key;
        /** RGBA8888. */
        public final int defaultColor;

        Swatch(String key, int defaultColor) {
            this.key = key;
            this.defaultColor = defaultColor;
        }
    }

    /**
     * What tapping a colour cycles through: saturated enough to stand out on satellite imagery,
     * distinct enough from each other to tell apart at a glance. RGBA8888.
     */
    public static final int[] PALETTE = {
            0xFFFFFFFF, // white
            0xFFF4D6FF, // warm white
            0xFFD02EFF, // yellow
            0xFF8A1FFF, // orange
            0xD98C3AFF, // ochre
            0x9A5B2EFF, // brown
            0xE8322BFF, // red
            0xE0338FFF, // magenta
            0x8E4FE0FF, // violet
            0x2E7BF0FF, // blue
            0x1FC8E0FF, // cyan
            0x3DBE4BFF, // green
            0x1C1C1CFF, // black
    };

    public static final int DASH_COUNT_MIN = 1;
    public static final int DASH_COUNT_MAX = 8;
    /** Four dashes per 240 m turn of the phase: a 60 m cycle, about a paper map's dash on the ground. */
    public static final int DASH_COUNT_DEFAULT = 4;
    public static final float DASH_SPEED_MAX = 1.5f;
    /** Cycles per second: the dashes creep along, enough to catch the eye, not to distract. */
    public static final float DASH_SPEED_DEFAULT = 0.35f;
    /** The share of each cycle that is dash rather than gap. */
    public static final float DASH_DUTY = 0.58f;

    /**
     * How often names and numbers are written along roads and trails, fewest to most. Each step
     * keeps every eighth, fourth, second or single label spot the planner lays out (see
     * {@link RoadLabelCandidate#label(int)}), and allows more labels on screen at once.
     */
    public static final int LABEL_FREQUENCY_MIN = 0;
    public static final int LABEL_FREQUENCY_MAX = 3;
    /** A trail label every 300 m and a road name every 1.2 km. */
    public static final int LABEL_FREQUENCY_DEFAULT = 2;
    private static final int[] LABEL_STRIDES = {8, 4, 2, 1};
    private static final int[] MAX_LABELS = {16, 28, 40, 56};

    private final int[] colors = new int[Swatch.values().length];
    private volatile int dashCount = DASH_COUNT_DEFAULT;
    private volatile float dashSpeed = DASH_SPEED_DEFAULT;
    private volatile boolean roadNames = true;
    private volatile int labelFrequency = LABEL_FREQUENCY_DEFAULT;

    public RoadStyle() {
        resetColors();
    }

    /** Reads every setting, falling back to the defaults for any not stored yet. */
    public void load(Preferences preferences) {
        for (Swatch s : Swatch.values()) {
            colors[s.ordinal()] = preferences.getInteger(s.key, s.defaultColor);
        }
        setDashCount(preferences.getInteger(VIEWER_ROAD_DASH_COUNT, DASH_COUNT_DEFAULT));
        setDashSpeed(preferences.getFloat(VIEWER_ROAD_DASH_SPEED, DASH_SPEED_DEFAULT));
        roadNames = preferences.getBoolean(VIEWER_ROAD_NAMES, true);
        setLabelFrequency(preferences.getInteger(VIEWER_ROAD_LABEL_FREQUENCY, LABEL_FREQUENCY_DEFAULT));
    }

    /** Writes every setting. The caller flushes. */
    public void save(Preferences preferences) {
        for (Swatch s : Swatch.values()) {
            preferences.putInteger(s.key, colors[s.ordinal()]);
        }
        preferences.putInteger(VIEWER_ROAD_DASH_COUNT, dashCount);
        preferences.putFloat(VIEWER_ROAD_DASH_SPEED, dashSpeed);
        preferences.putBoolean(VIEWER_ROAD_NAMES, roadNames);
        preferences.putInteger(VIEWER_ROAD_LABEL_FREQUENCY, labelFrequency);
    }

    public int color(Swatch swatch) {
        return colors[swatch.ordinal()];
    }

    public void setColor(Swatch swatch, int rgba8888) {
        colors[swatch.ordinal()] = rgba8888;
    }

    /**
     * Moves the swatch to the next colour of the {@link #PALETTE}, wrapping round; a colour
     * that is not in the palette (set some other way) moves to the first one.
     *
     * @return the new colour
     */
    public int cycleColor(Swatch swatch) {
        int current = colors[swatch.ordinal()];
        int next = PALETTE[0];
        for (int i = 0; i < PALETTE.length; i++) {
            if (PALETTE[i] == current) {
                next = PALETTE[(i + 1) % PALETTE.length];
                break;
            }
        }
        colors[swatch.ordinal()] = next;
        return next;
    }

    public void resetColors() {
        for (Swatch s : Swatch.values()) {
            colors[s.ordinal()] = s.defaultColor;
        }
    }

    /** Dashes per {@link RoadTileRasterizer#DASH_BASE_METERS} of trail. */
    public int dashCount() {
        return dashCount;
    }

    public void setDashCount(int count) {
        dashCount = Math.max(DASH_COUNT_MIN, Math.min(DASH_COUNT_MAX, count));
    }

    /** Length of one dash-and-gap cycle on the ground, in metres. */
    public float dashPeriodMeters() {
        return RoadTileRasterizer.DASH_BASE_METERS / dashCount;
    }

    /** How fast the dashes move along the trail, in cycles per second; 0 holds them still. */
    public float dashSpeed() {
        return dashSpeed;
    }

    public void setDashSpeed(float speed) {
        if (Float.isNaN(speed)) {
            speed = DASH_SPEED_DEFAULT;
        }
        dashSpeed = Math.max(0f, Math.min(DASH_SPEED_MAX, speed));
    }

    /** Whether the names of streets, tracks and trails are written along them. */
    public boolean isRoadNames() {
        return roadNames;
    }

    public void setRoadNames(boolean visible) {
        roadNames = visible;
    }

    /** How often labels are written, {@link #LABEL_FREQUENCY_MIN} to {@link #LABEL_FREQUENCY_MAX}. */
    public int labelFrequency() {
        return labelFrequency;
    }

    public void setLabelFrequency(int frequency) {
        labelFrequency = Math.max(LABEL_FREQUENCY_MIN, Math.min(LABEL_FREQUENCY_MAX, frequency));
    }

    /** Of the planner's label spots, every this many is used. */
    public int labelStride() {
        return LABEL_STRIDES[labelFrequency];
    }

    /** At most this many road and trail labels on screen at once. */
    public int maxLabels() {
        return MAX_LABELS[labelFrequency];
    }

    /**
     * The colour of a trail of this difficulty ({@link RoadFeature} TRAIL_* constants): the
     * swatch its grade is drawn with, as the user has set it. RGBA8888.
     */
    public int trailColor(float difficulty) {
        if (difficulty >= 0.75f) {
            return color(Swatch.TRAILS_ALPINE);
        }
        return difficulty >= 0.25f ? color(Swatch.TRAILS_MOUNTAIN) : color(Swatch.TRAILS_EASY);
    }

    /**
     * Whether text written on a plate of this colour should be dark rather than white: dark on
     * yellow, ochre, white and the other light colours, white on red, blue, violet and black.
     */
    public static boolean prefersDarkText(int rgba8888) {
        return luminance(rgba8888) > 0.5f;
    }

    /**
     * The outline drawn around a line of this colour: dark around a light colour, light around
     * a dark one, so any colour the user picks keeps an edge against the terrain. RGBA8888.
     */
    public static int casingFor(int rgba8888) {
        float r = ((rgba8888 >>> 24) & 0xFF) / 255f;
        float g = ((rgba8888 >>> 16) & 0xFF) / 255f;
        float b = ((rgba8888 >>> 8) & 0xFF) / 255f;
        if (luminance(rgba8888) > 0.45f) {
            return pack(r * 0.22f, g * 0.22f, b * 0.22f, 0.9f);
        }
        return pack(r + (1f - r) * 0.8f, g + (1f - g) * 0.8f, b + (1f - b) * 0.8f, 0.85f);
    }

    /** Relative luminance, Rec. 709 weights over the stored (gamma-encoded) values. */
    public static float luminance(int rgba8888) {
        float r = ((rgba8888 >>> 24) & 0xFF) / 255f;
        float g = ((rgba8888 >>> 16) & 0xFF) / 255f;
        float b = ((rgba8888 >>> 8) & 0xFF) / 255f;
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    static int pack(float r, float g, float b, float a) {
        return (clampByte(r) << 24) | (clampByte(g) << 16) | (clampByte(b) << 8) | clampByte(a);
    }

    private static int clampByte(float v) {
        int i = Math.round(v * 255f);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }
}
