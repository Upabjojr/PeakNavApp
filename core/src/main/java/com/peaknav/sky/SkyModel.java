package com.peaknav.sky;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.TimeZone;

/**
 * Holds the observer's location and the current time, and turns them into cached local-sky
 * direction vectors (east, north, up) for the Sun, Moon, planets, catalogue stars and constellation
 * lines. The 3D renderer just projects these unit vectors with the live camera every frame; this
 * model only recomputes them when the clock or the observer has moved enough to matter, so the
 * per-frame cost stays tiny even with thousands of stars.
 */
public final class SkyModel {

    private final SolarSystem solarSystem = new SolarSystem();
    private final StarCatalog stars = new StarCatalog();
    private final ConstellationData constellations = new ConstellationData();

    /** A few of the brightest stars, labelled by their proper names (RA/Dec J2000, degrees). */
    public static final class NamedStar {
        public final String name; public final float raDeg, decDeg;
        NamedStar(String name, float raDeg, float decDeg) { this.name = name; this.raDeg = raDeg; this.decDeg = decDeg; }
    }
    public static final NamedStar[] NAMED_STARS = {
            new NamedStar("Sirius", 101.287f, -16.716f),
            new NamedStar("Canopus", 95.988f, -52.696f),
            new NamedStar("Arcturus", 213.915f, 19.182f),
            new NamedStar("Vega", 279.235f, 38.784f),
            new NamedStar("Capella", 79.172f, 45.998f),
            new NamedStar("Rigel", 78.634f, -8.202f),
            new NamedStar("Procyon", 114.826f, 5.225f),
            new NamedStar("Betelgeuse", 88.793f, 7.407f),
            new NamedStar("Altair", 297.696f, 8.868f),
            new NamedStar("Aldebaran", 68.980f, 16.509f),
            new NamedStar("Antares", 247.352f, -26.432f),
            new NamedStar("Spica", 201.298f, -11.161f),
            new NamedStar("Pollux", 116.329f, 28.026f),
            new NamedStar("Fomalhaut", 344.413f, -29.622f),
            new NamedStar("Deneb", 310.358f, 45.280f),
            new NamedStar("Regulus", 152.093f, 11.967f),
            new NamedStar("Polaris", 37.953f, 89.264f),
    };
    private float[] namedStarEnu = new float[NAMED_STARS.length * 3];

    // Cached horizontal directions as ENU unit vectors (x east, y north, z up), 3 floats per point.
    private float[] starEnu = new float[0];
    private float[] bodyEnu = new float[0];               // one entry per solarSystem.bodies
    private final List<float[]> constellationEnu = new ArrayList<>();
    private float[] labelEnu = new float[0];              // 3 per constellation label

    private double sunAltitudeDeg = -90;
    private boolean loaded = false;
    private boolean everComputed = false;

    private double lastLat = Double.NaN, lastLon = Double.NaN;
    private long lastMillis = Long.MIN_VALUE;

    /** How stale the cached sky may get before a recompute (ms). The sky drifts ~0.004°/s. */
    private static final long RECOMPUTE_INTERVAL_MS = 10_000L;

    public void loadAssets() {
        if (loaded) return;
        stars.load();
        constellations.load();
        starEnu = new float[stars.count * 3];
        labelEnu = new float[constellations.labels.size() * 3];
        for (float[] poly : constellations.polylines) {
            constellationEnu.add(new float[poly.length / 2 * 3]);
        }
        bodyEnu = new float[solarSystem.bodies.size() * 3];
        loaded = true;
    }

    /**
     * Recomputes the sky if the observer or the clock has moved enough. Safe to call every frame.
     * @param latitudeDeg  observer latitude
     * @param longitudeDeg observer longitude, east positive
     * @param utcMillis     current time, milliseconds since the Unix epoch (UTC)
     */
    public void update(double latitudeDeg, double longitudeDeg, long utcMillis) {
        if (!loaded) loadAssets();
        boolean moved = Math.abs(latitudeDeg - lastLat) > 0.02 || Math.abs(longitudeDeg - lastLon) > 0.02;
        boolean timePassed = Math.abs(utcMillis - lastMillis) > RECOMPUTE_INTERVAL_MS;
        if (everComputed && !moved && !timePassed) return;
        recompute(latitudeDeg, longitudeDeg, utcMillis);
        lastLat = latitudeDeg;
        lastLon = longitudeDeg;
        lastMillis = utcMillis;
        everComputed = true;
    }

    /** Forces a recompute on the next {@link #update} (e.g. right after the observer teleports). */
    public void invalidate() {
        everComputed = false;
    }

    // Optional frozen "custom" time. Null means follow the device clock.
    private Long customTimeMillis = null;

    /** The time the sky should be computed for: a user-set custom instant, or the live device clock. */
    public long currentTimeMillis() {
        return customTimeMillis != null ? customTimeMillis : System.currentTimeMillis();
    }

