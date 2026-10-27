package com.peaknav.contours;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds where the contour lines of a tile may carry their elevation.
 *
 * <p>It works on the tile's own grid of elevations - the vertices the terrain mesh is built
 * from - so a spot lies on the line the shader draws: along a grid edge the mesh is a straight
 * segment, and where a level crosses it is found by the same linear interpolation the GPU does.
 * Only the levels the shader can draw in bold are considered, multiples of
 * {@link #LEVEL_STEP_METERS}: the finest bold lines are four times the finest interval of 25 m.
 *
 * <p>Every crossing of every level is a possible spot, far too many; the ground is divided into
 * blocks of {@link #BLOCK_METERS} and each level keeps, in each block, the crossing nearest the
 * block's centre. The blocks are fixed on the globe, not on the tile, so a tile reloaded at
 * another zoom keeps its labels where they were, and the amount of labels the user picks keeps
 * every first, second or fourth block (see {@link ContourLabelCandidate#kept}).
 */
public final class ContourLabelPlanner {

    /** The levels that may be labelled: multiples of this, in metres. */
    public static final int LEVEL_STEP_METERS = 100;
    /** Side of the blocks the spots are thinned to, one per level each, in metres. */
    public static final double BLOCK_METERS = 250.0;
    /**
     * Ground flatter than this (metres per metre) gets no label: there the line wanders with
     * every metre of noise in the data, and the text would lie across it.
     */
    public static final float MIN_SLOPE = 0.03f;

    private static final double METERS_PER_DEGREE = 111320.0;

    private ContourLabelPlanner() {
    }

    /**
     * The spots on a grid of elevations.
     *
     * @param elevations metres, {@code size * size} of them, row by row from the south, west to east
     * @param size       vertices along each side
     * @param south      latitude of the first row
     * @param north      latitude of the last row
     * @param west       longitude of the first column
     * @param east       longitude of the last column
     */
    public static List<ContourLabelCandidate> plan(float[] elevations, int size,
                                                   double south, double north,
                                                   double west, double east) {
        List<ContourLabelCandidate> out = new ArrayList<>();
        if (size < 3 || elevations.length < size * size) {
            return out;
        }
        double stepLat = (north - south) / (size - 1);
        double stepLon = (east - west) / (size - 1);
        double midLat = 0.5 * (south + north);
        double cellNorthMeters = stepLat * METERS_PER_DEGREE;
        double cellEastMeters = stepLon * METERS_PER_DEGREE * Math.cos(Math.toRadians(midLat));
        if (!(cellNorthMeters > 0) || !(cellEastMeters > 0)) {
            return out;
        }

        // The best crossing so far for each level and block.
        Map<Long, double[]> best = new HashMap<>();
        Map<Long, ContourLabelCandidate> chosen = new HashMap<>();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float z = elevations[y * size + x];
                if (Float.isNaN(z)) {
                    continue;
                }
                // The edge to the east, then the one to the north: each grid edge once.
                for (int dir = 0; dir < 2; dir++) {
                    int x2 = dir == 0 ? x + 1 : x;
                    int y2 = dir == 0 ? y : y + 1;
                    if (x2 >= size || y2 >= size) {
                        continue;
                    }
                    float z2 = elevations[y2 * size + x2];
                    if (Float.isNaN(z2) || z2 == z) {
                        continue;
                    }
                    float lo = Math.min(z, z2), hi = Math.max(z, z2);
                    // Levels with lo < level <= hi: a level exactly at a vertex is crossed by
                    // the one edge that rises to it, not by every edge that touches it.
                    int first = (int) Math.floor(lo / LEVEL_STEP_METERS) + 1;
                    int last = (int) Math.floor(hi / LEVEL_STEP_METERS);
                    for (int k = first; k <= last; k++) {
                        int level = k * LEVEL_STEP_METERS;
                        float t = (level - z) / (z2 - z);
                        double gx = gradientEast(elevations, size, x, y, x2, y2, t, cellEastMeters);
                        double gy = gradientNorth(elevations, size, x, y, x2, y2, t, cellNorthMeters);
                        if (Double.isNaN(gx) || Double.isNaN(gy)
                                || gx * gx + gy * gy < MIN_SLOPE * MIN_SLOPE) {
                            continue;
                        }
                        double lat = south + (y + t * (y2 - y)) * stepLat;
                        double lon = west + (x + t * (x2 - x)) * stepLon;
                        offer(best, chosen, level, lat, lon, (float) gx, (float) gy);
                    }
                }
            }
        }
        out.addAll(chosen.values());
        return out;
    }

    private static void offer(Map<Long, double[]> best, Map<Long, ContourLabelCandidate> chosen,
                              int level, double lat, double lon, float gx, float gy) {
        double northMeters = lat * METERS_PER_DEGREE;
        int by = (int) Math.floor(northMeters / BLOCK_METERS);
        // The block's own row fixes the scale of its columns, so every point agrees on them.
        double rowLat = (by + 0.5) * BLOCK_METERS / METERS_PER_DEGREE;
        double eastScale = METERS_PER_DEGREE * Math.cos(Math.toRadians(rowLat));
        double eastMeters = lon * eastScale;
        int bx = (int) Math.floor(eastMeters / BLOCK_METERS);
        double cy = northMeters - (by + 0.5) * BLOCK_METERS;
        double cx = eastMeters - (bx + 0.5) * BLOCK_METERS;
        double d2 = cx * cx + cy * cy;
        long key = (((long) level) << 42) ^ (((long) bx & 0x1FFFFFL) << 21) ^ ((long) by & 0x1FFFFFL);
        double[] previous = best.get(key);
        if (previous != null && previous[0] <= d2) {
            return;
        }
        best.put(key, new double[]{d2});
        chosen.put(key, new ContourLabelCandidate(level, lat, lon, gx, gy, bx, by));
    }

    /** Slope eastward at a point a share {@code t} of the way along a grid edge, central differences at its ends. */
    private static double gradientEast(float[] e, int size, int x, int y, int x2, int y2, float t,
                                       double cellMeters) {
        return (1 - t) * dzdx(e, size, x, y) / cellMeters + t * dzdx(e, size, x2, y2) / cellMeters;
    }

    private static double gradientNorth(float[] e, int size, int x, int y, int x2, int y2, float t,
                                        double cellMeters) {
        return (1 - t) * dzdy(e, size, x, y) / cellMeters + t * dzdy(e, size, x2, y2) / cellMeters;
    }

    /** Metres of height per grid step eastward at a vertex; NaN where a neighbour is missing. */
    private static double dzdx(float[] e, int size, int x, int y) {
        int a = Math.max(0, x - 1), b = Math.min(size - 1, x + 1);
        return (e[y * size + b] - e[y * size + a]) / (double) (b - a);
    }

    private static double dzdy(float[] e, int size, int x, int y) {
        int a = Math.max(0, y - 1), b = Math.min(size - 1, y + 1);
        return (e[b * size + x] - e[a * size + x]) / (double) (b - a);
    }
}
