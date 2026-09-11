package com.peaknav.roads;

import java.util.Arrays;
import java.util.List;

/**
 * Draws one tile's roads, tracks, trails and pistes into two textures for the terrain shader -
 * not as coloured pixels, but as <em>distances</em>.
 *
 * <p>Each texel of the distance texture holds, per class, how far it is from the nearest line of
 * that class. The shader turns that into a line of any width, colour and outline it likes, with
 * an edge that stays sharp however far the camera comes in: a distance interpolates cleanly
 * between texels where a coloured mask can only blur. So the colours, the dash pattern and its
 * animation are all shader uniforms, and changing any of them costs nothing - no tile is ever
 * redrawn for a style change. And because it is plain arithmetic into a byte array, it runs
 * the same on every platform: iOS, which never had a mapsforge canvas to draw roads with, gets
 * them from exactly this code.
 *
 * <p>Distance texture, {@code res x res}, RGBA:
 * <ul>
 *   <li>R roads, G tracks, B trails, A pistes: the distance in texels, stored as
 *       {@code (d + BIAS) / (BAND + BIAS)}. Distances past {@link #BAND} are clamped; negative
 *       ones mean "inside" - the interior of a piste area, or the extra width baked into a major
 *       road, which is how the road's rank reaches the shader without a channel of its own.</li>
 * </ul>
 * Aux texture, half the resolution, RGBA:
 * <ul>
 *   <li>RG the nearest trail's position along its way, as the sine and cosine of
 *       {@code 2 pi s / DASH_BASE_METERS}. Stored as a unit vector so a linear filter blends it
 *       cleanly through the wrap; the shader recovers the angle, multiplies it by the dash count
 *       the user chose (an integer, so the pattern stays continuous through the wrap), and
 *       animates it by adding time. Where filtering has averaged it to nothing - far away, in
 *       the small mip levels - the dashes fade into a solid line instead of shimmering.</li>
 *   <li>B the nearest piste's difficulty, A the nearest trail's difficulty
 *       ({@link RoadFeature} constants). Written over a wide band so the smaller mip levels,
 *       which average texels, do not dilute a red trail into a yellow one at a distance.</li>
 * </ul>
 */
public final class RoadTileRasterizer {

    /** Texels of distance kept on either side of a line. */
    public static final float BAND = 8f;
    /** How far below zero the stored range reaches: area interiors, and rank widths. */
    public static final float BIAS = 2f;

    public static final int CH_ROAD = 0;
    public static final int CH_TRACK = 1;
    public static final int CH_PATH = 2;
    public static final int CH_PISTE = 3;

    /**
     * The ground length of one full turn of the stored dash phase. Long on purpose: the phase
     * must turn slowly across the texels to survive filtering, and the shader divides it into
     * as many dashes as the user asks for.
     */
    public static final float DASH_BASE_METERS = 240f;

    /** Extra half-width baked into a road's distance, by rank, in metres. */
    static final float MAJOR_EXTRA_HALF_WIDTH_M = 3.0f;
    static final float LOCAL_EXTRA_HALF_WIDTH_M = 1.2f;

    /** Long segments are diced so each piece's bounding box hugs the line. */
    private static final float CHUNK_TEXELS = 24f;

    private RoadTileRasterizer() {
    }

    /** Scratch buffers, reused per thread: a 1024-texel tile is 16 MB of floats. */
    private static final class Workspace {
        int res = -1;
        int auxRes = -1;
        final float[][] dist = new float[4][];
        float[] pathNear;
        float[] pathPhase;
        float[] pathDifficulty;
        float[] pisteNear;
        float[] pisteDifficulty;

        void prepare(int res, int auxRes) {
            if (this.res != res) {
                for (int c = 0; c < 4; c++) {
                    dist[c] = new float[res * res];
                }
                this.res = res;
            }
            if (this.auxRes != auxRes) {
                int n = auxRes * auxRes;
                pathNear = new float[n];
                pathPhase = new float[n];
                pathDifficulty = new float[n];
                pisteNear = new float[n];
                pisteDifficulty = new float[n];
                this.auxRes = auxRes;
            }
            for (int c = 0; c < 4; c++) {
                Arrays.fill(dist[c], BAND);
            }
            Arrays.fill(pathNear, AUX_BAND);
            Arrays.fill(pisteNear, AUX_BAND);
            Arrays.fill(pathPhase, 0f);
            Arrays.fill(pathDifficulty, 0f);
            Arrays.fill(pisteDifficulty, 0f);
        }
    }

