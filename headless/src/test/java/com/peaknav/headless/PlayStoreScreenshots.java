package com.peaknav.headless;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.peaknav.gpx.GpxParser;
import com.peaknav.gpx.GpxTrack;
import com.peaknav.utils.PreferencesManager.UnitSystem;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Renders the Play Store phone screenshots: the app as it looks on a phone held upright or
 * sideways, with its buttons drawn, at the places and on the features worth showing - peaks
 * named around the world, the night sky, GPX tours with their info pane, route to here, ski
 * runs and lifts, photo matching and the options menu.
 *
 * <p>Not a test: it does nothing unless asked, so a normal test run skips it.
 *
 * <pre>
 *   PEAKNAV_STORE_SCREENSHOTS=android/PeakNav_PlayStore_Package/screenshots \
 *   PEAKNAV_STORE_ORIENTATION=portrait|landscape [PEAKNAV_STORE_ONLY=01_,15_] [PEAKNAV_STORE_OVERWRITE=1] \
 *   ./gradlew :headless:test --tests com.peaknav.headless.PlayStoreScreenshots
 * </pre>
 *
 * <p>Writes {@code phone_portrait_1080x1920/NN_name.png} or {@code phone_landscape_1920x1080/...}:
 * 16:9 at 1080 p, within Google Play's limits for phone screenshots. Pictures already there are
 * kept unless {@code PEAKNAV_STORE_OVERWRITE} is set, so an interrupted run resumes. The views
 * are the ones the peaknav-videos promos and panoramas frame; the peaknav-videos GPX tracks are
 * read from {@code ../peaknav-videos/gpx} next to this checkout.
 */
class PlayStoreScreenshots {

    private static final String DAY = "2026-07-15T09:30:00Z";
    private static final File VIDEOS_GPX = new File(System.getProperty("user.dir"), "../../peaknav-videos/gpx");
    private static final File DEMO_PHOTOS = new File(System.getProperty("user.home"), ".peaknav/skyline_demo");

    private PeakNavRenderer renderer;
    private File dir;
    private int width, height;

    /** A named scene: how to set it up; the picture is taken once it has settled. */
    private interface Setup {
        void run() throws Exception;
    }

    private static final class Scene {
        final String name;
        final Setup setup;

        Scene(String name, Setup setup) {
            this.name = name;
            this.setup = setup;
        }
    }

    @Test
    void render() throws Exception {
        String out = System.getenv("PEAKNAV_STORE_SCREENSHOTS");
        assumeTrue(out != null && System.getenv("DISPLAY") != null, "set PEAKNAV_STORE_SCREENSHOTS and DISPLAY");
        boolean portrait = !"landscape".equalsIgnoreCase(System.getenv("PEAKNAV_STORE_ORIENTATION"));
        width = portrait ? 1080 : 1920;
        height = portrait ? 1920 : 1080;
        File base = new File(out);
        if (!base.isAbsolute()) {
            base = new File(new File(System.getProperty("user.dir")).getParentFile(), out);
        }
        dir = new File(base, portrait ? "phone_portrait_1080x1920" : "phone_landscape_1920x1080");
        dir.mkdirs();
        String only = System.getenv("PEAKNAV_STORE_ONLY");
        boolean overwrite = System.getenv("PEAKNAV_STORE_OVERWRITE") != null;

        List<Scene> scenes = scenes();
        List<Scene> todo = new ArrayList<>();
        for (int i = 0; i < scenes.size(); i++) {
            String file = fileName(i, scenes.get(i).name);
            if (only != null && !matchesAny(file, only)) {
                continue;
            }
            if (!overwrite && new File(dir, file).exists()) {
                System.out.println("store screenshot kept: " + file);
                continue;
            }
            todo.add(scenes.get(i));
        }
        if (todo.isEmpty()) {
            return;
        }
        renderer = PeakNavRenderer.start(width, height, Locale.ENGLISH);
        try {
            for (Scene scene : todo) {
                String file = fileName(scenes.indexOf(scene), scene.name);
                long started = System.currentTimeMillis();
                try {
                    reset();
                    scene.setup.run();
                    if (!awaitDownloadBarHidden(180_000)) {
                        System.out.println("store screenshot SKIPPED (download still in progress): " + file);
                        continue;
                    }
                    renderer.captureWithUi(new File(dir, file));
                    System.out.println("store screenshot: " + file + " in "
                            + (System.currentTimeMillis() - started) / 1000 + " s");
                } catch (Exception | AssertionError e) {
                    System.out.println("store screenshot FAILED: " + file + ": " + e);
                }
            }
        } finally {
            renderer.close();
        }
    }

