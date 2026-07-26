package com.peaknav.sky;

import static com.peaknav.sky.SkyMath.RAD2DEG;
import static com.peaknav.sky.SkyMath.atan2d;
import static com.peaknav.sky.SkyMath.cosd;
import static com.peaknav.sky.SkyMath.obliquity;
import static com.peaknav.sky.SkyMath.rev;
import static com.peaknav.sky.SkyMath.sind;

import java.util.ArrayList;
import java.util.List;

/**
 * Geocentric positions of the Sun, Moon and the five naked-eye planets, using Paul Schlyter's
 * public-domain low-precision series (accurate to roughly an arc-minute for the Sun/planets and a
 * few arc-minutes for the Moon — far finer than a naked-eye sky view needs).
 */
public final class SolarSystem {

    public final SkyBody sun = new SkyBody("Sun", SkyBody.Kind.SUN, 1.0f, 0.93f, 0.55f);
    public final SkyBody moon = new SkyBody("Moon", SkyBody.Kind.MOON, 0.92f, 0.92f, 0.88f);
    public final SkyBody mercury = new SkyBody("Mercury", SkyBody.Kind.PLANET, 0.75f, 0.72f, 0.66f);
    public final SkyBody venus = new SkyBody("Venus", SkyBody.Kind.PLANET, 1.0f, 0.97f, 0.82f);
    public final SkyBody mars = new SkyBody("Mars", SkyBody.Kind.PLANET, 0.95f, 0.45f, 0.30f);
    public final SkyBody jupiter = new SkyBody("Jupiter", SkyBody.Kind.PLANET, 0.95f, 0.85f, 0.66f);
    public final SkyBody saturn = new SkyBody("Saturn", SkyBody.Kind.PLANET, 0.95f, 0.87f, 0.60f);

    /** All bodies, in a stable order (Sun, Moon, then planets). */
    public final List<SkyBody> bodies = new ArrayList<>();

    private double sunMeanLongitude; // Ls, needed for sidereal time
    // Sun's geocentric ecliptic rectangular coords (z = 0) and distance, reused for the planets.
    private double sunXs, sunYs, sunDist;

    public SolarSystem() {
        bodies.add(sun);
        bodies.add(moon);
        bodies.add(mercury);
        bodies.add(venus);
        bodies.add(mars);
        bodies.add(jupiter);
        bodies.add(saturn);
    }

    public double getSunMeanLongitude() {
        return sunMeanLongitude;
    }

    /** Recomputes every body for the given Schlyter day number. */
    public void compute(double d) {
        double oblecl = obliquity(d);
        computeSun(d, oblecl);
        computeMoon(d, oblecl);
        computePlanet(mercury, d, oblecl,
                48.3313 + 3.24587e-5 * d, 7.0047 + 5.00e-8 * d, 29.1241 + 1.01444e-5 * d,
                0.387098, 0.205635 + 5.59e-10 * d, 168.6562 + 4.0923344368 * d, -0.42);
        computePlanet(venus, d, oblecl,
                76.6799 + 2.46590e-5 * d, 3.3946 + 2.75e-8 * d, 54.8910 + 1.38374e-5 * d,
                0.723330, 0.006773 - 1.302e-9 * d, 48.0052 + 1.6021302244 * d, -4.40);
        computePlanet(mars, d, oblecl,
                49.5574 + 2.11081e-5 * d, 1.8497 - 1.78e-8 * d, 286.5016 + 2.92961e-5 * d,
                1.523688, 0.093405 + 2.516e-9 * d, 18.6021 + 0.5240207766 * d, -1.52);
        computePlanet(jupiter, d, oblecl,
                100.4542 + 2.76854e-5 * d, 1.3030 - 1.557e-7 * d, 273.8777 + 1.64505e-5 * d,
                5.20256, 0.048498 + 4.469e-9 * d, 19.8950 + 0.0830853001 * d, -9.40);
        computePlanet(saturn, d, oblecl,
                113.6634 + 2.38980e-5 * d, 2.4886 - 1.081e-7 * d, 339.3939 + 2.97661e-5 * d,
                9.55475, 0.055546 - 9.499e-9 * d, 316.9670 + 0.0334442282 * d, -8.88);
    }

