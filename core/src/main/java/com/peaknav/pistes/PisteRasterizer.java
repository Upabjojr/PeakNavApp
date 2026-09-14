package com.peaknav.pistes;

import com.peaknav.geo.LatLong;
import com.peaknav.pbf.Tag;
import com.peaknav.pbf.Way;
import com.peaknav.roads.RoadClass;
import com.peaknav.roads.RoadFeature;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Draws one tile's downhill ski runs into a texture for the terrain shader, which paints them as
 * fat lines in the colour of their difficulty with a band flowing down each run (see the ski
 * slopes block of {@code assets/fragment_shader.glsl}).
 *
 * <p>Per texel, row 0 at the top as for the roads:
 * <ul>
 *   <li>R, G - the flow phase along the nearest run, as its sine and cosine so a linear filter
 *       blends it across the wrap. It grows downhill: each run is oriented by the terrain's
 *       heights at its two ends, and where those are not known by the way's own direction,
 *       which OpenStreetMap asks mappers to draw downhill. Both 0.5 - no direction - inside a
 *       piste drawn as an area;</li>
 *   <li>B - the difficulty: {@link #BLUE}, {@link #RED} or {@link #BLACK};</li>
 *   <li>A - coverage: 1 along the run's core, ramping to 0 across a soft edge so the shader can
 *       draw a clean outline at any zoom; a flat {@link #AREA_COVERAGE} inside an area.</li>
 * </ul>
 *
 * <p>Only {@code piste:type=downhill} ways are drawn: lifts, nordic loops and ski-touring routes
 * share the extract but are not slopes. Pure: no rendering, so it is covered by unit tests.
 */
public final class PisteRasterizer {

    /** The terrain's height at a point, in metres; NaN where it is not known. */
    public interface Elevation {
        float metres(double lat, double lon);
    }

    /** Difficulty codes, as stored in B: novice and easy runs are blue, intermediate red, the rest black. */
    public static final float BLUE = 0f;
    public static final float RED = 0.5f;
    public static final float BLACK = 1f;

    /** Half the drawn width of a run on the ground, in metres - fat, as on a ski map. */
    static final float HALF_WIDTH_METRES = 14f;
    /** At least this many texels either side, so a run stays visible on a tile far away. */
    static final float MIN_HALF_TEXELS = 1.6f;
    /** Texels over which the coverage ramps down outside the core. */
    static final float FEATHER_TEXELS = 1.5f;
    /** Length of one flowing band down a run, in metres. */
    static final float PATTERN_METRES = 90f;
    /** Coverage stored inside a piste area, whose fill the shader keeps translucent. */
    public static final float AREA_COVERAGE = 0.45f;
    /** A run whose far end is higher than its start by more than this is drawn the other way. */
    static final float CLIMB_TO_REVERSE_METRES = 2f;
    /** Long segments are diced so each chunk's bounding box stays tight. */
    private static final float CHUNK_TEXELS = 24f;
    private static final double METRES_PER_DEGREE = 111320.0;

    /** What a tile came to: its texels, a side, and whether it holds any run at all. */
    public static final class Result {
        public final byte[] rgba;
        public final int res;
        public final boolean empty;

        Result(byte[] rgba, int res, boolean empty) {
            this.rgba = rgba;
            this.res = res;
            this.empty = empty;
        }
    }

    private PisteRasterizer() {
    }

    /** The difficulty code of a downhill run, or null for a way that is not one. */
    public static Float difficultyOf(List<Tag> tags) {
        if (!"downhill".equalsIgnoreCase(pisteValue(tags, "piste:type"))) {
            return null;
        }
        String difficulty = pisteValue(tags, "piste:difficulty");
        if (difficulty == null) {
            return RED; // the most common grade
        }
        switch (difficulty.trim().toLowerCase(Locale.ROOT)) {
            case "novice":
            case "easy":
                return BLUE;
            case "advanced":
            case "expert":
            case "freeride":
            case "extreme":
                return BLACK;
            default:
                return RED;
        }
    }

    /**
     * The downhill runs among {@code ways} as features for the label planner
     * ({@code RoadLabelPlanner.planPistes}): a {@link RoadClass#PISTE} each, its attribute the
     * difficulty code, named from {@code name} or else {@code piste:name}, numbered from
     * {@code piste:ref} or else {@code ref}. Areas are kept but marked, and are not labelled.
     */
    public static List<RoadFeature> labelFeatures(List<Way> ways) {
        List<RoadFeature> out = new ArrayList<>();
        if (ways == null) {
            return out;
        }
        for (Way way : ways) {
            if (way == null || way.latLongs == null) {
                continue;
            }
            Float grade = difficultyOf(way.tags);
            if (grade == null) {
                continue;
            }
            String name = pisteValue(way.tags, "name");
            if (name == null) {
                name = pisteValue(way.tags, "piste:name");
            }
            String number = pisteValue(way.tags, "piste:ref");
            if (number == null) {
                number = pisteValue(way.tags, "ref");
            }
            boolean areaTagged = "yes".equalsIgnoreCase(pisteValue(way.tags, "area"));
            for (LatLong[] line : way.latLongs) {
                if (line == null || line.length < 2 || hasNull(line)) {
                    continue;
                }
                double[] lat = new double[line.length], lon = new double[line.length];
                for (int i = 0; i < line.length; i++) {
                    lat[i] = line[i].latitude;
                    lon[i] = line[i].longitude;
                }
                boolean closed = lat[0] == lat[lat.length - 1] && lon[0] == lon[lon.length - 1];
                out.add(new RoadFeature(RoadClass.PISTE, grade, lat, lon, closed && areaTagged, name, number));
            }
        }
        return out;
    }

    /**
     * Draws the downhill runs among {@code ways} into a {@code res} by {@code res} texture over
     * the box, north up.
     *
     * @param elevation the terrain's heights, to point each run downhill; null to trust the ways
     */
    public static Result rasterize(List<Way> ways, double north, double south, double east, double west,
                                   int res, Elevation elevation) {
        int n = res * res;
        float[] cov = new float[n];
        float[] phase = new float[n];
        float[] difficulty = new float[n];
        boolean[] directed = new boolean[n];
        double dLat = north - south, dLon = east - west;
        if (ways == null || dLat <= 0 || dLon <= 0) {
            return new Result(new byte[n * 4], res, true);
        }
        double metresPerTexel = dLon * METRES_PER_DEGREE * Math.cos(Math.toRadians((north + south) / 2)) / res;
        float halfWidth = Math.max(MIN_HALF_TEXELS, (float) (HALF_WIDTH_METRES / metresPerTexel));
        boolean any = false;
        for (Way way : ways) {
            if (way == null || way.latLongs == null) {
                continue;
            }
            Float grade = difficultyOf(way.tags);
            if (grade == null) {
                continue;
            }
            boolean areaTagged = "yes".equalsIgnoreCase(pisteValue(way.tags, "area"));
            for (LatLong[] line : way.latLongs) {
                if (line == null || line.length < 2 || hasNull(line)) {
                    continue;
                }
                double[] xs = new double[line.length], ys = new double[line.length];
                for (int i = 0; i < line.length; i++) {
                    xs[i] = (line[i].longitude - west) / dLon * res;
                    ys[i] = (north - line[i].latitude) / dLat * res;
                }
                boolean closed = line[0].latitude == line[line.length - 1].latitude
                        && line[0].longitude == line[line.length - 1].longitude;
                if (closed && areaTagged) {
                    any |= fillArea(cov, difficulty, directed, res, xs, ys, grade);
                } else {
                    any |= stampRun(cov, phase, difficulty, directed, res, xs, ys, line, grade, halfWidth, elevation);
                }
            }
        }
        return new Result(any ? encode(cov, phase, difficulty, directed, res) : new byte[n * 4], res, !any);
    }

    /** One run: every texel near it gets its coverage, difficulty and the phase downhill at its nearest point. */
    private static boolean stampRun(float[] cov, float[] phase, float[] difficulty, boolean[] directed, int res,
                                    double[] xs, double[] ys, LatLong[] line, float grade, float halfWidth,
                                    Elevation elevation) {
        int count = line.length;
        double[] along = new double[count];
        for (int i = 1; i < count; i++) {
            along[i] = along[i - 1] + metres(line[i - 1], line[i]);
        }
        double total = along[count - 1];
        boolean reversed = false;
        if (elevation != null) {
            float top = elevation.metres(line[0].latitude, line[0].longitude);
            float bottom = elevation.metres(line[count - 1].latitude, line[count - 1].longitude);
            reversed = !Float.isNaN(top) && !Float.isNaN(bottom) && bottom - top > CLIMB_TO_REVERSE_METRES;
        }
        float influence = halfWidth + FEATHER_TEXELS;
        boolean wrote = false;
        for (int i = 0; i + 1 < count; i++) {
            double ax = xs[i], ay = ys[i], bx = xs[i + 1], by = ys[i + 1];
            if (Math.max(ax, bx) < -influence || Math.min(ax, bx) > res + influence
                    || Math.max(ay, by) < -influence || Math.min(ay, by) > res + influence) {
                continue;
            }
            int chunks = Math.max(1, (int) Math.ceil(Math.hypot(bx - ax, by - ay) / CHUNK_TEXELS));
            for (int c = 0; c < chunks; c++) {
                double t0 = (double) c / chunks, t1 = (double) (c + 1) / chunks;
                double d0 = along[i] + (along[i + 1] - along[i]) * t0;
                double d1 = along[i] + (along[i + 1] - along[i]) * t1;
                if (reversed) {
                    d0 = total - d0;
                    d1 = total - d1;
                }
                wrote |= stampChunk(cov, phase, difficulty, directed, res,
                        (float) (ax + (bx - ax) * t0), (float) (ay + (by - ay) * t0),
                        (float) (ax + (bx - ax) * t1), (float) (ay + (by - ay) * t1),
                        (float) (d0 / PATTERN_METRES), (float) (d1 / PATTERN_METRES),
                        grade, halfWidth, influence);
            }
        }
        return wrote;
    }

    private static boolean stampChunk(float[] cov, float[] phase, float[] difficulty, boolean[] directed, int res,
                                      float ax, float ay, float bx, float by, float phaseA, float phaseB,
                                      float grade, float halfWidth, float influence) {
        int minX = Math.max(0, (int) Math.floor(Math.min(ax, bx) - influence));
        int maxX = Math.min(res - 1, (int) Math.ceil(Math.max(ax, bx) + influence));
        int minY = Math.max(0, (int) Math.floor(Math.min(ay, by) - influence));
        int maxY = Math.min(res - 1, (int) Math.ceil(Math.max(ay, by) + influence));
        float ex = bx - ax, ey = by - ay, lengthSquared = ex * ex + ey * ey;
        boolean wrote = false;
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                float px = x + 0.5f - ax, py = y + 0.5f - ay;
                float t = lengthSquared < 1e-6f ? 0f : Math.max(0f, Math.min(1f, (px * ex + py * ey) / lengthSquared));
                float dx = px - ex * t, dy = py - ey * t;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d >= influence) {
                    continue;
                }
                float c = d <= halfWidth ? 1f : (influence - d) / FEATHER_TEXELS;
                int i = y * res + x;
                // The nearest run wins where two meet; a run always wins over an area's fill.
                if (c > cov[i] || (!directed[i] && c >= AREA_COVERAGE)) {
                    cov[i] = c;
                    phase[i] = phaseA + (phaseB - phaseA) * t;
                    difficulty[i] = grade;
                    directed[i] = true;
                    wrote = true;
                }
            }
        }
        return wrote;
    }

    /** A piste area: the texels whose centres fall inside it, by the even-odd rule, a row at a time. */
    private static boolean fillArea(float[] cov, float[] difficulty, boolean[] directed, int res,
                                    double[] xs, double[] ys, float grade) {
        int count = xs.length;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (double y : ys) {
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        int rowFrom = Math.max(0, (int) Math.floor(minY)), rowTo = Math.min(res - 1, (int) Math.ceil(maxY));
        double[] crossings = new double[count];
        boolean wrote = false;
        for (int row = rowFrom; row <= rowTo; row++) {
            double cy = row + 0.5;
            int k = 0;
            for (int i = 0; i + 1 < count; i++) {
                double y0 = ys[i], y1 = ys[i + 1];
                if ((y0 <= cy && y1 > cy) || (y1 <= cy && y0 > cy)) {
                    crossings[k++] = xs[i] + (cy - y0) / (y1 - y0) * (xs[i + 1] - xs[i]);
                }
            }
            java.util.Arrays.sort(crossings, 0, k);
            for (int j = 0; j + 1 < k; j += 2) {
                int from = Math.max(0, (int) Math.ceil(crossings[j] - 0.5));
                int to = Math.min(res - 1, (int) Math.floor(crossings[j + 1] - 0.5));
                for (int x = from; x <= to; x++) {
                    int i = row * res + x;
                    if (!directed[i] && cov[i] < AREA_COVERAGE) {
                        cov[i] = AREA_COVERAGE;
                        difficulty[i] = grade;
                        wrote = true;
                    }
                }
            }
        }
        return wrote;
    }

    /** Texels beyond a run's or an area's edge that are given its difficulty and phase, uncovered. */
    static final int HALO_TEXELS = 2;

    private static byte[] encode(float[] cov, float[] phase, float[] difficulty, boolean[] directed, int res) {
        dilate(cov, phase, difficulty, directed, res);
        byte[] out = new byte[res * res * 4];
        for (int i = 0; i < res * res; i++) {
            float c = cov[i];
            if (c <= 0f && !halo(difficulty, i)) {
                continue;
            }
            int o = i * 4;
            if (directed[i]) {
                double angle = phase[i] * 2.0 * Math.PI;
                out[o] = unorm((float) (Math.sin(angle) * 0.5 + 0.5));
                out[o + 1] = unorm((float) (Math.cos(angle) * 0.5 + 0.5));
            } else {
                out[o] = unorm(0.5f);
                out[o + 1] = unorm(0.5f);
            }
            out[o + 2] = unorm(difficulty[i]);
            out[o + 3] = unorm(Math.max(0f, Math.min(1f, c)));
        }
        return out;
    }

    /** Marks a halo texel: its coverage stays 0, and a negative difficulty stands for "none". */
    private static boolean halo(float[] difficulty, int i) {
        return difficulty[i] >= 0f;
    }

    /**
     * Spreads each covered texel's difficulty and phase into the uncovered texels around it, a
     * texel at a time, {@link #HALO_TEXELS} times. The texture is filtered linearly, and at a run's
     * edge the shader samples between a covered texel and an empty one: were the empty one left at
     * difficulty 0 (blue) and phase 0, a black run's rim would blend through red and blue, and
     * the flow would jitter. With the halo both sides of the edge agree.
     */
    private static void dilate(float[] cov, float[] phase, float[] difficulty, boolean[] directed, int res) {
        int n = res * res;
        for (int i = 0; i < n; i++) {
            if (cov[i] <= 0f) {
                difficulty[i] = -1f;
            }
        }
        float[] nextDifficulty = new float[n];
        for (int pass = 0; pass < HALO_TEXELS; pass++) {
            System.arraycopy(difficulty, 0, nextDifficulty, 0, n);
            for (int y = 0; y < res; y++) {
                for (int x = 0; x < res; x++) {
                    int i = y * res + x;
                    if (difficulty[i] >= 0f) {
                        continue;
                    }
                    for (int k = 0; k < 4; k++) {
                        int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0), ny = y + (k == 2 ? 1 : k == 3 ? -1 : 0);
                        if (nx < 0 || ny < 0 || nx >= res || ny >= res) {
                            continue;
                        }
                        int j = ny * res + nx;
                        if (difficulty[j] >= 0f) {
                            nextDifficulty[i] = difficulty[j];
                            phase[i] = phase[j];
                            directed[i] = directed[j];
                            break;
                        }
                    }
                }
            }
            System.arraycopy(nextDifficulty, 0, difficulty, 0, n);
        }
    }

    private static boolean hasNull(LatLong[] line) {
        for (LatLong p : line) {
            if (p == null) {
                return true;
            }
        }
        return false;
    }

    private static double metres(LatLong a, LatLong b) {
        double dy = (b.latitude - a.latitude) * METRES_PER_DEGREE;
        double dx = (b.longitude - a.longitude) * METRES_PER_DEGREE * Math.cos(Math.toRadians((a.latitude + b.latitude) / 2));
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static byte unorm(float v) {
        int i = Math.round(v * 255f);
        return (byte) (i < 0 ? 0 : Math.min(255, i));
    }

    /**
     * A piste tag of a way: from its own tags, or else from a piste route it belongs to.
     *
     * <p>The parser appends the tags of every relation a way belongs to after its own, each
     * group starting at its {@code type} tag. A route's members often carry nothing themselves,
     * so a route lends them its piste tags. A multipolygon does not: its members are the outline
     * of a piste area, and read as runs they were drawn as lines along the area's edge - in red,
     * the default for a missing grade, around every black run whose area was mapped that way.
     */
    static String pisteValue(List<Tag> tags, String key) {
        if (tags == null) {
            return null;
        }
        int ownEnd = nextGroup(tags, 0);
        String own = valueIn(tags, 0, ownEnd, key);
        if (own != null) {
            return own;
        }
        for (int from = ownEnd; from < tags.size(); from = nextGroup(tags, from + 1)) {
            int to = nextGroup(tags, from + 1);
            if ("route".equalsIgnoreCase(valueIn(tags, from, to, "type"))) {
                String lent = valueIn(tags, from, to, key);
                if (lent != null) {
                    return lent;
                }
            }
        }
        return null;
    }

    /** Where the next group of tags starts: the next {@code type} tag at or after {@code from}. */
    private static int nextGroup(List<Tag> tags, int from) {
        for (int i = from; i < tags.size(); i++) {
            if (tags.get(i) != null && "type".equals(tags.get(i).key)) {
                return i;
            }
        }
        return tags.size();
    }

    private static String valueIn(List<Tag> tags, int from, int to, String key) {
        for (int i = from; i < to && i < tags.size(); i++) {
            Tag tag = tags.get(i);
            if (tag != null && key.equals(tag.key) && tag.value != null && !tag.value.isEmpty()) {
                return tag.value;
            }
        }
        return null;
    }

    private static String value(List<Tag> tags, String key) {
        if (tags == null) {
            return null;
        }
        for (Tag tag : tags) {
            if (key.equals(tag.key)) {
                return tag.value;
            }
        }
        return null;
    }
}
