package com.peaknav.sky;

/** A solar-system body's apparent equatorial position and brightness at a given time. */
public final class SkyBody {

    public enum Kind { SUN, MOON, PLANET }

    public final String name;
    public final Kind kind;
    public double raDeg;      // apparent right ascension, degrees
    public double decDeg;     // apparent declination, degrees
    public double magnitude;  // apparent visual magnitude (smaller = brighter)
    /** For the Moon: illuminated fraction 0..1 (0 = new, 1 = full); unused otherwise. */
    public double phase = 1.0;
    /**
     * For the Moon: the Sun–Moon elongation in degrees (0 = new, 180 = full); unused otherwise.
     * Note this is NOT the astronomical phase angle (which is 180° − elongation) nor a bright-limb
     * position angle — the field used to be named as if it were.
     */
    public double elongationDeg = 0.0;

    // Colour used when drawing the body's disc/dot (r,g,b in 0..1).
    public final float r, g, b;

    public SkyBody(String name, Kind kind, float r, float g, float b) {
        this.name = name;
        this.kind = kind;
        this.r = r;
        this.g = g;
        this.b = b;
    }
}