    private void computeSun(double d, double oblecl) {
        double w = 282.9404 + 4.70935e-5 * d;
        double e = 0.016709 - 1.151e-9 * d;
        double M = rev(356.0470 + 0.9856002585 * d);
        sunMeanLongitude = rev(w + M);

        double E = M + e * RAD2DEG * sind(M) * (1 + e * cosd(M));
        double xv = cosd(E) - e;
        double yv = Math.sqrt(1 - e * e) * sind(E);
        double v = atan2d(yv, xv);
        double r = Math.sqrt(xv * xv + yv * yv);
        double lon = rev(v + w);

        sunXs = r * cosd(lon);
        sunYs = r * sind(lon);
        sunDist = r;

        double xe = sunXs;
        double ye = sunYs * cosd(oblecl);
        double ze = sunYs * sind(oblecl);
        sun.raDeg = rev(atan2d(ye, xe));
        sun.decDeg = atan2d(ze, Math.sqrt(xe * xe + ye * ye));
        sun.magnitude = -26.7;
    }

    private void computeMoon(double d, double oblecl) {
        double N = 125.1228 - 0.0529538083 * d;
        double i = 5.1454;
        double w = 318.0634 + 0.1643573223 * d;
        double a = 60.2666;
        double e = 0.054900;
        double M = rev(115.3654 + 13.0649929509 * d);

        double E = M + e * RAD2DEG * sind(M) * (1 + e * cosd(M));
        for (int k = 0; k < 5; k++) {
            double dE = (E - e * RAD2DEG * sind(E) - M) / (1 - e * cosd(E));
            E -= dE;
            if (Math.abs(dE) < 1e-6) break;
        }
        double xv = a * (cosd(E) - e);
        double yv = a * Math.sqrt(1 - e * e) * sind(E);
        double v = atan2d(yv, xv);
        double r = Math.sqrt(xv * xv + yv * yv);

        double xh = r * (cosd(N) * cosd(v + w) - sind(N) * sind(v + w) * cosd(i));
        double yh = r * (sind(N) * cosd(v + w) + cosd(N) * sind(v + w) * cosd(i));
        double zh = r * sind(v + w) * sind(i);

        double lon = atan2d(yh, xh);
        double lat = atan2d(zh, Math.sqrt(xh * xh + yh * yh));

        // Perturbations (Schlyter). Ls = sun mean longitude, Ms = sun mean anomaly.
        double Ms = rev(356.0470 + 0.9856002585 * d);
        double Ls = sunMeanLongitude;
        double Mm = M;                       // Moon mean anomaly
        double Lm = rev(N + w + M);          // Moon mean longitude
        double D = rev(Lm - Ls);             // Moon mean elongation
        double F = rev(Lm - N);              // Moon argument of latitude

        lon += -1.274 * sind(Mm - 2 * D)
                + 0.658 * sind(2 * D)
                - 0.186 * sind(Ms)
                - 0.059 * sind(2 * Mm - 2 * D)
                - 0.057 * sind(Mm - 2 * D + Ms)
                + 0.053 * sind(Mm + 2 * D)
                + 0.046 * sind(2 * D - Ms)
                + 0.041 * sind(Mm - Ms)
                - 0.035 * sind(D)
                - 0.031 * sind(Mm + Ms)
                - 0.015 * sind(2 * F - 2 * D)
                + 0.011 * sind(Mm - 4 * D);
        lat += -0.173 * sind(F - 2 * D)
                - 0.055 * sind(Mm - F - 2 * D)
                - 0.046 * sind(Mm + F - 2 * D)
                + 0.033 * sind(F + 2 * D)
                + 0.017 * sind(2 * Mm + F);
        r += -0.58 * cosd(Mm - 2 * D) - 0.46 * cosd(2 * D);

        double xg = r * cosd(lon) * cosd(lat);
        double yg = r * sind(lon) * cosd(lat);
        double zg = r * sind(lat);

        double xe = xg;
        double ye = yg * cosd(oblecl) - zg * sind(oblecl);
        double ze = yg * sind(oblecl) + zg * cosd(oblecl);
        moon.raDeg = rev(atan2d(ye, xe));
        moon.decDeg = atan2d(ze, Math.sqrt(xe * xe + ye * ye));
        moon.magnitude = -11.0;

        // Illuminated fraction from the Sun-Moon elongation seen from Earth. Clamp the cosine to
        // [-1, 1] before acos: at (and very near) new moon floating-point error can nudge it just
        // past 1.0, which would make acos return NaN and the phase read as a spurious 0%/100%.
        double cosElong = sind(sun.decDeg) * sind(moon.decDeg)
                + cosd(sun.decDeg) * cosd(moon.decDeg) * cosd(sun.raDeg - moon.raDeg);
        cosElong = Math.max(-1.0, Math.min(1.0, cosElong));
        double elong = Math.acos(cosElong) * RAD2DEG;
        moon.phase = (1 - cosd(elong)) / 2.0;
        moon.phaseAngleDeg = elong;
    }