    /** The aux band, in aux texels: as wide as the distance band, i.e. twice its ground reach. */
    private static final float AUX_BAND = BAND;

    private static final ThreadLocal<Workspace> WORKSPACE = new ThreadLocal<Workspace>() {
        @Override
        protected Workspace initialValue() {
            return new Workspace();
        }
    };

    /**
     * Rasterizes the features over the box {@code north/south/east/west} into a
     * {@code res x res} distance texture and a {@code res/2} aux texture. Features of classes
     * that are not drawn are ignored, as are those that do not come near the box.
     */
    public static RoadTextures rasterize(List<RoadFeature> features,
                                         double north, double south, double east, double west,
                                         int res) {
        if (res < 2) {
            throw new IllegalArgumentException("res " + res);
        }
        int auxRes = Math.max(1, res / 2);
        float metersPerTexel = (float) RoadGeo.metersPerTexel(north, south, east, west, res);
        double dLon = east - west;
        double dLat = north - south;
        Workspace ws = WORKSPACE.get();
        ws.prepare(res, auxRes);

        boolean any = false;
        if (dLon > 0 && dLat > 0) {
            for (RoadFeature f : features) {
                if (!f.roadClass.isDrawn() || f.size() < 2) {
                    continue;
                }
                int n = f.size();
                float[] xs = new float[n];
                float[] ys = new float[n];
                float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
                float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
                for (int i = 0; i < n; i++) {
                    // Tile UV, as the mesh maps it: u east from the west edge, v south from the
                    // north edge (row 0 of the texture is the north edge).
                    xs[i] = (float) ((f.lon[i] - west) / dLon * res);
                    ys[i] = (float) ((north - f.lat[i]) / dLat * res);
                    minX = Math.min(minX, xs[i]);
                    maxX = Math.max(maxX, xs[i]);
                    minY = Math.min(minY, ys[i]);
                    maxY = Math.max(maxY, ys[i]);
                }
                float margin = BAND + BIAS + 2f;
                if (maxX < -margin || minX > res + margin || maxY < -margin || minY > res + margin) {
                    continue;
                }
                any |= stampFeature(ws, f, xs, ys, res, auxRes, metersPerTexel);
            }
        }

        byte[] distance = new byte[res * res * 4];
        byte[] aux = new byte[auxRes * auxRes * 4];
        encode(ws, res, auxRes, distance, aux);
        return new RoadTextures(res, auxRes, distance, aux, !any, metersPerTexel);
    }

    private static boolean stampFeature(Workspace ws, RoadFeature f, float[] xs, float[] ys,
                                        int res, int auxRes, float metersPerTexel) {
        float[] ax = scaled(xs, 0.5f);
        float[] ay = scaled(ys, 0.5f);
        switch (f.roadClass) {
            case ROAD: {
                float extra = f.attribute >= RoadFeature.RANK_MAJOR ? MAJOR_EXTRA_HALF_WIDTH_M
                        : f.attribute >= RoadFeature.RANK_LOCAL ? LOCAL_EXTRA_HALF_WIDTH_M : 0f;
                float offset = Math.min(BIAS, extra / metersPerTexel);
                return stampPolyline(ws.dist[CH_ROAD], res, res, xs, ys, offset);
            }
            case TRACK:
                return stampPolyline(ws.dist[CH_TRACK], res, res, xs, ys, 0f);
            case PATH: {
                boolean wrote = stampPolyline(ws.dist[CH_PATH], res, res, xs, ys, 0f);
                float[] along = cumulativeMeters(f);
                stampPolylineAux(ws.pathNear, ws.pathPhase, ws.pathDifficulty, auxRes,
                        ax, ay, along, f.attribute);
                return wrote;
            }
            case PISTE: {
                boolean wrote;
                if (f.area) {
                    wrote = stampArea(ws.dist[CH_PISTE], null, res, xs, ys, 0f, BAND);
                    stampArea(ws.pisteNear, ws.pisteDifficulty, auxRes, ax, ay,
                            f.attribute, AUX_BAND);
                } else {
                    wrote = stampPolyline(ws.dist[CH_PISTE], res, res, xs, ys, 0f);
                    stampPolylineAux(ws.pisteNear, null, ws.pisteDifficulty, auxRes, ax, ay,
                            null, f.attribute);
                }
                return wrote;
            }
            default:
                return false;
        }
    }

