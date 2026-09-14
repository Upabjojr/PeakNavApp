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
 * Draws one tile's ski lifts into a texture for the terrain shader, which paints each as its cable
 * with carriers travelling uphill along it - cabins for a cable car or a gondola, chairs for a
 * chairlift, handles on a dashed line for a drag lift, moving stripes for a magic carpet (see the
 * lifts block of {@code assets/fragment_shader.glsl}).
 *
 * <p>Per texel, row 0 at the top:
 * <ul>
 *   <li>R, G - the travel phase along the nearest lift, one turn every {@link #PATTERN_METRES},
 *       as sine and cosine, growing uphill: oriented by the terrain's heights at the lift's ends,
 *       else by the way's own direction, which for a lift is its direction of travel;</li>
 *   <li>B - the kind of lift, {@link #code(Kind)};</li>
 *   <li>A - 1 on the lift's line, falling linearly to 0 at {@link #reachTexels} from it: a
 *       distance field, so the shader can draw a thin cable and wider carriers from the one
 *       texture.</li>
 * </ul>
 *
 * <p>Goods lifts, zip lines, pylons, stations and lifts abandoned or only proposed are left out.
 * Pure: no rendering, so it is covered by unit tests.
 */
public final class LiftRasterizer {

    /** The kinds of lift the shader tells apart, in the order of their codes. */
    public enum Kind { CABLE_CAR, GONDOLA, CHAIR_LIFT, DRAG_LIFT, MAGIC_CARPET }

    /** One travel turn along a lift, in metres; the shader counts carriers in whole fractions of it. */
    static final float PATTERN_METRES = 160f;
    /** How far from the line the distance field reaches, in metres: room for the widest cabin. */
    static final float REACH_METRES = 10f;
    /** At least this many texels, so a lift stays drawable on a tile far away. */
    static final float MIN_REACH_TEXELS = 2.5f;
    /** A lift whose top end is lower than its bottom end by more than this is drawn the other way. */
    static final float DROP_TO_REVERSE_METRES = 2f;
    private static final float CHUNK_TEXELS = 24f;
    private static final double METRES_PER_DEGREE = 111320.0;

    private LiftRasterizer() {
    }

    /** The code stored in B for a kind: the middle of its fifth of the range, so filtering cannot move it into another. */
    public static float code(Kind kind) {
        return (kind.ordinal() + 0.5f) / Kind.values().length;
    }

    /** The kind of lift a way is, from its own {@code aerialway} tag; null for anything that is not a working lift. */
    public static Kind kindOf(List<Tag> tags) {
        String value = ownValue(tags, "aerialway");
        if (value == null) {
            return null;
        }
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "cable_car":
                return Kind.CABLE_CAR;
            case "gondola":
            case "mixed_lift":
                return Kind.GONDOLA;
            case "chair_lift":
                return Kind.CHAIR_LIFT;
            case "drag_lift":
            case "t-bar":
            case "j-bar":
            case "platter":
            case "rope_tow":
                return Kind.DRAG_LIFT;
            case "magic_carpet":
                return Kind.MAGIC_CARPET;
            default:
                return null;
        }
    }

    /** The lifts among {@code ways} as features for the label planner: a {@link RoadClass#LIFT} each, its attribute the kind's code. */
    public static List<RoadFeature> labelFeatures(List<Way> ways) {
        List<RoadFeature> out = new ArrayList<>();
        if (ways == null) {
            return out;
        }
        for (Way way : ways) {
            if (way == null || way.latLongs == null) {
                continue;
            }
            Kind kind = kindOf(way.tags);
            if (kind == null) {
                continue;
            }
            String name = ownValue(way.tags, "name");
            String number = ownValue(way.tags, "ref");
            for (LatLong[] line : way.latLongs) {
                if (line == null || line.length < 2 || hasNull(line)) {
                    continue;
                }
                double[] lat = new double[line.length], lon = new double[line.length];
                for (int i = 0; i < line.length; i++) {
                    lat[i] = line[i].latitude;
                    lon[i] = line[i].longitude;
                }
                out.add(new RoadFeature(RoadClass.LIFT, code(kind), lat, lon, false, name, number));
            }
        }
        return out;
    }

    /**
     * Draws the lifts among {@code ways} into a {@code res} by {@code res} texture over the box,
     * north up.
     *
     * @param elevation the terrain's heights, to point each lift uphill; null to trust the ways
     */
    public static PisteRasterizer.Result rasterize(List<Way> ways, double north, double south, double east, double west,
                                                   int res, PisteRasterizer.Elevation elevation) {
        int n = res * res;
        float[] field = new float[n];
        float[] phase = new float[n];
        float[] kinds = new float[n];
        double dLat = north - south, dLon = east - west;
        if (ways == null || dLat <= 0 || dLon <= 0) {
            return new PisteRasterizer.Result(new byte[n * 4], res, true);
        }
        double metresPerTexel = dLon * METRES_PER_DEGREE * Math.cos(Math.toRadians((north + south) / 2)) / res;
        float reach = reachTexels(metresPerTexel);
        boolean any = false;
        for (Way way : ways) {
            if (way == null || way.latLongs == null) {
                continue;
            }
            Kind kind = kindOf(way.tags);
            if (kind == null) {
                continue;
            }
            for (LatLong[] line : way.latLongs) {
                if (line == null || line.length < 2 || hasNull(line)) {
                    continue;
                }
                any |= stampLift(field, phase, kinds, res, line, north, west, dLat, dLon, code(kind), reach, elevation);
            }
        }
        if (!any) {
            return new PisteRasterizer.Result(new byte[n * 4], res, true);
        }
        boolean[] directed = new boolean[n];
        java.util.Arrays.fill(directed, true);
        PisteRasterizer.dilate(field, phase, kinds, directed, res);
        byte[] out = new byte[n * 4];
        for (int i = 0; i < n; i++) {
            if (field[i] <= 0f && kinds[i] < 0f) {
                continue;
            }
            int o = i * 4;
            double angle = phase[i] * 2.0 * Math.PI;
            out[o] = unorm((float) (Math.sin(angle) * 0.5 + 0.5));
            out[o + 1] = unorm((float) (Math.cos(angle) * 0.5 + 0.5));
            out[o + 2] = unorm(Math.max(0f, kinds[i]));
            out[o + 3] = unorm(Math.max(0f, Math.min(1f, field[i])));
        }
        return new PisteRasterizer.Result(out, res, false);
    }

    /** How far the distance field reaches from a lift, in texels, at a tile's scale. */
    static float reachTexels(double metresPerTexel) {
        return Math.max(MIN_REACH_TEXELS, (float) (REACH_METRES / metresPerTexel));
    }

    private static boolean stampLift(float[] field, float[] phase, float[] kinds, int res, LatLong[] line,
                                     double north, double west, double dLat, double dLon, float kindCode,
                                     float reach, PisteRasterizer.Elevation elevation) {
        int count = line.length;
        double[] along = new double[count];
        for (int i = 1; i < count; i++) {
            along[i] = along[i - 1] + metres(line[i - 1], line[i]);
        }
        double total = along[count - 1];
        boolean reversed = false;
        if (elevation != null) {
            float start = elevation.metres(line[0].latitude, line[0].longitude);
            float end = elevation.metres(line[count - 1].latitude, line[count - 1].longitude);
            reversed = !Float.isNaN(start) && !Float.isNaN(end) && start - end > DROP_TO_REVERSE_METRES;
        }
        boolean wrote = false;
        for (int i = 0; i + 1 < count; i++) {
            float ax = (float) ((line[i].longitude - west) / dLon * res), ay = (float) ((north - line[i].latitude) / dLat * res);
            float bx = (float) ((line[i + 1].longitude - west) / dLon * res), by = (float) ((north - line[i + 1].latitude) / dLat * res);
            if (Math.max(ax, bx) < -reach || Math.min(ax, bx) > res + reach
                    || Math.max(ay, by) < -reach || Math.min(ay, by) > res + reach) {
                continue;
            }
            int chunks = Math.max(1, (int) Math.ceil(Math.hypot(bx - ax, by - ay) / CHUNK_TEXELS));
            for (int c = 0; c < chunks; c++) {
                float t0 = (float) c / chunks, t1 = (float) (c + 1) / chunks;
                double d0 = along[i] + (along[i + 1] - along[i]) * t0, d1 = along[i] + (along[i + 1] - along[i]) * t1;
                if (reversed) {
                    d0 = total - d0;
                    d1 = total - d1;
                }
                wrote |= stampChunk(field, phase, kinds, res, ax + (bx - ax) * t0, ay + (by - ay) * t0,
                        ax + (bx - ax) * t1, ay + (by - ay) * t1,
                        (float) (d0 / PATTERN_METRES), (float) (d1 / PATTERN_METRES), kindCode, reach);
            }
        }
        return wrote;
    }

    private static boolean stampChunk(float[] field, float[] phase, float[] kinds, int res,
                                      float ax, float ay, float bx, float by, float phaseA, float phaseB,
                                      float kindCode, float reach) {
        int minX = Math.max(0, (int) Math.floor(Math.min(ax, bx) - reach));
        int maxX = Math.min(res - 1, (int) Math.ceil(Math.max(ax, bx) + reach));
        int minY = Math.max(0, (int) Math.floor(Math.min(ay, by) - reach));
        int maxY = Math.min(res - 1, (int) Math.ceil(Math.max(ay, by) + reach));
        float ex = bx - ax, ey = by - ay, lengthSquared = ex * ex + ey * ey;
        boolean wrote = false;
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                float px = x + 0.5f - ax, py = y + 0.5f - ay;
                float t = lengthSquared < 1e-6f ? 0f : Math.max(0f, Math.min(1f, (px * ex + py * ey) / lengthSquared));
                float dx = px - ex * t, dy = py - ey * t;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d >= reach) {
                    continue;
                }
                float value = 1f - d / reach;
                int i = y * res + x;
                if (value > field[i]) {
                    field[i] = value;
                    phase[i] = phaseA + (phaseB - phaseA) * t;
                    kinds[i] = kindCode;
                    wrote = true;
                }
            }
        }
        return wrote;
    }

    /** A value from the way's own tags only: a lift's are always its own. */
    private static String ownValue(List<Tag> tags, String key) {
        if (tags == null) {
            return null;
        }
        for (Tag tag : tags) {
            if (tag == null) {
                continue;
            }
            if ("type".equals(tag.key)) {
                return null; // the relations' tags start here
            }
            if (key.equals(tag.key) && tag.value != null && !tag.value.isEmpty()) {
                return tag.value;
            }
        }
        return null;
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
}
