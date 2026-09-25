package com.peaknav.viewer;

/**
 * The captions of the "?" tutorial, translated through the app's own catalogue.
 *
 * <p>The tutorial ({@link com.peaknav.viewer.widgets.TutorialOverlay}) names each slide by key
 * and asks the catalogue for the words - so there is one list of keys, one set of translations
 * in {@code assets/i18n/strings_*.properties}, and no English baked into the slideshow.
 *
 * <p>{@link #KEYS} is the whole contract: a slide uses {@code <key>} for its caption and
 * {@code <key>_detail} for the line under it. The slide order lives with the script that
 * writes the slides, {@code tools/tutorial_slides.py}.
 */
public final class TutorialStrings {

    /** Every caption key the tutorial can ask for, in slide order. */
    public static final String[] KEYS = {
            "Tutorial_welcome",
            "Tutorial_gyroscope",
            "Tutorial_elevation",
            "Tutorial_search",
            "Tutorial_here",
            "Tutorial_share",
            "Tutorial_options",
            "Tutorial_options_pane",
            "Tutorial_satellite",
            "Tutorial_trails",
            "Tutorial_pistes",
            "Tutorial_sky",
            "Tutorial_tap",
            "Tutorial_go_to",
            "Tutorial_orbit",
            "Tutorial_open_maps",
            "Tutorial_route",
            "Tutorial_route_result",
            "Tutorial_gpx",
            "Tutorial_gpx_play",
            "Tutorial_gpx_stats",
            "Tutorial_gpx_share",
            "Tutorial_gallery",
            "Tutorial_camera",
            "Tutorial_photo_match",
            "Tutorial_photo_outlines",
            "Tutorial_photo_terrain",
            "Tutorial_photo_pin",
            "Tutorial_photo_close",
            "Tutorial_help",
    };

    private TutorialStrings() {
    }

}
