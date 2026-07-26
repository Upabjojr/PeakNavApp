package com.peaknav.sky;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;

import java.util.ArrayList;
import java.util.List;

/**
 * Constellation stick-figures and names from {@code assets/sky/constellations.dat}.
 * Derived from d3-celestial (© 2015 Olaf Frohn, BSD-2-Clause) — see the header line in that file.
 *
 * <p>Lines: {@code L ra dec ra dec ...} is one polyline (RA/Dec pairs, degrees).
 * Names: {@code N ra dec Name}.
 */
public final class ConstellationData {

    /** Each entry is a flat [ra0,dec0, ra1,dec1, ...] polyline in degrees. */
    public final List<float[]> polylines = new ArrayList<>();

    public static final class Label {
        public final float raDeg, decDeg;
        public final String name;
        public Label(float raDeg, float decDeg, String name) {
            this.raDeg = raDeg;
            this.decDeg = decDeg;
            this.name = name;
        }
    }

    public final List<Label> labels = new ArrayList<>();

    public void load() {
        try {
            FileHandle f = Gdx.files.internal("sky/constellations.dat");
            if (!f.exists()) return;
            for (String line : f.readString("UTF-8").split("\n")) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                if (line.charAt(0) == 'N') {
                    String[] p = line.split(" ", 4);
                    if (p.length >= 4) {
                        labels.add(new Label(Float.parseFloat(p[1]), Float.parseFloat(p[2]), p[3].trim()));
                    }
                } else if (line.charAt(0) == 'L') {
                    String[] p = line.trim().split("\\s+");
                    int count = (p.length - 1) / 2 * 2; // even number of coords after the 'L'
                    if (count < 4) continue;
                    float[] poly = new float[count];
                    for (int i = 0; i < count; i++) {
                        poly[i] = Float.parseFloat(p[1 + i]);
                    }
                    polylines.add(poly);
                }
            }
        } catch (Exception e) {
            System.err.println("[Sky] failed to load constellations: " + e.getMessage());
        }
    }
}