    /** Waits until the app's "Download in progress" bar is gone; false if it is still up at the deadline. */
    private boolean awaitDownloadBarHidden(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        boolean waited = false;
        while (true) {
            final boolean[] visible = new boolean[1];
            renderer.runOnRenderThread(() -> visible[0] = com.peaknav.viewer.MapViewerSingleton.getViewerInstance()
                    .tableLocation.progressBarTable.isVisible());
            if (!visible[0]) {
                if (waited) {
                    renderer.awaitTilesLoaded(90_000);
                    renderer.refreshLabelsAndWait(30_000);
                    renderer.settle(2000);
                }
                return true;
            }
            if (System.currentTimeMillis() > deadline) {
                // Still up after the wait: a download for tiles the server does not have hangs
                // in its retries, and the bar with it. The terrain that did arrive is on screen,
                // so take the bar down and the picture anyway.
                System.out.println("store: download bar stuck; hiding it");
                renderer.runOnRenderThread(() -> com.peaknav.compatibility.PeakNavAppState.getAppState()
                        .setMapDataDownloadProgressRatio(1f));
                renderer.awaitTilesLoaded(90_000);
                renderer.refreshLabelsAndWait(30_000);
                renderer.settle(2000);
                return true;
            }
            waited = true;
            Thread.sleep(1000);
        }
    }

    private static String fileName(int index, String name) {
        return String.format(Locale.ROOT, "%02d_%s.png", index + 1, name);
    }

