package com.peaknav.viewer;

import java.util.ArrayList;
import java.util.List;

/**
 * The picture files the tutorial page asks for, read from the page itself.
 *
 * <p>{@code assets/info/app_tutorial.html} carries its slides as a JSON block, each naming an
 * image; every platform then hands those files in through {@code get_image()} (base64 on the
 * phones, a temp directory on the desktop). Reading the names from the page keeps the three
 * platforms from carrying their own copy of the list, which went stale whenever the slides
 * changed - a slide whose file was missing from a platform's list showed no picture at all.
 */
public final class TutorialImages {

    private TutorialImages() {
    }

    /** Every distinct {@code "image": "name"} of the page, in the order the slides use them. */
    public static List<String> namesIn(String html) {
        List<String> names = new ArrayList<>();
        String marker = "\"image\"";
        int at = 0;
        while (true) {
            at = html.indexOf(marker, at);
            if (at < 0) {
                return names;
            }
            at += marker.length();
            int colon = html.indexOf(':', at);
            int open = colon < 0 ? -1 : html.indexOf('"', colon);
            int close = open < 0 ? -1 : html.indexOf('"', open + 1);
            if (close < 0) {
                return names;
            }
            String name = html.substring(open + 1, close);
            if (!name.isEmpty() && !names.contains(name)) {
                names.add(name);
            }
            at = close;
        }
    }
}
