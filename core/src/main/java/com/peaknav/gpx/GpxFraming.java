package com.peaknav.gpx;

/**
 * Where to put the camera to show a whole track: the view a loaded GPX file opens on.
 *
 * <p>Every point of the track is fitted inside the part of the screen the controls leave free,
 * from a three-quarter view. For a given compass heading and downward angle the nearest such
 * camera has a closed form: each edge of that free rectangle is a plane through the camera, the
 * track must lie on its inner side, and the two pairs of opposite edges each fix how far back the
 * camera must stand. Sixteen headings at each of {@link #DEPRESSIONS} are tried and the view that
 * shows the track largest wins, with a slight preference for looking up the track (from its low
 * end towards its high end) and a penalty for a view where mountains hide the path. A shallower
 * angle brings the camera nearer - a quarter nearer for a long track at 25 degrees than at 35 -
 * but lets ridges hide more of the path; the penalty weighs the one against the other.
 *
 * <p>World units are the map's: x east and y north in latits, z up in latits. Pure: no
 * rendering, so it is covered by unit tests.
 */
public final class GpxFraming {

    /** How steeply the camera may look down: three-quarter views that keep the relief readable. */
    static final float[] DEPRESSIONS = {25f, 32f, 40f};
    /** Headings tried, evenly around the compass. */
    static final int HEADINGS = 16;
    /** Line-of-sight samples per track point when checking whether the terrain hides it. */
    static final int SIGHT_STEPS = 12;
    /** Track points whose line of sight is checked, at most. */
    static final int SIGHT_POINTS = 24;
    /** How much a fully hidden track lengthens a heading's effective distance. */
    static final float HIDDEN_PENALTY = 1.5f;
    /** How much looking down the track (high end nearest) lengthens it, against looking up it. */
    static final float DOWNHILL_PENALTY = 0.15f;

    /** Terrain height, in latits, at a world position; NaN where it is not known. */
    public interface Terrain {
        float heightAt(float x, float y);
    }

    /** The screen the track must fit in: fields of view and the free margins, as fractions. */
    public static final class Screen {
        final float tanHalfVertical, tanHalfHorizontal;
        final float left, right, top, bottom;

        /**
         * @param verticalFovDegrees the camera's vertical field of view
         * @param aspect             width over height
         * @param left               fraction of the width kept clear on the left, and so on
         */
        public Screen(float verticalFovDegrees, float aspect, float left, float right, float top, float bottom) {
            this.tanHalfVertical = (float) Math.tan(Math.toRadians(verticalFovDegrees) / 2);
            this.tanHalfHorizontal = tanHalfVertical * aspect;
            this.left = left;
            this.right = right;
            this.top = top;
            this.bottom = bottom;
        }
    }

    /** The chosen camera: where it stands and which way it looks (a unit vector). */
    public static final class Result {
        public final float x, y, z;
        public final float dirX, dirY, dirZ;
        public final float headingDegrees;

