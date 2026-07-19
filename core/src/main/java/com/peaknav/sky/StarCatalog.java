package com.peaknav.sky;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;

/**
 * Naked-eye stars from the Yale Bright Star Catalogue (public domain), trimmed to
 * {@code assets/sky/stars.dat} as one "RAdeg Decdeg magnitude" record per line, brightest first.
 */
public final class StarCatalog {

    public float[] raDeg = new float[0];
    public float[] decDeg = new float[0];
    public float[] mag = new float[0];
    public int count = 0;

    public void load() {
        try {
            FileHandle f = Gdx.files.internal("sky/stars.dat");
            if (!f.exists()) return;
            String[] lines = f.readString("UTF-8").split("\n");
            raDeg = new float[lines.length];
            decDeg = new float[lines.length];
            mag = new float[lines.length];
            int n = 0;
            for (String line : lines) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                int a = line.indexOf(' ');
                int b = line.indexOf(' ', a + 1);
                if (a < 0 || b < 0) continue;
                raDeg[n] = Float.parseFloat(line.substring(0, a));
                decDeg[n] = Float.parseFloat(line.substring(a + 1, b));
                mag[n] = Float.parseFloat(line.substring(b + 1).trim());
                n++;
            }
            count = n;
        } catch (Exception e) {
            System.err.println("[Sky] failed to load stars: " + e.getMessage());
        }
    }
}