    private static boolean matchesAny(String file, String patterns) {
        for (String p : patterns.split(",")) {
            if (!p.trim().isEmpty() && file.contains(p.trim())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ the scenes

    private List<Scene> scenes() {
        List<Scene> s = new ArrayList<>();
        // Peaks named around the world, from the viewpoints people go to for them: the camera stands
        // there, a few metres up, and turns to the summit, so it sits in the frame whatever the shape.
        s.add(new Scene("matterhorn_riffelsee", () -> look(45.98360, 7.75920, 40, 45.97630, 7.65860, 4478, true)));
        s.add(new Scene("everest_kala_patthar", () -> look(28.00220, 86.82830, 20, 27.98810, 86.92500, 8849, false)));
        s.add(new Scene("tre_cime_rifugio_locatelli", () -> look(46.65000, 12.29500, 600, 46.61870, 12.30460, 2999, true)));
        s.add(new Scene("matterhorn_gornergrat", () -> look(45.98380, 7.78510, 30, 45.97630, 7.65860, 4478, true)));
        s.add(new Scene("mont_blanc_lac_blanc", () -> look(45.97440, 6.88780, 30, 45.83260, 6.86520, 4808, true)));
        s.add(new Scene("eiger_kleine_scheidegg", () -> look(46.58560, 7.96140, 20, 46.57760, 8.00530, 3967, true)));
        s.add(new Scene("dolomites_passo_giau", () -> look(46.48490, 12.05530, 30, 46.53780, 12.06000, 3225, true)));
        s.add(new Scene("grand_teton_jenny_lake", () -> look(43.75200, -110.71900, 30, 43.74100, -110.80240, 4199, true)));
        s.add(new Scene("half_dome_glacier_point", () -> look(37.72750, -119.57370, 20, 37.74590, -119.53320, 2694, true)));
        s.add(new Scene("denali_wonder_lake", () -> look(63.46110, -150.86620, 20, 63.06920, -151.00700, 6190, false)));
        s.add(new Scene("fitz_roy_patagonia", () -> look(-49.32370, -72.87470, 40, -49.27140, -73.04310, 3405, false)));
        s.add(new Scene("torres_del_paine", () -> look(-50.97250, -72.75160, 40, -50.94060, -72.98130, 2800, false)));
        s.add(new Scene("mount_rainier_paradise", () -> look(46.78600, -121.73580, 20, 46.85290, -121.76040, 4392, true)));
        s.add(new Scene("mount_fuji_kawaguchiko", () -> look(35.51700, 138.75200, 20, 35.36060, 138.72740, 3776, false)));

        // The sky: stars, constellations and planets where they really are.
        s.add(new Scene("night_sky_mont_blanc", () -> night(45.92370, 6.86940, 1400, 183, 19, "2026-07-15T21:40:00Z")));
        s.add(new Scene("night_sky_patagonia", () -> night(-49.32370, -72.87470, 900, 300, 18, "2026-07-15T03:30:00Z")));
        s.add(new Scene("night_sky_k2", () -> night(35.74400, 76.51400, 5300, 0, 14, "2026-07-15T18:30:00Z")));

        // GPX tours, with the info pane: walking time, profile, elevation and distance so far.
        s.add(new Scene("gpx_tour_ortles", () -> tour("ortles-from-solda.gpx", 0.45f, PANE_OPEN)));
        s.add(new Scene("gpx_tour_matterhorn_profile", () -> tour("matterhorn-hornli-ridge.gpx", 0.7f, PANE_MAXIMIZED)));
        s.add(new Scene("gpx_tour_mont_blanc", () -> tour("mont-blanc-gouter-route.gpx", 0.35f, PANE_OPEN)));
        s.add(new Scene("gpx_tour_gosausee", () -> tour("gosausee-adamekhuette.gpx", 0.55f, PANE_OPEN)));
        // Following the path: the tour's camera at different moments, the pane folded or open.
        s.add(new Scene("gpx_follow_matterhorn_start", () -> tour("matterhorn-hornli-ridge.gpx", 0.15f, PANE_FOLDED)));
        s.add(new Scene("gpx_follow_ortles_summit", () -> tour("ortles-from-solda.gpx", 0.85f, PANE_FOLDED)));
        s.add(new Scene("gpx_follow_bernina_express", () -> tour("bernina-express-tirano-st-moritz.gpx", 0.5f, PANE_OPEN)));
        s.add(new Scene("gpx_follow_everest", () -> tour("everest-lukla-to-summit.gpx", 0.6f, PANE_OPEN)));
        s.add(new Scene("gpx_follow_half_dome", () -> tour("half-dome-cables.gpx", 0.55f, PANE_FOLDED)));
        // The info pane's profile: walking time, heights up the side, time along the bottom.
        s.add(new Scene("gpx_profile_monte_rosa", () -> tour("monte-rosa-alagna-capanna-margherita.gpx", 0.5f, PANE_MAXIMIZED)));
        s.add(new Scene("gpx_profile_kilimanjaro", () -> tour("kilimanjaro-machame.gpx", 0.4f, PANE_OPEN)));
        s.add(new Scene("gpx_profile_fuji", () -> tour("fuji-yoshida-trail.gpx", 0.65f, PANE_MAXIMIZED)));

        // Route to here, and the menu a tap on the map opens.
        s.add(new Scene("route_to_here_zermatt", this::routeToHere));
        s.add(new Scene("tap_menu_riffelsee", () -> {
            look(45.98360, 7.75920, 40, 45.97630, 7.65860, 4478, true);
            renderer.tap(width / 2, (int) (height * 0.58));
            renderer.settle(1500);
        }));

        // Ski runs by difficulty, lifts with their cabins and chairs, and their names.
        s.add(new Scene("ski_zermatt", () -> ski(46.01200, 7.77500, 300, 3500, 1100)));
        s.add(new Scene("ski_val_thorens", () -> ski(45.29800, 6.58000, 20, 4000, 1200)));
        s.add(new Scene("ski_sella_dolomites", () -> ski(46.51500, 11.79000, 330, 4500, 1300)));
        s.add(new Scene("ski_pinzolo", () -> ski(46.17000, 10.79000, 290, 3500, 1100)));
        s.add(new Scene("ski_cervinia", () -> ski(45.93000, 7.66000, 250, 4000, 1200)));
        s.add(new Scene("ski_val_gardena", () -> ski(46.56000, 11.73000, 20, 4000, 1200)));
        s.add(new Scene("ski_courchevel", () -> ski(45.40500, 6.63000, 20, 3500, 1100)));
        s.add(new Scene("ski_kitzbuehel", () -> ski(47.42500, 12.38000, 20, 3500, 1000)));
        s.add(new Scene("ski_verbier", () -> ski(46.08500, 7.25000, 290, 3500, 1100)));
        s.add(new Scene("ski_lifts_close_zermatt", () -> ski(46.01800, 7.78500, 290, 1500, 450)));
        s.add(new Scene("ski_menu", () -> {
            ski(46.01200, 7.77500, 300, 3500, 1100);
            renderer.openRoadsMenu(3).settle(800);
        }));

        // A photo's skyline matched to the terrain, peaks named on the photo.
        s.add(new Scene("photo_match_zermatt", () -> photo("zermatt_matterhorn_241.jpg")));
        s.add(new Scene("photo_match_riffelsee", () -> photo("riffelsee_matterhorn_265.jpg")));
        // Picture overlays (screenshots 46-51) are not rendered here: they are the frames the
        // peaknav-videos app-match video was cut from (videos/app-match/frames/*/final.png),
        // copied into the package with their photo credits in PHOTO_CREDITS.md.

        // The options.
        s.add(new Scene("options_menu", () -> {
            look(45.98380, 7.78510, 30, 45.97630, 7.65860, 4478, false);
            renderer.setOptionsPane(true).settle(800);
        }));
        return s;
    }

    /** Back to a daytime view with the usual layers, no tour, photo or menu left over. */
    private void reset() {
        renderer.setOptionsPane(false);
        // A tapped point's menu (go to, orbit, route to here...), as its X closes it.
        renderer.runOnRenderThread(() -> {
            com.peaknav.viewer.screens.MapViewerScreen screen = com.peaknav.viewer.MapViewerSingleton.getViewerInstance();
            screen.stopOrbit();
            screen.removeImpact();
        });
        renderer.stopGpxTour().clearGpx();
        renderer.setGpxInfoMaximized(false).setGpxInfoOpen(true);
        if (renderer.hasPhoto()) {
            renderer.clearPhoto();
        }
        renderer.setPhotoOverlay(1f, 1f);
        renderer.setUnitSystem(UnitSystem.METRIC);
        renderer.setSky(false).setSkyMode(1).setSkyTimeMillis(Instant.parse(DAY).toEpochMilli())
                .setConstellations(false).setStarNames(false).setSkyLabels(false).setSkyTimeLabel(false)
                .setSkyGrid(false).setSkyEcliptic(false);
        renderer.setSunShading(true).setHorizonCompass(true).setCornerCompass(true).setShowCoordinates(true);
        renderer.setSatelliteVisible(true);
        renderer.setFieldOfView(45f);
        renderer.clearLabels();
        renderer.setAllLabels(true);
    }

    private void labels(boolean paths, boolean ski) {
        renderer.setLabel(PeakNavRenderer.Label.PEAKS, true)
                .setLabel(PeakNavRenderer.Label.ALPINE_HUTS, true)
                .setLabel(PeakNavRenderer.Label.LAKES, true)
                .setLabel(PeakNavRenderer.Label.ROADS, true)
                .setLabel(PeakNavRenderer.Label.ROAD_NAMES, paths)
                .setLabel(PeakNavRenderer.Label.PISTES, ski)
                .setLabel(PeakNavRenderer.Label.PISTE_NAMES, ski)
                .setLabel(PeakNavRenderer.Label.LIFTS, ski)
                .setLabel(PeakNavRenderer.Label.LIFT_NAMES, ski);
    }

    /** Stand at a place - at a height above the sea, or else above the ground - and look. */
    private void go(double lat, double lon, double altitudeAsl, double aboveGround, float bearing, float pitch) {
        renderer.downloadMissingData(lat, lon, 300_000);
        renderer.moveTo(lat, lon);
        renderer.awaitTilesLoaded(90_000);
        if (!Double.isNaN(altitudeAsl)) {
            renderer.setAltitudeMeters(altitudeAsl);
        } else {
            renderer.setElevationMeters(aboveGround);
        }
        renderer.aim(bearing, pitch);
        renderer.awaitTilesLoaded(90_000);
        // Again, now the ground under the camera has loaded: a height above the ground is taken
        // from the terrain known when it is set, and set too early it put the camera far too low.
        renderer.settle(1500);
        if (!Double.isNaN(altitudeAsl)) {
            renderer.setAltitudeMeters(altitudeAsl);
        } else {
            renderer.setElevationMeters(aboveGround);
        }
        renderer.aim(bearing, pitch);
        renderer.awaitTilesLoaded(90_000);
        renderer.refreshLabelsAndWait(30_000);
        renderer.awaitLabelsRendered(20_000);
        renderer.settle(1500);
    }

    private void view(double lat, double lon, double altitudeAsl, double aboveGround, float bearing, float pitch,
                      boolean paths) {
        labels(paths, false);
        go(lat, lon, altitudeAsl, aboveGround, bearing, pitch);
        clearView(lat, lon, bearing, pitch);
    }

    /**
     * Retakes a view from a bad spot: while the ground within 3 km in front of the camera rises
     * into the middle of the frame, or hardly a label is drawn, the camera goes up 200 m and looks
     * again - up to six times.
     */
    private void clearView(double lat, double lon, float bearing, float pitch) {
        com.peaknav.skyline.ElevationSampler terrain = com.peaknav.viewer.PhotoSkylineAligner.loadedTerrain();
        for (int attempt = 0; attempt < 6; attempt++) {
            double camera = renderer.altitudeMeters();
            double b = Math.toRadians(bearing), cos = Math.cos(Math.toRadians(lat));
            double worst = -90;
            for (double d = 60; d <= 3000; d += 60) {
                double pLat = lat + d * Math.cos(b) / 111320.0;
                double pLon = lon + d * Math.sin(b) / (111320.0 * cos);
                float ground = terrain.elevationMeters(pLat, pLon);
                if (!Float.isNaN(ground)) {
                    worst = Math.max(worst, Math.toDegrees(Math.atan2(ground - camera, d)));
                }
            }
            final int[] labels = new int[1];
            renderer.runOnRenderThread(() -> labels[0] = com.peaknav.compatibility.PeakNavAppState.getAppState().getVisibleLabelCount());
            // The frame is 45 degrees tall: ground rising past a quarter of it below the centre
            // (as the slope in front of K2 did) fills the lower half and hides what is behind.
            boolean blocked = worst > pitch - 10;
            if (!blocked && labels[0] >= 2) {
                return;
            }
            System.out.println("store bad spot (ground " + Math.round(worst) + " deg vs pitch " + pitch + ", labels "
                    + labels[0] + "): camera up 200 m from " + Math.round(camera) + " m");
            renderer.setAltitudeMeters(camera + 200);
            renderer.aim(bearing, pitch);
            renderer.awaitTilesLoaded(90_000);
            renderer.refreshLabelsAndWait(30_000);
            renderer.awaitLabelsRendered(20_000);
            renderer.settle(1500);
        }
    }

    /**
     * Stand at a viewpoint and turn to a summit: the bearing and the pitch to it from where the
     * camera ends up, the summit a little above the middle of the frame so its label has room.
     */
    private void look(double lat, double lon, double aboveGround, double peakLat, double peakLon, double peakEle,
                      boolean paths) {
        labels(paths, false);
        go(lat, lon, Double.NaN, aboveGround, 0, 0);
        double p1 = Math.toRadians(lat), p2 = Math.toRadians(peakLat), dl = Math.toRadians(peakLon - lon);
        double bearing = Math.toDegrees(Math.atan2(Math.sin(dl) * Math.cos(p2),
                Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl)));
        double metres = com.peaknav.routing.WalkingRouter.metres(lat, lon, peakLat, peakLon);
        // The Earth's curve, less a seventh for refraction, lowers a far summit.
        double drop = metres * metres / (2 * 6371008.8) * 0.87;
        double pitch = Math.toDegrees(Math.atan2(peakEle - drop - renderer.altitudeMeters(), metres));
        renderer.aim((float) ((bearing + 360) % 360), (float) (pitch - 4));
        renderer.awaitTilesLoaded(90_000);
        renderer.refreshLabelsAndWait(30_000);
        renderer.awaitLabelsRendered(20_000);
        renderer.settle(1500);
        clearView(lat, lon, (float) ((bearing + 360) % 360), (float) (pitch - 4));
    }

    private void night(double lat, double lon, double altitudeAsl, float bearing, float pitch, String time) {
        labels(false, false);
        renderer.setLabel(PeakNavRenderer.Label.ALPINE_HUTS, false);
        renderer.setSky(true).setSkyMode(2).setSkyTimeMillis(Instant.parse(time).toEpochMilli())
                .setConstellations(true).setStarNames(true).setSkyLabels(true);
        go(lat, lon, altitudeAsl, Double.NaN, bearing, pitch);
        clearView(lat, lon, bearing, pitch);
    }

    private static final int PANE_FOLDED = 0, PANE_OPEN = 1, PANE_MAXIMIZED = 2;

    private void tour(String gpxFile, float fraction, int pane) throws Exception {
        String xml = new String(Files.readAllBytes(new File(VIDEOS_GPX, gpxFile).toPath()), StandardCharsets.UTF_8);
        List<GpxTrack> tracks = GpxParser.parse(xml);
        GpxTrack.Point first = tracks.get(0).getPoints().get(0);
        GpxTrack.Point last = tracks.get(0).getPoints().get(tracks.get(0).size() - 1);
        labels(true, false);
        renderer.downloadMissingData(last.lat, last.lon, 300_000);
        go(first.lat, first.lon, Double.NaN, 300, 0, 0);
        renderer.loadGpx(xml);
        renderer.settle(1500);
        renderer.startGpxTour().settle(4000);
        renderer.setGpxTourPaused(true).settle(500);
        renderer.seekGpxTour(fraction).settle(1500);
        renderer.awaitTilesLoaded(90_000);
        renderer.refreshLabelsAndWait(30_000);
        renderer.setGpxInfoMaximized(pane == PANE_MAXIMIZED);
        renderer.setGpxInfoOpen(pane != PANE_FOLDED);
        renderer.settle(2500);
    }

    private void routeToHere() {
        labels(true, false);
        go(46.02070, 7.74910, Double.NaN, 40, 120, 10);
        com.peaknav.routing.RouteToPoint.Result result = renderer.routeTo(46.00800, 7.76800);
        if (result.route == null) {
            throw new IllegalStateException("no route: " + result.problem);
        }
        renderer.openRoute(result.route, 46.00800, 7.76800);
        renderer.settle(3000);
        renderer.startGpxTour().settle(4000);
        renderer.setGpxTourPaused(true).settle(500);
        renderer.seekGpxTour(0.5f).settle(1500);
        renderer.awaitTilesLoaded(90_000);
        renderer.refreshLabelsAndWait(30_000);
        renderer.setGpxInfoOpen(true);
        renderer.settle(2500);
    }

    /**
     * Over a ski area: the camera {@code metres} from its middle, looking towards it from
     * {@code fromBearing}, {@code height} metres above the ground, turned to the middle.
     */
    private void ski(double lat, double lon, double fromBearing, double metres, double height) {
        labels(false, true);
        renderer.setLabel(PeakNavRenderer.Label.ALPINE_HUTS, false);
        double b = Math.toRadians(fromBearing), p1 = Math.toRadians(lat), d = metres / 6371008.8;
        double camLat = Math.toDegrees(Math.asin(Math.sin(p1) * Math.cos(d) + Math.cos(p1) * Math.sin(d) * Math.cos(b)));
        double camLon = lon + Math.toDegrees(Math.atan2(Math.sin(b) * Math.sin(d) * Math.cos(p1),
                Math.cos(d) - Math.sin(p1) * Math.sin(Math.toRadians(camLat))));
        renderer.downloadMissingData(lat, lon, 300_000);
        go(camLat, camLon, Double.NaN, height, (float) ((fromBearing + 180) % 360), -20);
        // An absolute altitude from the ground as loaded, under the camera and under the ski area -
        // the higher of the two - rather than a height above "the current terrain", which left the
        // camera on the ground.
        com.peaknav.skyline.ElevationSampler terrain = com.peaknav.viewer.PhotoSkylineAligner.loadedTerrain();
        float groundCamera = terrain.elevationMeters(camLat, camLon);
        float groundMetres = terrain.elevationMeters(lat, lon);
        double base = Math.max(Float.isNaN(groundCamera) ? 0 : groundCamera, Float.isNaN(groundMetres) ? 0 : groundMetres);
        if (base > 0) {
            renderer.setAltitudeMeters(base + height);
            renderer.awaitTilesLoaded(90_000);
        }
        double[] metresNow = {renderer.altitudeMeters()};
        double target = Float.isNaN(groundMetres) ? metresNow[0] - height : groundMetres;
        double pitch = Math.toDegrees(Math.atan2(target - metresNow[0], metres));
        System.out.println("store ski camera: ground " + groundCamera + " / " + groundMetres + " m, altitude "
                + Math.round(metresNow[0]) + " m, pitch " + Math.round(pitch));
        renderer.aim((float) ((fromBearing + 180) % 360), (float) (pitch + 2));
        renderer.awaitTilesLoaded(90_000);
        renderer.refreshLabelsAndWait(30_000);
        renderer.awaitLabelsRendered(20_000);
        renderer.settle(2500);
    }

    private void photo(String file) throws Exception {
        byte[] bytes = Files.readAllBytes(new File(DEMO_PHOTOS, file).toPath());
        labels(false, false);
        renderer.setLabel(PeakNavRenderer.Label.ALPINE_HUTS, false).setLabel(PeakNavRenderer.Label.ROADS, false);
        renderer.loadPhoto(bytes, 60_000);
        double[] where = renderer.photoLocation();
        if (where == null) {
            throw new IllegalStateException("the photo has no position");
        }
        go(where[0], where[1], Double.NaN, 3, 0, 0);
        renderer.loadPhoto(bytes, 60_000);
        renderer.matchPhoto(3);
        renderer.setPhotoOverlay(0.85f, 0f);
        renderer.awaitTilesLoaded(90_000);
        renderer.refreshLabelsAndWait(30_000);
        renderer.settle(2500);
    }
}
