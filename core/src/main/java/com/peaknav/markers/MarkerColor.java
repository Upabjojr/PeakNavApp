package com.peaknav.markers;

/**
 * The colours a marker's flag can have: distinct from each other, and from the terrain they stand
 * on - no white for the snow, no brown for the rock. Each is kept in the GPX file by its name; the
 * three that GPS devices have a flag symbol for go into the standard {@code sym} as well.
 */
public enum MarkerColor {
    BLUE("#1f8ae0", "Flag, Blue"),
    RED("#d9342b", "Flag, Red"),
    GREEN("#2ea44f", "Flag, Green"),
    YELLOW("#f2c200", null),
    ORANGE("#f07c1a", null),
    PURPLE("#8e44c9", null);

    /** The cloth's colour, "#rrggbb". */
    public final String hex;
    /** The GPX symbol GPS devices draw for it; null where they have none. */
    public final String gpxSymbol;

    MarkerColor(String hex, String gpxSymbol) {
        this.hex = hex;
        this.gpxSymbol = gpxSymbol;
    }

    /** The name kept in the file, "blue". */
    public String key() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** The colour a file names, by its key or its GPX symbol; blue when it names none we know. */
    public static MarkerColor parse(String key, String gpxSymbol) {
        if (key != null) {
            for (MarkerColor c : values()) {
                if (c.key().equalsIgnoreCase(key.trim())) {
                    return c;
                }
            }
        }
        if (gpxSymbol != null) {
            for (MarkerColor c : values()) {
                if (c.gpxSymbol != null && c.gpxSymbol.equalsIgnoreCase(gpxSymbol.trim())) {
                    return c;
                }
            }
        }
        return BLUE;
    }
}