    private static float[] scaled(float[] v, float k) {
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = v[i] * k;
        }
        return out;
    }

    /** Metres along the way at each node: the dash phase runs on this, so it is continuous
     *  across tile edges - the neighbouring tile computes the same numbers from the same way. */
    static float[] cumulativeMeters(RoadFeature f) {
        float[] s = new float[f.size()];
        for (int i = 1; i < s.length; i++) {
            s[i] = s[i - 1] + (float) RoadGeo.metersBetween(f.lat[i - 1], f.lon[i - 1],
                    f.lat[i], f.lon[i]);
        }
        return s;
    }

    private static boolean stampPolyline(float[] field, int w, int h, float[] xs, float[] ys,
                                         float offset) {
        boolean wrote = false;
        float reach = BAND + offset;
        for (int i = 0; i + 1 < xs.length; i++) {
            float x0 = xs[i], y0 = ys[i], x1 = xs[i + 1], y1 = ys[i + 1];
            float len = (float) Math.hypot(x1 - x0, y1 - y0);
            int chunks = Math.max(1, (int) Math.ceil(len / CHUNK_TEXELS));
            for (int c = 0; c < chunks; c++) {
                float t0 = (float) c / chunks;
                float t1 = (float) (c + 1) / chunks;
                wrote |= stampSegment(field, w, h,
                        x0 + (x1 - x0) * t0, y0 + (y1 - y0) * t0,
                        x0 + (x1 - x0) * t1, y0 + (y1 - y0) * t1,
                        reach, offset);
            }
        }
        return wrote;
    }

    /**
     * Gives every texel within {@code reach} of the segment its distance to it, less the
     * offset, where that beats what the texel already holds. The nearer line wins, which keeps
     * joins and crossings continuous.
     */
    static boolean stampSegment(float[] field, int w, int h, float ax, float ay, float bx, float by,
                                float reach, float offset) {
        int minX = Math.max(0, (int) Math.floor(Math.min(ax, bx) - reach));
        int maxX = Math.min(w - 1, (int) Math.ceil(Math.max(ax, bx) + reach));
        int minY = Math.max(0, (int) Math.floor(Math.min(ay, by) - reach));
        int maxY = Math.min(h - 1, (int) Math.ceil(Math.max(ay, by) + reach));
        if (minX > maxX || minY > maxY) {
            return false;
        }
        float ex = bx - ax;
        float ey = by - ay;
        float lenSq = ex * ex + ey * ey;
        float reachSq = reach * reach;
        boolean wrote = false;
        for (int y = minY; y <= maxY; y++) {
            int row = y * w;
            float py = y + 0.5f - ay;
            for (int x = minX; x <= maxX; x++) {
                float px = x + 0.5f - ax;
                float t = (lenSq < 1e-9f) ? 0f : (px * ex + py * ey) / lenSq;
                t = (t < 0f) ? 0f : (t > 1f ? 1f : t);
                float dx = px - ex * t;
                float dy = py - ey * t;
                float dsq = dx * dx + dy * dy;
                if (dsq >= reachSq) {
                    continue;
                }
                float d = (float) Math.sqrt(dsq) - offset;
                int idx = row + x;
                if (d < field[idx]) {
                    field[idx] = d;
                    wrote = true;
                }
            }
        }
        return wrote;
    }

    /**
     * The aux-texture counterpart of {@link #stampPolyline}: tracks, per texel, the nearest
     * line and records its attribute and - when {@code along} is given - its dash phase there.
     */
    private static void stampPolylineAux(float[] near, float[] phase, float[] attr, int res,
                                         float[] xs, float[] ys, float[] along, float attribute) {
        for (int i = 0; i + 1 < xs.length; i++) {
            float x0 = xs[i], y0 = ys[i], x1 = xs[i + 1], y1 = ys[i + 1];
            float s0 = along == null ? 0f : along[i];
            float s1 = along == null ? 0f : along[i + 1];
            float len = (float) Math.hypot(x1 - x0, y1 - y0);
            int chunks = Math.max(1, (int) Math.ceil(len / CHUNK_TEXELS));
            for (int c = 0; c < chunks; c++) {
                float t0 = (float) c / chunks;
                float t1 = (float) (c + 1) / chunks;
                stampSegmentAux(near, phase, attr, res,
                        x0 + (x1 - x0) * t0, y0 + (y1 - y0) * t0,
                        x0 + (x1 - x0) * t1, y0 + (y1 - y0) * t1,
                        s0 + (s1 - s0) * t0, s0 + (s1 - s0) * t1, attribute);
            }
        }
    }

    private static void stampSegmentAux(float[] near, float[] phase, float[] attr, int res,
                                        float ax, float ay, float bx, float by,
                                        float sA, float sB, float attribute) {
        float reach = AUX_BAND;
        int minX = Math.max(0, (int) Math.floor(Math.min(ax, bx) - reach));
        int maxX = Math.min(res - 1, (int) Math.ceil(Math.max(ax, bx) + reach));
        int minY = Math.max(0, (int) Math.floor(Math.min(ay, by) - reach));
        int maxY = Math.min(res - 1, (int) Math.ceil(Math.max(ay, by) + reach));
        if (minX > maxX || minY > maxY) {
            return;
        }
        float ex = bx - ax;
        float ey = by - ay;
        float lenSq = ex * ex + ey * ey;
        float reachSq = reach * reach;
        for (int y = minY; y <= maxY; y++) {
            int row = y * res;
            float py = y + 0.5f - ay;
            for (int x = minX; x <= maxX; x++) {
                float px = x + 0.5f - ax;
                float t = (lenSq < 1e-9f) ? 0f : (px * ex + py * ey) / lenSq;
                t = (t < 0f) ? 0f : (t > 1f ? 1f : t);
                float dx = px - ex * t;
                float dy = py - ey * t;
                float dsq = dx * dx + dy * dy;
                if (dsq >= reachSq) {
                    continue;
                }
                float d = (float) Math.sqrt(dsq);
                int idx = row + x;
                if (d < near[idx]) {
                    near[idx] = d;
                    if (phase != null) {
                        phase[idx] = sA + (sB - sA) * t;
                    }
                    if (attr != null) {
                        attr[idx] = attribute;
                    }
                }
            }
        }
    }

    /**
     * Fills a closed outline: inside texels get a negative distance (down to {@code -BIAS}, so
     * the shore of the area stays a smooth ramp), outside ones their distance to the outline.
     * With {@code attr} given, texels this area wins also take its attribute.
     *
     * @return true if anything was written
     */
    static boolean stampArea(float[] field, float[] attr, int res,
                             float[] xs, float[] ys, float attribute, float reach) {
        int n = xs.length;
        float minXf = Float.MAX_VALUE, maxXf = -Float.MAX_VALUE;
        float minYf = Float.MAX_VALUE, maxYf = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            minXf = Math.min(minXf, xs[i]);
            maxXf = Math.max(maxXf, xs[i]);
            minYf = Math.min(minYf, ys[i]);
            maxYf = Math.max(maxYf, ys[i]);
        }
        int minX = Math.max(0, (int) Math.floor(minXf - reach));
        int maxX = Math.min(res - 1, (int) Math.ceil(maxXf + reach));
        int minY = Math.max(0, (int) Math.floor(minYf - reach));
        int maxY = Math.min(res - 1, (int) Math.ceil(maxYf + reach));
        if (minX > maxX || minY > maxY) {
            return false;
        }
        int w = maxX - minX + 1;
        int h = maxY - minY + 1;
        float[] local = new float[w * h];
        Arrays.fill(local, reach);
        for (int i = 0; i + 1 < n; i++) {
            float x0 = xs[i] - minX, y0 = ys[i] - minY, x1 = xs[i + 1] - minX, y1 = ys[i + 1] - minY;
            float len = (float) Math.hypot(x1 - x0, y1 - y0);
            int chunks = Math.max(1, (int) Math.ceil(len / CHUNK_TEXELS));
            for (int c = 0; c < chunks; c++) {
                float t0 = (float) c / chunks;
                float t1 = (float) (c + 1) / chunks;
                stampSegment(local, w, h,
                        x0 + (x1 - x0) * t0, y0 + (y1 - y0) * t0,
                        x0 + (x1 - x0) * t1, y0 + (y1 - y0) * t1, reach, 0f);
            }
        }
        boolean[] inside = insideMask(xs, ys, minX, minY, w, h);
        boolean wrote = false;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int li = y * w + x;
                float u = local[li];
                float d = inside[li] ? -Math.min(u, BIAS) : u;
                if (!inside[li] && u >= reach) {
                    continue;
                }
                int idx = (minY + y) * res + (minX + x);
                if (d < field[idx]) {
                    field[idx] = d;
                    if (attr != null) {
                        attr[idx] = attribute;
                    }
                    wrote = true;
                }
            }
        }
        return wrote;
    }

    /** Even-odd fill of the outline over texel centres, row by row. */
    static boolean[] insideMask(float[] xs, float[] ys, int originX, int originY, int w, int h) {
        boolean[] mask = new boolean[w * h];
        int n = xs.length;
        float[] crossings = new float[n];
        for (int y = 0; y < h; y++) {
            float cy = originY + y + 0.5f;
            int count = 0;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                float yi = ys[i], yj = ys[j];
                if ((yi > cy) != (yj > cy)) {
                    crossings[count++] = xs[i] + (cy - yi) * (xs[j] - xs[i]) / (yj - yi);
                }
            }
            if (count < 2) {
                continue;
            }
            Arrays.sort(crossings, 0, count);
            for (int k = 0; k + 1 < count; k += 2) {
                int from = Math.max(0, (int) Math.ceil(crossings[k] - 0.5f - originX));
                int to = Math.min(w - 1, (int) Math.floor(crossings[k + 1] - 0.5f - originX));
                for (int x = from; x <= to; x++) {
                    mask[y * w + x] = true;
                }
            }
        }
        return mask;
    }

    private static void encode(Workspace ws, int res, int auxRes, byte[] distance, byte[] aux) {
        int n = res * res;
        float[] r = ws.dist[CH_ROAD], g = ws.dist[CH_TRACK], b = ws.dist[CH_PATH], a = ws.dist[CH_PISTE];
        for (int i = 0; i < n; i++) {
            int o = i * 4;
            distance[o] = encodeDistance(r[i]);
            distance[o + 1] = encodeDistance(g[i]);
            distance[o + 2] = encodeDistance(b[i]);
            distance[o + 3] = encodeDistance(a[i]);
        }
        int m = auxRes * auxRes;
        for (int i = 0; i < m; i++) {
            int o = i * 4;
            if (ws.pathNear[i] < AUX_BAND) {
                double angle = ws.pathPhase[i] / DASH_BASE_METERS * 2.0 * Math.PI;
                aux[o] = unorm((float) (Math.sin(angle) * 0.5 + 0.5));
                aux[o + 1] = unorm((float) (Math.cos(angle) * 0.5 + 0.5));
                aux[o + 3] = unorm(ws.pathDifficulty[i]);
            } else {
                aux[o] = unorm(0.5f);
                aux[o + 1] = unorm(0.5f);
                aux[o + 3] = 0;
            }
            aux[o + 2] = ws.pisteNear[i] < AUX_BAND ? unorm(ws.pisteDifficulty[i]) : 0;
        }
    }

    /** A distance in texels as the byte the shader reads back with {@link #decodeDistance}. */
    public static byte encodeDistance(float d) {
        return unorm((d + BIAS) / (BAND + BIAS));
    }

    public static float decodeDistance(byte b) {
        return (b & 0xFF) / 255f * (BAND + BIAS) - BIAS;
    }

    private static byte unorm(float v) {
        int i = Math.round(v * 255f);
        return (byte) ((i < 0) ? 0 : (i > 255 ? 255 : i));
    }
}
