package com.peaknav.viewer.labels;

import com.peaknav.areas.MapArea;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Keeps area labels from blinking.
 *
 * <p>Two things decide whether an area's name is on screen, and both were being re-evaluated on
 * every rendered frame:
 *
 * <ul>
 *   <li><b>Hidden by mountains.</b> The test samples the geographical depth pixmaps, which are
 *       rendered only when the POI visibility worker asks for them — so between renders they
 *       describe the terrain as seen from where the camera <em>was</em>. Orbiting moves the camera
 *       every frame, so the distance being tested drifts against a frozen depth map, and any area
 *       whose sample points graze a silhouette flips verdict from frame to frame.
 *   <li><b>Hidden by another label.</b> The winner of a contested spot was re-decided every frame,
 *       so two names grazing each other's edge traded the spot continuously.
 * </ul>
 *
 * <p>Peak labels never show either symptom, and the reason is instructive: their occlusion is
 * decided once per visibility pass, against a depth map rendered <em>for</em> that pass, and then
 * cached until the next one. This class gives the area labels the same treatment — the raw tests
 * run at the decision cadence rather than at frame rate, and a verdict already in force is not
 * overturned by a single dissenting sample.
 *
 * <p>Not thread safe: it is used from the render thread only.
 */
public final class AreaLabelStability {

    /**
     * How many decisions in a row the raw occlusion test must disagree with the verdict in force
     * before it is overturned. At the half-second decision cadence this is a second of the terrain
     * test consistently saying otherwise — long enough that noise cannot flip a label, short enough
     * that walking behind a ridge hides its name promptly.
     */
    public static final int DISSENT_TO_FLIP = 2;

    /** Above this many remembered areas, verdicts untouched for {@link #STALE_MS} are dropped. */
    private static final int CACHE_LIMIT = 256;
    private static final long STALE_MS = 60_000L;

    /** The verdict in force for one area, and how much evidence has accrued against it. */
    private static final class Verdict {
        boolean visible;
        int dissent;
        long touchedMs;
    }

    private final Map<MapArea, Verdict> verdicts = new HashMap<>();

    /**
     * The verdict in force, for frames that are not deciding anything. Areas never sampled read as
     * hidden: an area whose terrain test has not run yet is not on screen either, since only
     * labels chosen at a decision are drawn between decisions.
     */
    public boolean lastVerdict(MapArea area) {
        Verdict v = verdicts.get(area);
        return v != null && v.visible;
    }

    /**
     * Folds one raw "is any part of it clear of nearer terrain" sample into the verdict for
     * {@code area} and returns the verdict now in force.
     *
     * <p>A newly seen area is believed at once, so labels still appear promptly; only reversals
     * have to be earned, by {@link #DISSENT_TO_FLIP} decisions running.
     */
    public boolean record(MapArea area, boolean raw, long nowMs) {
        Verdict v = verdicts.get(area);
        if (v == null) {
            v = new Verdict();
            v.visible = raw;
            prune(nowMs);
            verdicts.put(area, v);
        } else if (raw == v.visible) {
            v.dissent = 0;
        } else if (++v.dissent >= DISSENT_TO_FLIP) {
            v.visible = raw;
            v.dissent = 0;
        }
        v.touchedMs = nowMs;
        return v.visible;
    }

    /**
     * Do two label names collide?
     *
     * <p>{@code slackA} shrinks the first rectangle before the test. The de-overlap pass passes
     * slack for a label already on screen: a sitting label must be overlapped by more than the
     * slack to be displaced, a newcomer only has to touch. Without it, two names grazing each
     * other's edge trade the spot at every decision as the camera drifts them apart and back by a
     * pixel — which reads as blinking. With it the spot changes hands once, deliberately.
     *
     * <p>Rectangles are y-up, given as origin plus size, as they come off the label measuring pass.
     */
    public static boolean namesOverlap(float ax, float ay, float aw, float ah, float slackA,
                                       float bx, float by, float bw, float bh) {
        float x = ax + slackA, w = aw - 2f * slackA;
        float y = ay + slackA, h = ah - 2f * slackA;
        if (w <= 0f || h <= 0f) {
            // Slack wider than the label itself — shrink it to its centre point rather than to a
            // rectangle turned inside out, which would never overlap anything.
            x = ax + aw * 0.5f;
            w = 0f;
            y = ay + ah * 0.5f;
            h = 0f;
        }
        return x < bx + bw && x + w > bx && y < by + bh && y + h > by;
    }

    /** Kilometres per degree of latitude; the same constant the label geometry uses. */
    private static final float KM_PER_DEG_LAT = 111.32f;

    /**
     * Is this point inside the area's ellipse?
     *
     * <p>Used to rank a lake above the islands within it. An island in a lake is a feature
     * OF that lake, so letting the islet's name suppress the lake's reads backwards - and
     * it did, because islands outrank lakes everywhere else (an island in the sea is the
     * landmark; the sea is not).
     *
     * <p>The ellipse is the same one the label geometry uses: semi-axes in kilometres with
     * the major axis rotated counter-clockwise from due East. The point is taken into that
     * frame and tested against the unit circle.
     */
    public static boolean ellipseContains(MapArea area, float lat, float lon) {
        if (area == null) {
            return false;
        }
        float kmPerDegLon = KM_PER_DEG_LAT
                * Math.max(1e-3f, (float) Math.cos(Math.toRadians(area.lat)));
        float dLon = lon - area.lon;
        if (dLon > 180f) dLon -= 360f;
        else if (dLon < -180f) dLon += 360f;
        float east = dLon * kmPerDegLon;
        float north = (lat - area.lat) * KM_PER_DEG_LAT;
        float rot = (float) Math.toRadians(area.rotationDeg);
        float cos = (float) Math.cos(rot), sin = (float) Math.sin(rot);
        float alongMajor = east * cos + north * sin;
        float alongMinor = -east * sin + north * cos;
        float a = Math.max(1e-3f, area.semiMajorKm);
        float b = Math.max(1e-3f, area.semiMinorKm);
        return (alongMajor * alongMajor) / (a * a) + (alongMinor * alongMinor) / (b * b) <= 1f;
    }

    /** How many areas currently carry a verdict. For tests and diagnostics. */
    public int remembered() {
        return verdicts.size();
    }

    /** Forgets everything; used when the area data underneath is reloaded. */
    public void clear() {
        verdicts.clear();
    }

    /**
     * Forgets areas not seen for a while, so travelling does not accumulate a verdict for every
     * area ever passed. Only walks the map once it has grown past its limit.
     */
    private void prune(long nowMs) {
        if (verdicts.size() < CACHE_LIMIT)
            return;
        Iterator<Map.Entry<MapArea, Verdict>> it = verdicts.entrySet().iterator();
        while (it.hasNext()) {
            if (nowMs - it.next().getValue().touchedMs > STALE_MS) {
                it.remove();
            }
        }
    }
}
