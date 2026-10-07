package com.peaknav.viewer.labels;

/** Where the platform's {@link LabelTextRasterizer} is kept; the launchers install it. */
public final class LabelTextRasterizers {

    private LabelTextRasterizers() {
    }

    private static volatile LabelTextRasterizer instance;

    /** Installs the platform's rasterizer; call before the app starts. */
    public static void set(LabelTextRasterizer rasterizer) {
        instance = rasterizer;
    }

    /** The platform's rasterizer, or null where there is none. */
    public static LabelTextRasterizer get() {
        return instance;
    }
}