    private void computePlanet(SkyBody body, double d, double oblecl,
                               double N, double i, double w, double a, double e, double M, double h0) {
        M = rev(M);
        double E = M + e * RAD2DEG * sind(M) * (1 + e * cosd(M));
        for (int k = 0; k < 5; k++) {
            double dE = (E - e * RAD2DEG * sind(E) - M) / (1 - e * cosd(E));
            E -= dE;
            if (Math.abs(dE) < 1e-6) break;
        }
        double xv = a * (cosd(E) - e);
        double yv = a * Math.sqrt(1 - e * e) * sind(E);
        double v = atan2d(yv, xv);
        double r = Math.sqrt(xv * xv + yv * yv); // heliocentric distance

        double xh = r * (cosd(N) * cosd(v + w) - sind(N) * sind(v + w) * cosd(i));
        double yh = r * (sind(N) * cosd(v + w) + cosd(N) * sind(v + w) * cosd(i));
        double zh = r * sind(v + w) * sind(i);

        double lon = atan2d(yh, xh);
        double lat = atan2d(zh, Math.sqrt(xh * xh + yh * yh));

        // Jupiter & Saturn need perturbations from their mutual attraction for arc-minute accuracy.
        if (body == jupiter || body == saturn) {
            double Mj = rev(19.8950 + 0.0830853001 * d);
            double Ms = rev(316.9670 + 0.0334442282 * d);
            if (body == jupiter) {
                lon += -0.332 * sind(2 * Mj - 5 * Ms - 67.6)
                        - 0.056 * sind(2 * Mj - 2 * Ms + 21)
                        + 0.042 * sind(3 * Mj - 5 * Ms + 21)
                        - 0.036 * sind(Mj - 2 * Ms)
                        + 0.022 * cosd(Mj - Ms)
                        + 0.023 * sind(2 * Mj - 3 * Ms + 52)
                        - 0.016 * sind(Mj - 5 * Ms - 69);
            } else {
                lon += 0.812 * sind(2 * Mj - 5 * Ms - 67.6)
                        - 0.229 * cosd(2 * Mj - 4 * Ms - 2)
                        + 0.119 * sind(Mj - 2 * Ms - 3)
                        + 0.046 * sind(2 * Mj - 6 * Ms - 69)
                        + 0.014 * sind(Mj - 3 * Ms + 32);
                lat += -0.020 * cosd(2 * Mj - 4 * Ms - 2)
                        + 0.018 * sind(2 * Mj - 6 * Ms - 49);
            }
        }

        double xhc = r * cosd(lon) * cosd(lat);
        double yhc = r * sind(lon) * cosd(lat);
        double zhc = r * sind(lat);

        // Geocentric ecliptic = heliocentric planet + geocentric Sun.
        double xg = xhc + sunXs;
        double yg = yhc + sunYs;
        double zg = zhc;

        double xe = xg;
        double ye = yg * cosd(oblecl) - zg * sind(oblecl);
        double ze = yg * sind(oblecl) + zg * cosd(oblecl);
        body.raDeg = rev(atan2d(ye, xe));
        body.decDeg = atan2d(ze, Math.sqrt(xe * xe + ye * ye));

        // Apparent magnitude (Schlyter): h0 base + distance + phase-angle terms.
        double R = Math.sqrt(xg * xg + yg * yg + zg * zg); // geocentric distance
        double cosFV = (r * r + R * R - sunDist * sunDist) / (2 * r * R);
        cosFV = Math.max(-1, Math.min(1, cosFV));
        double FV = Math.acos(cosFV) * RAD2DEG; // phase angle, degrees
        double mag = h0 + 5 * Math.log10(r * R);
        if (body == venus) {
            mag += 0.0013 * FV + 4.2e-7 * FV * FV * FV;
        } else if (body == mercury) {
            mag += 0.027 * FV + 2.2e-13 * Math.pow(FV, 6);
        } else if (body == mars) {
            mag += 0.016 * FV;
        } else if (body == jupiter) {
            mag += 0.005 * FV;
        } else if (body == saturn) {
            mag += 0.044 * FV; // ring contribution omitted
        }
        body.magnitude = mag;
    }
}