        Result(float x, float y, float z, float dirX, float dirY, float dirZ, float headingDegrees) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dirX = dirX;
            this.dirY = dirY;
            this.dirZ = dirZ;
            this.headingDegrees = headingDegrees;
        }
    }

    private GpxFraming() {
    }

    /**
     * The camera that shows the whole track.
     *
     * @param xs, ys, zs    the track's points (a sample of them is plenty)
     * @param preferHeading the heading to favour, degrees clockwise from north - up the track;
     *                      NaN for none
     * @param minDistance   the nearest the camera may be to the track's centre, latits
     * @param clearance     the least height above the terrain under the camera, and above the
     *                      track's highest point, latits
     * @param terrain       heights for the line-of-sight and clearance checks; may be null
     */
    public static Result frame(float[] xs, float[] ys, float[] zs, Screen screen, float preferHeading,
                               float minDistance, float clearance, Terrain terrain) {
        int n = xs.length;
        float cx = 0, cy = 0, cz = 0, topZ = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            cx += xs[i];
            cy += ys[i];
            cz += zs[i];
            topZ = Math.max(topZ, zs[i]);
        }
        cx /= n;
        cy /= n;
        cz /= n;

        Result best = null;
        float bestCost = Float.MAX_VALUE;
        for (int k = 0; k < HEADINGS * DEPRESSIONS.length; k++) {
            float heading = 360f * (k % HEADINGS) / HEADINGS;
            float depression = DEPRESSIONS[k / HEADINGS];
            float[] cam = fit(xs, ys, zs, screen, heading, depression);
            float[] dir = direction(heading, depression);
            // Not nearer than the minimum, not below the clearance: both by stepping back along
            // the view, which also raises the camera since it looks down.
            float distance = dist(cam[0], cam[1], cam[2], cx, cy, cz);
            if (distance < minDistance) {
                back(cam, dir, minDistance - distance);
            }
            float floor = topZ + clearance;
            if (terrain != null) {
                float ground = terrain.heightAt(cam[0], cam[1]);
                if (!Float.isNaN(ground)) {
                    floor = Math.max(floor, ground + clearance);
                }
            }
            if (cam[2] < floor) {
                back(cam, dir, (floor - cam[2]) / -dir[2]);
            }
            float cost = dist(cam[0], cam[1], cam[2], cx, cy, cz);
            if (terrain != null) {
                cost *= 1f + HIDDEN_PENALTY * hiddenFraction(cam, xs, ys, zs, terrain);
            }
            if (!Float.isNaN(preferHeading)) {
                float off = (float) Math.cos(Math.toRadians(heading - preferHeading));
                cost *= 1f + DOWNHILL_PENALTY * (1f - off) / 2f;
            }
            if (cost < bestCost) {
                bestCost = cost;
                best = new Result(cam[0], cam[1], cam[2], dir[0], dir[1], dir[2], heading);
            }
        }
        return best;
    }

    /** Unit view direction for a heading, looking down by {@code depressionDegrees}. */
    static float[] direction(float headingDegrees, float depressionDegrees) {
        double h = Math.toRadians(headingDegrees), d = Math.toRadians(depressionDegrees);
        return new float[]{(float) (Math.sin(h) * Math.cos(d)), (float) (Math.cos(h) * Math.cos(d)),
                (float) -Math.sin(d)};
    }

    /**
     * The nearest camera looking along {@code heading}, down by {@code depression}, that has every
     * point inside the screen's free rectangle. In the camera's frame (right r, up u, forward f) a point p is inside when
     * kb &lt;= u.(p-c) / f.(p-c) &lt;= kt and kl &lt;= r.(p-c) / f.(p-c) &lt;= kr, the k being the
     * rectangle's edges as slopes. Each is a half-space for c; writing c = a r + b u + e f, the
     * top and bottom pair bound e from above, as do left and right, and the tighter bound wins -
     * then a and b sit midway in what they are allowed.
     */
    static float[] fit(float[] xs, float[] ys, float[] zs, Screen s, float heading, float depression) {
        float[] f = direction(heading, depression);
        double h = Math.toRadians(heading);
        float[] r = {(float) Math.cos(h), (float) -Math.sin(h), 0f};
        float[] u = cross(r, f);
        float kt = (1 - 2 * s.top) * s.tanHalfVertical;
        float kb = (-1 + 2 * s.bottom) * s.tanHalfVertical;
        float kr = (1 - 2 * s.right) * s.tanHalfHorizontal;
        float kl = (-1 + 2 * s.left) * s.tanHalfHorizontal;
        float mt = -Float.MAX_VALUE, mb = -Float.MAX_VALUE, mr = -Float.MAX_VALUE, ml = -Float.MAX_VALUE;
        for (int i = 0; i < xs.length; i++) {
            float pr = r[0] * xs[i] + r[1] * ys[i] + r[2] * zs[i];
            float pu = u[0] * xs[i] + u[1] * ys[i] + u[2] * zs[i];
            float pf = f[0] * xs[i] + f[1] * ys[i] + f[2] * zs[i];
            mt = Math.max(mt, pu - kt * pf);
            mb = Math.max(mb, kb * pf - pu);
            mr = Math.max(mr, pr - kr * pf);
            ml = Math.max(ml, kl * pf - pr);
        }
        // b - kt e >= mt, kb e - b >= mb  =>  e <= -(mt + mb) / (kt - kb); likewise for a.
        float e = Math.min(-(mt + mb) / (kt - kb), -(mr + ml) / (kr - kl));
        float b = ((mt + kt * e) + (kb * e - mb)) / 2;
        float a = ((mr + kr * e) + (kl * e - ml)) / 2;
        return new float[]{a * r[0] + b * u[0] + e * f[0], a * r[1] + b * u[1] + e * f[1],
                a * r[2] + b * u[2] + e * f[2]};
    }

    /** The share of (a sample of) the track that the terrain hides from the camera. */
    static float hiddenFraction(float[] cam, float[] xs, float[] ys, float[] zs, Terrain terrain) {
        int step = Math.max(1, xs.length / SIGHT_POINTS);
        int hidden = 0, checked = 0;
        for (int i = 0; i < xs.length; i += step) {
            checked++;
            for (int j = 1; j < SIGHT_STEPS; j++) {
                float t = j / (float) SIGHT_STEPS;
                float x = cam[0] + (xs[i] - cam[0]) * t;
                float y = cam[1] + (ys[i] - cam[1]) * t;
                float z = cam[2] + (zs[i] - cam[2]) * t;
                float ground = terrain.heightAt(x, y);
                if (!Float.isNaN(ground) && ground > z) {
                    hidden++;
                    break;
                }
            }
        }
        return checked == 0 ? 0f : hidden / (float) checked;
    }

    private static void back(float[] cam, float[] dir, float by) {
        cam[0] -= dir[0] * by;
        cam[1] -= dir[1] * by;
        cam[2] -= dir[2] * by;
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static float dist(float ax, float ay, float az, float bx, float by, float bz) {
        float dx = ax - bx, dy = ay - by, dz = az - bz;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
