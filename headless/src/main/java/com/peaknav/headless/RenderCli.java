package com.peaknav.headless;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Command-line front end for {@link PeakNavRenderer}, so views can be produced from a
 * script or a CI job without writing Java.
 *
 * <pre>
 * peaknav-headless --lat 46.0207 --lon 7.7491 --bearing 230 --out matterhorn.png
 * </pre>
 *
 * Several {@code --shot} arguments may be given to capture more than one view from a single
 * boot, which is much faster than starting the app once per image - start-up and the initial
 * tile load dominate the cost.
 *
 * <pre>
 * peaknav-headless --lat 46.0207 --lon 7.7491 \
 *     --shot 230,-4,matterhorn-sw.png --shot 20,0,zermatt-n.png
 * </pre>
 */
public final class RenderCli {

    private RenderCli() {}

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            // The app installs a crash logger whose desktop implementation does nothing,
            // and MapApp.render() swallows every Throwable through it. Without this,
            // a failure here leaves no trace anywhere at all.
            System.err.println("peaknav-headless failed:");
            t.printStackTrace();
            System.out.flush();
            System.err.flush();
            Runtime.getRuntime().halt(1);
        }
        System.out.flush();
        // halt, not exit: several of the app's thread pools are non-daemon, so a normal
        // return leaves the JVM alive forever waiting for them.
        Runtime.getRuntime().halt(0);
    }

    private static void run(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        List<String> shots = new ArrayList<>();
        List<String> frames = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                fail("unexpected argument: " + arg);
            }
            if (arg.equals("--help") || arg.equals("-h")) {
                usage();
                return;
            }
            if (i + 1 >= args.length) {
                fail("missing value for " + arg);
            }
            String value = args[++i];
            if (arg.equals("--shot")) {
                shots.add(value);
            } else if (arg.equals("--frame")) {
                frames.add(value);
            } else {
                options.put(arg.substring(2), value);
            }
        }

        double latitude = required(options, "lat");
        double longitude = required(options, "lon");
        int width = (int) optional(options, "width", 1600);
        int height = (int) optional(options, "height", 1000);
        long settle = (long) optional(options, "settle", 25_000);
        // Parsed here rather than where it is used, which is after the app has started. Two
        // reasons: a typo is worth hearing about now instead of three minutes into a boot, and
        // once the app is up an error message does not survive the exit - the shutdown path
        // discards buffered output, so a late failure is a silent one.
        Long skyTimeMillis = options.containsKey("sky-time")
                ? parseSkyTime(options.get("sky-time")) : null;

        boolean serve = options.containsKey("serve");
        if (shots.isEmpty() && !frames.isEmpty()) {
            // Frames carry their own everything; no --shot needed.
            shots.add("0,0,/dev/null");
        }
        if (shots.isEmpty() && !serve) {
            // The single-shot form; --out is only required when no --shot was given.
            String out = options.get("out");
            if (out == null) {
                fail("give either --out, at least one --shot, or --serve");
            }
            shots.add(optional(options, "bearing", 0) + "," + optional(options, "pitch", 0) + "," + out);
        }

        System.out.printf("PeakNav headless renderer: %.5f, %.5f at %dx%d%n",
                latitude, longitude, width, height);

        try (PeakNavRenderer renderer = PeakNavRenderer.start(width, height, java.util.Locale.forLanguageTag(
                optionalString(options, "language", "en")))) {
            renderer.setImageFormat(options.get("format"));
            renderer.moveTo(latitude, longitude);

            applyToggle(options, "sky", renderer::setSky);
            applyToggle(options, "constellations", renderer::setConstellations);
            applyToggle(options, "sky-grid", renderer::setSkyGrid);
            applyToggle(options, "ecliptic", renderer::setSkyEcliptic);
            applyToggle(options, "star-names", renderer::setStarNames);
            applyToggle(options, "sky-labels", renderer::setSkyLabels);
            applyToggle(options, "sky-time-label", renderer::setSkyTimeLabel);
            if (skyTimeMillis != null) {
                renderer.setSkyTimeMillis(skyTimeMillis);
            }
            applyToggle(options, "sun-shading", renderer::setSunShading);
            if (options.containsKey("sky-mode")) {
                String mode = options.get("sky-mode").trim().toLowerCase();
                int value = mode.equals("day") ? 1 : mode.equals("night") ? 2
                        : mode.equals("local") ? 0 : -1;
                if (value < 0) {
                    fail("--sky-mode wants local, day or night; got: " + mode);
                }
                renderer.setSkyMode(value);
            }
            applyToggle(options, "horizon-compass", renderer::setHorizonCompass);
            applyToggle(options, "coordinates", renderer::setShowCoordinates);
            applyToggle(options, "corner-compass", renderer::setCornerCompass);

            // With no --labels the app keeps its stored preferences, which normally have at
            // least peaks on; an explicit empty list means "no labels", and then waiting for
            // one to appear would only ever time out.
            boolean labelsExpected = true;
            if (options.containsKey("labels")) {
                // Whitelist: everything off, then only what was named back on.
                renderer.clearLabels();
                labelsExpected = false;
                for (String name : options.get("labels").split(",")) {
                    name = name.trim();
                    if (name.isEmpty()) {
                        continue;
                    }
                    try {
                        renderer.setLabel(
                                PeakNavRenderer.Label.valueOf(name.toUpperCase()), true);
                        labelsExpected = true;
                    } catch (IllegalArgumentException e) {
                        fail("unknown label '" + name + "'; known: "
                                + java.util.Arrays.toString(PeakNavRenderer.Label.values())
                                        .toLowerCase());
                    }
                }
            }

            if (options.containsKey("download")) {
                long timeout = (long) optional(options, "download", 600_000);
                System.out.println("  downloading any missing map data (up to " + timeout + "ms)");
                boolean ok = renderer.downloadMissingData(latitude, longitude, timeout);
                System.out.println(ok
                        ? "  map data present"
                        : "  WARNING: download did not finish in time; the view may have gaps");
            }

            if (options.containsKey("await")) {
                long timeout = (long) optional(options, "await", 60_000);
                System.out.println("  waiting up to " + timeout + "ms for the view to finish");
                boolean quiet = renderer.awaitTilesLoaded(timeout);
                System.out.println(quiet
                        ? "  view settled (tiles + satellite imagery)"
                        : "  WARNING: timed out with tiles still arriving; image may be incomplete");
            } else {
                System.out.println("  waiting " + settle + "ms for tiles to stream in");
                renderer.settle(settle);
            }

            // Elevation is applied only now, after the wait: while tiles load, the app flies
            // the camera onto the terrain itself, and that fly overwrites any height set
            // earlier. (This looked like an inverted elevation axis for a while - which
            // framing survived depended on timing, not on the value.)
            if (options.containsKey("elevation-m")) {
                renderer.setElevationMeters(optional(options, "elevation-m", 0));
            } else if (options.containsKey("elevation")) {
                renderer.setElevation((float) optional(options, "elevation", 0));
            }

            if (!frames.isEmpty()) {
                // How long each frame waits before the shutter. The defaults are sized for a
                // lone still after a long jump; a video's frames sit a fraction of a degree
                // apart with every tile already resident, and there the same waits are almost
                // pure idleness - measured at 3.3 s of a 3.8 s frame, seven eighths of the
                // whole render. The values are per-run knobs rather than hard-coded economies
                // so a still stays as patient as it always was.
                long frameQuiet = (long) optional(options, "frame-quiet", 2_000);
                long frameSettle = (long) optional(options, "frame-settle", 1_200);
                int labelRefresh = (int) optional(options, "label-refresh", 0);
                renderFrames(renderer, frames, frameQuiet, frameSettle, labelRefresh);
                return;
            }

            if (serve) {
                // REST mode: the boot options above still apply as the starting state,
                // then everything else is driven over HTTP. Port 0 asks the OS for a
                // free one; the marker line is what a client parses to find it, so its
                // format is part of the API.
                int port;
                try {
                    port = new RestServer(renderer, options.get("format"))
                            .start((int) optional(options, "serve", 0));
                } catch (java.io.IOException e) {
                    throw new RuntimeException("could not bind the REST server", e);
                }
                System.out.println("PEAKNAV_SERVE port=" + port);
                System.out.flush();
                try {
                    // Until /shutdown halts the JVM.
                    Thread.currentThread().join();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return;
            }

            for (String shot : shots) {
                // bearing,pitch,file - or bearing,pitch,elevation,file to also set the
                // viewpoint height for this shot (elevation carries a dot, so the file
                // is whatever follows the third comma). A height written with a trailing
                // "m" is metres above the ground - 2500m - rather than a bar fraction.
                String[] parts = shot.split(",", 4);
                if (parts.length < 3) {
                    fail("--shot wants bearing,pitch[,elevation],file - got: " + shot);
                }
                float bearing = Float.parseFloat(parts[0].trim());
                float pitch = Float.parseFloat(parts[1].trim());
                String shotElevation = null;
                File output;
                if (parts.length == 4) {
                    shotElevation = parts[2].trim();
                    output = new File(parts[3].trim());
                } else {
                    output = new File(parts[2].trim());
                }

                if (shotElevation != null) {
                    if (shotElevation.endsWith("m")) {
                        renderer.setElevationMeters(Double.parseDouble(
                                shotElevation.substring(0, shotElevation.length() - 1)));
                    } else {
                        renderer.setElevation(Float.parseFloat(shotElevation));
                    }
                }
                renderer.aim(bearing, pitch);
                // A turn brings previously off-screen terrain into view, so give the
                // streaming a moment before reading the frame.
                renderer.settle(shots.size() > 1 ? 6_000 : 1_500);
                if (labelsExpected && !renderer.awaitLabelsRendered(30_000)) {
                    System.out.println("  WARNING: no label appeared within 30s; capturing anyway");
                }
                renderer.capture(output);
                System.out.printf("  wrote %s (bearing %.0f, pitch %.0f%s)%n",
                        output.getAbsolutePath(), bearing, pitch,
                        shotElevation == null ? "" : ", elevation " + shotElevation);
            }
        }
        System.out.println("done");
    }

    /** Applies an on/off flag only when the caller actually passed it. */
    private static void applyToggle(Map<String, String> options, String name,
                                    java.util.function.Consumer<Boolean> setter) {
        String value = options.get(name);
        if (value == null) {
            return;
        }
        if (value.equalsIgnoreCase("on") || value.equalsIgnoreCase("true")) {
            setter.accept(true);
        } else if (value.equalsIgnoreCase("off") || value.equalsIgnoreCase("false")) {
            setter.accept(false);
        } else {
            fail("--" + name + " wants on or off, got: " + value);
        }
    }

    private static double required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null) {
            fail("--" + name + " is required");
        }
        return Double.parseDouble(value);
    }

    /**
     * Renders a moving camera: each frame carries its own position, so a whole orbit or
     * fly-through comes out of one boot. Without this the position is fixed for the run and a
     * 360-frame orbit would mean 360 app starts.
     *
     * <p>Each frame is {@code lat,lon,bearing,pitch,height,file}, where height is
     * {@code 4200asl} for an absolute altitude or {@code 600m} for a height above the ground.
     * A flight wants the absolute form: held at a fixed height above the terrain, the camera
     * rides up over every ridge and its subject bobs in the frame.
     */
    private static void renderFrames(PeakNavRenderer renderer, List<String> frames,
                                     long quietMillis, long settleMillis, int labelRefresh) {
        if (labelRefresh > 0) {
            // The camera moves every frame; left to its own triggers the label set would
            // reshuffle several times a second of video, which plays back as flicker.
            // Updates are held and forced only every labelRefresh-th frame - the caller
            // picks the cadence from the video's frame rate, e.g. fps/2 for every half
            // second. The count below is the frame's position in the WHOLE video, parsed
            // from its output name, not its position in this boot: a resumed run must
            // refresh on the same frames as an uninterrupted one, or the two halves of a
            // video would carry different label rhythms.
            renderer.setLabelAutoUpdate(false);
            renderer.refreshLabels();
        }
        int index = 0;
        boolean lastQuiet = false;
        double prevLat = Double.NaN, prevLon = Double.NaN;
        for (String frame : frames) {
            String[] p = frame.split(",", 6);
            if (p.length < 6) {
                fail("--frame wants lat,lon,bearing,pitch,height,file - got: " + frame);
            }
            double lat = Double.parseDouble(p[0].trim());
            double lon = Double.parseDouble(p[1].trim());
            float bearing = Float.parseFloat(p[2].trim());
            float pitch = Float.parseFloat(p[3].trim());
            String height = p[4].trim();
            File output = new File(p[5].trim());

            boolean refreshFrame = labelRefresh > 0 && frameNumber(output) % labelRefresh == 0;
            // Whether this frame may skip the wait ritual entirely. Three conditions,
            // all required: label throttling is on (a still gets its full patience);
            // the LAST wait actually reached quiet (a timed-out wait means tiles were
            // still arriving, so keep waiting each frame until they stop); and the
            // camera step is negligible - a video frame moves metres, a chunk's first
            // frame jumps kilometres from the boot position and must stream a whole
            // new view in. Between checkpoints a tile or texture may land mid-frame;
            // the next refresh frame's full wait re-syncs everything, so any such
            // seam lives for at most half a second of video.
            boolean negligibleStep = !Double.isNaN(prevLat)
                    && stepMeters(prevLat, prevLon, lat, lon) < NEGLIGIBLE_STEP_M;
            boolean skipWaits = labelRefresh > 0 && !refreshFrame
                    && lastQuiet && negligibleStep;

            if (labelRefresh > 0) {
                // Video mode: the target - the world's anchor, which terrain vertices
                // and label positions are all scaled against - stays where the boot
                // put it for the whole chunk. Only the camera moves, in that frozen
                // frame. Re-anchoring per frame made the world snap sideways every
                // couple of frames (see PeakNavRenderer.placeCamera).
                renderer.placeCamera(lat, lon);
            } else {
                renderer.moveTo(lat, lon);
                // The one wait stills need their arrival: the camera must actually be
                // AT the target, or the frame is taken from wherever the previous
                // target left it.
                renderer.awaitTargetReached(30_000);
            }
            if (!skipWaits) {
                // The move streams new terrain in; capturing before it arrives is what
                // makes a flight flicker, so the frame waits for the view to go quiet.
                lastQuiet = renderer.awaitTilesLoaded(60_000, quietMillis);
            }
            applyHeight(renderer, height);
            renderer.aim(bearing, pitch);
            if (refreshFrame) {
                // Wait for the pass to finish: capturing mid-pass bakes a half-built
                // label set into this frame and every frame until the next refresh.
                renderer.refreshLabelsAndWait(10_000);
            }
            if (!skipWaits) {
                renderer.settle(settleMillis);
            }
            renderer.capture(output);
            prevLat = lat;
            prevLon = lon;
            index++;
            if (index % 10 == 0 || index == frames.size()) {
                System.out.printf("  %d/%d frames%n", index, frames.size());
                System.out.flush();
            }
        }
    }

    private static void applyHeight(PeakNavRenderer renderer, String height) {
        if (height.endsWith("asl")) {
            renderer.setAltitudeMeters(
                    Double.parseDouble(height.substring(0, height.length() - 3)));
        } else if (height.endsWith("m")) {
            renderer.setElevationMeters(
                    Double.parseDouble(height.substring(0, height.length() - 1)));
        } else {
            renderer.setElevation(Float.parseFloat(height));
        }
    }

    private static String optionalString(Map<String, String> options, String name,
                                         String fallback) {
        String value = options.get(name);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static double optional(Map<String, String> options, String name, double fallback) {
        String value = options.get(name);
        return value == null ? fallback : Double.parseDouble(value);
    }

    /**
     * The instant to freeze the sky at: either {@code 2026-08-01T09:30:00Z} or plain UTC
     * milliseconds. Always UTC - a local-time reading would put the Sun somewhere else
     * depending on the machine's zone, which is the sort of difference nobody notices until
     * two halves of a video disagree.
     */
    private static long parseSkyTime(String value) {
        String text = value == null ? "" : value.trim();
        try {
            if (text.matches("-?\\d+")) {
                return Long.parseLong(text);
            }
            return java.time.Instant.parse(text).toEpochMilli();
        } catch (RuntimeException notATime) {
            fail("--sky-time wants an ISO-8601 UTC instant such as 2026-08-01T09:30:00Z, "
                    + "or milliseconds since the epoch; got: " + value);
            return 0;   // unreachable: fail() exits
        }
    }

    /**
     * The camera step below which a frame may trust the previous frame's tiles: video
     * frames move metres between captures and their terrain is already resident, while
     * a chunk's opening frame jumps from the boot position and needs the full wait.
     * Generous relative to any real video step (orbits ~230 m, flights ~45 m) and far
     * below any inter-chunk jump.
     */
    private static final double NEGLIGIBLE_STEP_M = 1000;

    /** Ground distance in metres, equirectangular - exact enough to classify a step. */
    private static double stepMeters(double lat1, double lon1, double lat2, double lon2) {
        double ky = 111_320;
        double kx = ky * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        double dy = (lat2 - lat1) * ky;
        double dx = (lon2 - lon1) * kx;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * The frame's number in the whole video, from its file name (f00123.jpg). Falls
     * back to the running index when the name carries no number - then a resumed run
     * may phase its label refreshes differently from a fresh one, which is why the
     * video scripts always number their frames.
     */
    private static int frameNumber(File output) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+)\\.[A-Za-z]+$").matcher(output.getName());
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static void fail(String message) {
        System.err.println("error: " + message);
        usage();
        System.exit(2);
    }

    private static void usage() {
        System.err.println(
                "usage: peaknav-headless --lat <deg> --lon <deg> [options]\n"
                        + "\n"
                        + "  --out <file>              write one PNG (uses --bearing / --pitch)\n"
                        + "  --shot <bearing,pitch,file>   capture a view; may be repeated\n"
                        + "  --bearing <deg>           compass bearing, 0 = north (default 0)\n"
                        + "  --pitch <deg>             positive looks up (default 0)\n"
                        + "  --width <px>              default 1600\n"
                        + "  --height <px>             default 1000\n"
                        + "  --settle <ms>             fixed wait after moving (default 25000)\n"
                        + "  --await <ms>              instead of --settle, wait until tiles stop\n"
                        + "                            arriving, giving up after this long\n"
                        + "  --download <ms>           fetch any map data this area is missing\n"
                        + "                            first, waiting up to this long\n"
                        + "  --format png|jpg          output format, overriding the file's\n"
                        + "                            extension; jpg for video frames\n"
                        + "  --frame lat,lon,bearing,pitch,height,file\n"
                        + "                            one frame of a moving camera; repeatable,\n"
                        + "                            all from a single boot. height is 4200asl\n"
                        + "                            (above sea level) or 600m (above ground)\n"
                        + "  --language <tag>          language for labels and interface text\n"
                        + "                            (default en; the app itself follows the\n"
                        + "                            system language, a render should not)\n"
                        + "  --elevation-m <metres>    viewpoint height above the ground, in\n"
                        + "                            metres; prefer this one\n"
                        + "  --elevation <0..1>        viewpoint height as a position on the\n"
                        + "                            app's elevation bar, whose curve is\n"
                        + "                            steep: 0.45 is about 2.5 km up. A shot's\n"
                        + "                            own height may use either form, with a\n"
                        + "                            trailing m meaning metres:\n"
                        + "                              --shot 210,-4,2500m,out.png\n"
                        + "  --labels <csv>            only these: peaks, place_names, cities,\n"
                        + "                            mountain_ranges, islands, lakes,\n"
                        + "                            alpine_huts, roads, pistes, navigation\n"
                        + "  --sky on|off              draw the sky\n"
                        + "  --constellations on|off   constellation lines\n"
                        + "  --sky-grid on|off         equatorial grid over the sky\n"
                        + "  --ecliptic on|off         the ecliptic\n"
                        + "  --star-names on|off       names beside the bright stars\n"
                        + "  --serve <port>            start a REST server on 127.0.0.1 instead of\n"
                        + "                            rendering shots; 0 picks a free port, printed\n"
                        + "                            as PEAKNAV_SERVE port=N; see /openapi.json\n"
                        + "  --frame-quiet <ms>        tile silence required before each --frame\n"
                        + "                            is captured (default 2000; videos use\n"
                        + "                            less, their frames nearly share a view)\n"
                        + "  --frame-settle <ms>       extra pause before each --frame's shutter\n"
                        + "                            (default 1200)\n"
                        + "  --label-refresh <n>       recompute which labels show only every\n"
                        + "                            n-th frame (by the frame number in its\n"
                        + "                            file name); stops label flicker in videos.\n"
                        + "                            Frames between refreshes also skip the\n"
                        + "                            tile-quiet wait and settle when the camera\n"
                        + "                            moved under 1 km and the last wait was\n"
                        + "                            quiet - much faster for dense videos\n"
                        + "  --sky-time-label on|off   the date-and-time pill shown while the\n"
                        + "                            sky is frozen (see --sky-time)\n"
                        + "  --sky-time <instant>      freeze the sky and sunlight at one time,\n"
                        + "                            e.g. 2026-08-01T09:30:00Z (UTC), so that a\n"
                        + "                            render does not depend on when it is run\n"
                        + "  --sky-labels on|off       every caption on the sky at once:\n"
                        + "                            constellation, star and planet names\n"
                        + "  --sun-shading on|off      shade terrain by sun position\n"
                        + "  --horizon-compass on|off  the compass strip across the horizon\n"
                        + "  --coordinates on|off      the coordinates pill at the bottom\n"
                        + "  --corner-compass on|off   the compass rose in the corner\n"
                        + "  --sky-mode local|day|night  light the terrain by local time (the\n"
                        + "                            default), or force daylight/night\n");
    }
}
