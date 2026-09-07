package com.peaknav.sky;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/**
 * The sky as seen from a place at an instant, in the form the star matcher wants: a
 * direction in the local horizon frame (east, north, up) and a magnitude for every
 * catalogue star and for each planet bright enough to be mistaken for one. The Sun and the
 * Moon are left out: neither is a point in a photograph.
 *
 * <p>Pure computation on the catalogue's arrays, so it runs on any thread and in tests
 * without libGDX; the same conversions {@link SkyModel} uses for drawing.
 */
public final class StarPositions {

    /** East-north-up unit vectors, three per entry. */
    public final float[] enu;
    /** Apparent magnitudes, one per entry (smaller is brighter). */
    public final float[] mag;
    public final int count;
    /** Planet names for the entries that are planets, null for catalogue stars. */
    public final String[] name;

    private StarPositions(float[] enu, float[] mag, String[] name, int count) {
        this.enu = enu;
        this.mag = mag;
        this.name = name;
        this.count = count;
    }

    /**
     * @param stars        the catalogue (its arrays are read, nothing else)
     * @param latitudeDeg  the observer's latitude
     * @param longitudeDeg the observer's longitude, east positive
     * @param utcMillis    the instant, milliseconds since the epoch
     * @param faintestMag  stars fainter than this are left out
     */
    public static StarPositions compute(StarCatalog stars, double latitudeDeg, double longitudeDeg, long utcMillis,
                                        float faintestMag) {
        GregorianCalendar cal = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        cal.setTimeInMillis(utcMillis);
        double utHours = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0
                + cal.get(Calendar.SECOND) / 3600.0;
        double d = SkyMath.dayNumber(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH), utHours);
        SolarSystem solarSystem = new SolarSystem();
        solarSystem.compute(d);
        double lst = SkyMath.localSiderealTimeDeg(solarSystem.getSunMeanLongitude(), utHours, longitudeDeg);

        int max = stars.count + solarSystem.bodies.size();
        float[] enu = new float[max * 3];
        float[] mag = new float[max];
        String[] name = new String[max];
        int n = 0;
        for (int i = 0; i < stars.count; i++) {
            if (stars.mag[i] > faintestMag) {
                continue;
            }
            SkyMath.AzAlt aa = SkyMath.equatorialToHorizontal(stars.raDeg[i], stars.decDeg[i], lst, latitudeDeg);
            write(enu, n * 3, aa);
            mag[n] = stars.mag[i];
            n++;
        }
        for (SkyBody b : solarSystem.bodies) {
            if (b.kind != SkyBody.Kind.PLANET || b.magnitude > faintestMag) {
                continue;
            }
            SkyMath.AzAlt aa = SkyMath.equatorialToHorizontal(b.raDeg, b.decDeg, lst, latitudeDeg);
            write(enu, n * 3, aa);
            mag[n] = (float) b.magnitude;
            name[n] = b.name;
            n++;
        }
        return new StarPositions(enu, mag, name, n);
    }

    private static void write(float[] arr, int off, SkyMath.AzAlt aa) {
        double az = Math.toRadians(aa.azimuth), alt = Math.toRadians(aa.altitude);
        double h = Math.cos(alt);
        arr[off] = (float) (h * Math.sin(az));
        arr[off + 1] = (float) (h * Math.cos(az));
        arr[off + 2] = (float) Math.sin(alt);
    }
}
