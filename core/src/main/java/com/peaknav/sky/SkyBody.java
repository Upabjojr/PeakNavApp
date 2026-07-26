package com.peaknav.sky;

/** A solar-system body's apparent equatorial position and brightness at a given time. */
public final class SkyBody {

    public enum Kind { SUN, MOON, PLANET }

    public final String name;
    public final Kind kind;
    public double raDeg;      // apparent right ascension, degrees
    public double decDeg;     // apparent declination, degrees
    public double magnitude;  // apparent visual magnitude (smaller = brighter)
    /** For the Moon: illuminated fraction 0..1 and the bright-limb position angle; unused otherwise. */
    public double phase = 1.0;
    public double phaseAngleDeg = 0.0;

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