    /** Freezes the sky at a specific instant (UTC millis since the epoch). */
    public void setCustomTimeMillis(long millis) {
        customTimeMillis = millis;
        invalidate();
    }

    /** Returns to the live device clock. */
    public void clearCustomTime() {
        customTimeMillis = null;
        invalidate();
    }

    public boolean hasCustomTime() {
        return customTimeMillis != null;
    }

    private void recompute(double latitudeDeg, double longitudeDeg, long utcMillis) {
        GregorianCalendar cal = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        cal.setTimeInMillis(utcMillis);
        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH) + 1;
        int day = cal.get(Calendar.DAY_OF_MONTH);
        double utHours = cal.get(Calendar.HOUR_OF_DAY)
                + cal.get(Calendar.MINUTE) / 60.0
                + cal.get(Calendar.SECOND) / 3600.0;

        double d = SkyMath.dayNumber(year, month, day, utHours);
        solarSystem.compute(d);
        double lst = SkyMath.localSiderealTimeDeg(solarSystem.getSunMeanLongitude(), utHours, longitudeDeg);

        // Bodies
        for (int i = 0; i < solarSystem.bodies.size(); i++) {
            SkyBody b = solarSystem.bodies.get(i);
            SkyMath.AzAlt aa = SkyMath.equatorialToHorizontal(b.raDeg, b.decDeg, lst, latitudeDeg);
            writeEnu(bodyEnu, i * 3, aa.azimuth, aa.altitude);
            if (b.kind == SkyBody.Kind.SUN) sunAltitudeDeg = aa.altitude;
        }

        // Stars
        for (int i = 0; i < stars.count; i++) {
            SkyMath.AzAlt aa = SkyMath.equatorialToHorizontal(stars.raDeg[i], stars.decDeg[i], lst, latitudeDeg);
            writeEnu(starEnu, i * 3, aa.azimuth, aa.altitude);
        }

        // Constellation polylines
        for (int p = 0; p < constellations.polylines.size(); p++) {
            float[] src = constellations.polylines.get(p);
            float[] dst = constellationEnu.get(p);
            for (int j = 0, k = 0; j < src.length; j += 2, k += 3) {
                SkyMath.AzAlt aa = SkyMath.equatorialToHorizontal(src[j], src[j + 1], lst, latitudeDeg);
                writeEnu(dst, k, aa.azimuth, aa.altitude);
            }
        }

        // Constellation name anchors
        for (int i = 0; i < constellations.labels.size(); i++) {
            ConstellationData.Label lab = constellations.labels.get(i);
            SkyMath.AzAlt aa = SkyMath.equatorialToHorizontal(lab.raDeg, lab.decDeg, lst, latitudeDeg);
            writeEnu(labelEnu, i * 3, aa.azimuth, aa.altitude);
        }

        // Named bright stars
        for (int i = 0; i < NAMED_STARS.length; i++) {
            NamedStar s = NAMED_STARS[i];
            SkyMath.AzAlt aa = SkyMath.equatorialToHorizontal(s.raDeg, s.decDeg, lst, latitudeDeg);
            writeEnu(namedStarEnu, i * 3, aa.azimuth, aa.altitude);
        }
    }

    public float[] getNamedStarEnu() { return namedStarEnu; }

    private static void writeEnu(float[] arr, int off, double azimuthDeg, double altitudeDeg) {
        double azr = Math.toRadians(azimuthDeg);
        double altr = Math.toRadians(altitudeDeg);
        double h = Math.cos(altr);
        arr[off] = (float) (h * Math.sin(azr));      // east
        arr[off + 1] = (float) (h * Math.cos(azr));  // north
        arr[off + 2] = (float) Math.sin(altr);       // up
    }

    // --- accessors for the renderer ---
    public boolean isLoaded() { return loaded; }
    public double getSunAltitudeDeg() { return sunAltitudeDeg; }
    public SolarSystem getSolarSystem() { return solarSystem; }
    public StarCatalog getStars() { return stars; }
    public float[] getStarEnu() { return starEnu; }
    public float[] getBodyEnu() { return bodyEnu; }
    public List<float[]> getConstellationEnu() { return constellationEnu; }
    public ConstellationData getConstellations() { return constellations; }
    public float[] getLabelEnu() { return labelEnu; }

    /** Direction towards the Sun as an ENU unit vector, for driving the terrain relief light. */
    public void getSunDirection(float[] out3) {
        out3[0] = bodyEnu.length >= 3 ? bodyEnu[0] : 0f;
        out3[1] = bodyEnu.length >= 3 ? bodyEnu[1] : 0f;
        out3[2] = bodyEnu.length >= 3 ? bodyEnu[2] : 1f;
    }
}
