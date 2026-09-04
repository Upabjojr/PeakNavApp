package com.peaknav.headless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.badlogic.gdx.math.Vector3;
import com.peaknav.utils.ResourceStats;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

/**
 * Exercises the headless renderer against the real app.
 *
 * <p>Booting the app costs tens of seconds, so one renderer is shared by every test here
 * ({@code PER_CLASS} plus {@code @BeforeAll}) and the tests are ordered cheapest-first.
 *
 * <p>The renderer needs an X connection to create a GL context, so the whole class is skipped
 * when {@code DISPLAY} is unset rather than failing - that keeps it harmless on a machine with
 * no desktop, while still running in a normal checkout and under Xvfb in CI.
 *
 * <p>Coordinates are Zermatt, an area whose data is expected to be present locally; the tests
 * that only check geometry do not care either way.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PeakNavRendererTest {

    private static final int WIDTH = 640;
    private static final int HEIGHT = 400;
    private static final double LAT = 46.0207;
    private static final double LON = 7.7491;

    private PeakNavRenderer renderer;

    @BeforeAll
    void boot() {
        assumeTrue(System.getenv("DISPLAY") != null,
                "no DISPLAY: the renderer cannot create a GL context here");
        renderer = PeakNavRenderer.start(WIDTH, HEIGHT);
        renderer.moveTo(LAT, LON);
        // Let the app finish placing the camera before any test poses it. While tiles load,
        // the app queues its own fly onto the terrain (LocationState.TARGETING); an aim()
        // issued before that fly has played gets overwritten by it, which made the aim test
        // pass or fail depending on timing.
        renderer.awaitTilesLoaded(120_000);
    }

    @AfterAll
    void shutDown() {
        if (renderer != null) {
            renderer.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("aim() converts a compass bearing into the app's ENU camera frame")
    void aimPointsTheCamera() {
        // Due east: +X in the app's ENU world frame, with the horizon level.
        renderer.aim(90f, 0f);
        Vector3 direction = renderer.cameraDirection();
        assertEquals(1f, direction.x, 0.001f, "east should be +X");
        assertEquals(0f, direction.y, 0.001f, "east should have no north component");
        assertEquals(0f, direction.z, 0.001f, "a level view should have no vertical component");

        // Due north, pitched up 30 degrees.
        renderer.aim(0f, 30f);
        direction = renderer.cameraDirection();
        assertEquals(0f, direction.x, 0.001f);
        assertEquals(Math.cos(Math.toRadians(30)), direction.y, 0.001f);
        assertEquals(Math.sin(Math.toRadians(30)), direction.z, 0.001f, "positive pitch looks up");

        // The horizon must stay level whatever the pitch, or every image comes out tilted.
        Vector3 up = renderer.cameraUp();
        assertEquals(0f, up.x, 0.001f, "up must not roll sideways");
        assertTrue(up.z > 0f, "up must point skyward");
    }

    @Test
    @Order(2)
    @DisplayName("label toggles reach the app's preferences")
    void labelsToggle() {
        renderer.clearLabels();
        assertTrue(renderer.labelStates().values().stream().noneMatch(Boolean::booleanValue),
                "clearLabels should leave every category off");

        renderer.setLabel(PeakNavRenderer.Label.PEAKS, true);
        assertTrue(renderer.labelStates().get(PeakNavRenderer.Label.PEAKS),
                "peaks should be on after being switched on");
        assertTrue(!renderer.labelStates().get(PeakNavRenderer.Label.CITIES),
                "switching peaks on must not switch anything else on");
    }

    @Test
    @Order(2)
    @DisplayName("elevation in metres means metres, and the bar is a curve over it")
    void elevationModes() {
        // Mode 1: the primitive. A height in metres comes back as that height.
        for (double meters : new double[]{0, 50, 600, 3200, 12000}) {
            renderer.setElevationMeters(meters);
            assertEquals(meters, renderer.elevationMeters(), Math.max(1.0, meters * 0.001),
                    "setElevationMeters(" + meters + ") should put the camera there");
        }

        // Mode 3: absolute altitude, what a moving camera uses. Independent of the ground,
        // which is the whole point: an orbit holds it so the subject does not bob.
        // Above the ground here (Zermatt sits at about 1600 m), these are taken literally.
        for (double asl : new double[]{2500, 4000, 9000}) {
            renderer.setAltitudeMeters(asl);
            assertEquals(asl, renderer.altitudeMeters(), Math.max(1.0, asl * 0.001),
                    "setAltitudeMeters(" + asl + ") should put the camera at that altitude");
        }
        // Below the ground it is raised to the surface instead of burying the camera. Asking
        // for sea level in a valley 1600 m up must not put the camera inside the mountain.
        renderer.setAltitudeMeters(0);
        double raised = renderer.altitudeMeters();
        assertTrue(raised > 1000,
                "an altitude below the terrain should be lifted to it, was " + raised);
        assertTrue(raised < 2500,
                "and lifted only to the ground, not further, was " + raised);

        // Mode 2: the UI wrapper. Same underlying camera, but the bar's curve applied - and
        // that curve is steep, which is the whole reason the two modes are separate. Reading
        // the bar as if it were linear (0.45 -> 450 m) is how scripted renders ended up
        // kilometres too high.
        renderer.setElevation(0.45f);
        double atBar45 = renderer.elevationMeters();
        assertTrue(atBar45 > 1000,
                "bar 0.45 is far higher than a linear reading suggests, was " + atBar45 + " m");

        // The bar must be monotonic, and round-trip through the metre layer. exp5Out used to
        // stand in for the inverse of exp5In here, which it is not: a height converted to a
        // bar position and back moved by up to 11% of the bar's travel.
        double previous = -1;
        for (float bar = 0f; bar <= 1.0f; bar += 0.1f) {
            renderer.setElevation(bar);
            double meters = renderer.elevationMeters();
            assertTrue(meters > previous,
                    "the bar must rise monotonically; bar " + bar + " gave " + meters + " m");
            previous = meters;

            renderer.setElevationMeters(meters);
            assertEquals(meters, renderer.elevationMeters(), Math.max(1.0, meters * 0.001),
                    "metres -> camera -> metres must round-trip at bar " + bar);
        }
    }

    @Test
    @Order(3)
    @DisplayName("capture() writes a readable PNG at the requested size")
    void captureWritesAnImage() throws Exception {
        Path output = Files.createTempDirectory("peaknav-test").resolve("shot.png");
        renderer.aim(210f, -3f);
        renderer.settle(3_000);
        renderer.capture(output.toFile());

        File file = output.toFile();
        assertTrue(file.isFile(), "capture() should create the file");
        assertTrue(file.length() > 1024, "a rendered frame should not be a near-empty file");

        BufferedImage image = ImageIO.read(file);
        assertEquals(WIDTH, image.getWidth(), "image width should match the framebuffer");
        assertEquals(HEIGHT, image.getHeight(), "image height should match the framebuffer");

        // A frame that rendered nothing at all comes out uniformly black; this is a weak but
        // cheap check that something was actually drawn.
        assertTrue(distinctColours(image) > 1, "the frame should not be a single flat colour");
    }

    @Test
    @Order(4)
    @DisplayName("capture() honours the .jpg extension")
    void captureWritesJpeg() throws Exception {
        Path output = Files.createTempDirectory("peaknav-test").resolve("shot.jpg");
        renderer.capture(output.toFile());
        assertTrue(output.toFile().isFile(), "capture() should create the jpeg");
        assertEquals(WIDTH, ImageIO.read(output.toFile()).getWidth());
    }

    @Test
    @Order(5)
    @DisplayName("no UI prompt is ever raised, even over an area with no map data")
    void noPromptsInHeadlessMode() {
        // Mid-Atlantic: nothing is downloaded here, which is precisely the case that used to
        // pop "Dati mancanti per la posizione selezionata. Aprire finestra di download?" and
        // then block forever waiting for a click.
        renderer.moveTo(30.0, -40.0);
        renderer.settle(2_000);
        renderer.capture(newTempFile("nodata.png"));
        assertEquals(0, renderer.suppressedPrompts(),
                "a plain render must not even try to raise a dialog");

        renderer.moveTo(LAT, LON);
    }

    @Test
    @Order(6)
    @DisplayName("the saved image is opaque and the right way up")
    void imageIsOpaqueAndUpright() throws Exception {
        renderer.moveTo(LAT, LON);
        renderer.setSky(true);
        renderer.aim(210f, 0f);
        renderer.settle(4_000);
        File out = newTempFile("upright.png");
        renderer.capture(out);

        BufferedImage image = ImageIO.read(out);
        // Blended label plates used to leave alpha < 255 in the framebuffer, which made those
        // areas transparent in the PNG instead of showing the terrain behind them.
        for (int y = 0; y < image.getHeight(); y += 37) {
            for (int x = 0; x < image.getWidth(); x += 37) {
                assertEquals(255, (image.getRGB(x, y) >>> 24) & 0xFF,
                        "every pixel must be fully opaque at " + x + "," + y);
            }
        }

        // With the sky on and a level view, the top of the frame must be more blue-dominant
        // than the bottom. Blueness rather than brightness, deliberately: the app renders a
        // real night sky, and dark-navy-over-snow inverts any brightness heuristic - but the
        // sky stays bluer than terrain by day and by night, while snow is neutral and
        // vegetation is green. A vertically flipped image fails this; that shipped twice.
        assertTrue(meanBlueness(image, 0, image.getHeight() / 6)
                        > meanBlueness(image, image.getHeight() * 5 / 6, image.getHeight()),
                "sky should be at the top - the image looks vertically flipped");
    }

    @Test
    @Order(7)
    @DisplayName("awaitLabelsRendered() reports labels actually drawn on screen")
    void labelSignalFires() {
        renderer.moveTo(LAT, LON);
        renderer.setLabel(PeakNavRenderer.Label.PEAKS, true);
        renderer.awaitTilesLoaded(60_000);
        renderer.aim(210f, -3f);
        assertTrue(renderer.awaitLabelsRendered(60_000),
                "peaks are on and Zermatt has data, so a label must eventually be drawn");
    }

    @Test
    @Order(8)
    @DisplayName("objectsJson() lists the loaded peaks, with drawn ones placed on the frame")
    void objectsAreListed() {
        renderer.setLabel(PeakNavRenderer.Label.PEAKS, true);
        // Looking down the valley leaves the Matterhorn above the top edge - its label
        // passes every gate and is clipped away. Look up at the skyline instead, and
        // give the label pass time to place a name on the frame.
        renderer.aim(210f, 6f);
        renderer.awaitLabelsRendered(60_000);
        com.badlogic.gdx.utils.JsonValue all = null;
        int peaks = 0, drawn = 0;
        long deadline = System.currentTimeMillis() + 30_000;
        do {
            renderer.settle(500);
            all = new com.badlogic.gdx.utils.JsonReader()
                    .parse(renderer.objectsJson("displayable", false)).get("objects");
            drawn = 0;
            for (com.badlogic.gdx.utils.JsonValue o = all.child; o != null; o = o.next) {
                if (o.getBoolean("drawn")) drawn++;
            }
        } while (drawn == 0 && System.currentTimeMillis() < deadline);
        peaks = 0;
        drawn = 0;
        int areas = 0;
        for (com.badlogic.gdx.utils.JsonValue o = all.child; o != null; o = o.next) {
            if ("peak".equals(o.getString("kind"))) {
                peaks++;
                assertTrue(o.has("name") && o.has("lat") && o.has("lon")
                        && o.has("elevation_m") && o.has("tags") && o.has("hidden"));
            }
            if ("area".equals(o.getString("kind"))) {
                areas++;
                // Where an area stands in the label pipeline: past the geometric culls
                // (candidate) and the cached terrain verdict, on top of whether it drew.
                assertTrue(o.has("type") && o.has("candidate")
                        && o.get("hidden").has("by_mountains"), "area fields: " + o);
                if (o.getBoolean("drawn")) {
                    assertTrue(o.getBoolean("candidate"), "a drawn area was a candidate: " + o);
                }
            }
            if (o.getBoolean("drawn")) {
                drawn++;
                // The anchor may be above the frame (a summit higher than the view), but
                // the name itself is pulled onto it.
                assertTrue(o.has("screen"), "a drawn POI has an anchor: " + o);
                float y = o.get("label").getFloat("y");
                assertTrue(y >= 0 && y <= HEIGHT, "a drawn name sits on the frame: " + o);
            }
        }
        assertTrue(peaks > 0, "Zermatt has peaks loaded");
        assertTrue(areas > 0, "Zermatt has named areas around it");
        assertTrue(drawn > 0, "looking at the skyline with peaks on, a name is on the frame");
        com.badlogic.gdx.utils.JsonValue onlyDrawn = new com.badlogic.gdx.utils.JsonReader()
                .parse(renderer.objectsJson("displayable", true)).get("objects");
        assertEquals(drawn, onlyDrawn.size);
        com.badlogic.gdx.utils.JsonValue everything = new com.badlogic.gdx.utils.JsonReader()
                .parse(renderer.objectsJson("all", false)).get("objects");
        assertTrue(everything.size >= all.size, "all widens, never narrows");
    }

    @Test
    @Order(10)
    @DisplayName("the ecliptic passes through the Sun, and the grid converges on the pole")
    void skyOverlaysAreWhereTheyShouldBe() {
        renderer.moveTo(LAT, LON);
        renderer.awaitTilesLoaded(60_000);
        renderer.setSkyGrid(true);
        renderer.setSkyEcliptic(true);
        renderer.settle(500);

        com.peaknav.sky.SkyModel sky = com.peaknav.utils.PeakNavUtils.getC().skyModel;
        assertTrue(sky != null && sky.isLoaded(), "the sky model should be loaded");

        // The Sun is ON the ecliptic - that is what the ecliptic is. So the Sun's direction
        // must lie on the polyline, whatever the date, place or time of day. This catches a
        // wrong obliquity, a wrong sidereal time, or a swapped axis, none of which are
        // visible by reading the code.
        float[] ecliptic = sky.getEclipticEnu();
        assertTrue(ecliptic.length > 300, "the ecliptic should be a full circle of points");
        float[] bodies = sky.getBodyEnu();
        float sx = bodies[0], sy = bodies[1], sz = bodies[2];   // the Sun is the first body
        double closest = Double.MAX_VALUE;
        for (int i = 0; i + 2 < ecliptic.length; i += 3) {
            double dot = sx * ecliptic[i] + sy * ecliptic[i + 1] + sz * ecliptic[i + 2];
            closest = Math.min(closest, Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot)))));
        }
        assertTrue(closest < 2.0,
                "the Sun should sit on the ecliptic; nearest point was " + closest + " degrees");

        // Every meridian of the grid ends at the celestial pole, so the last point of each
        // must be within a few degrees of the pole's direction - that is what makes them
        // converge there on screen.
        java.util.List<float[]> grid = sky.getGridEnu();
        assertTrue(grid.size() >= 12, "expected meridians and parallels, got " + grid.size());
    }

    @Test
    @Order(10)
    @DisplayName("orbit circles the chosen point, holding its distance and keeping it in view")
    void orbitCirclesTheCentre() {
        renderer.moveTo(LAT, LON);
        renderer.awaitTilesLoaded(60_000);
        renderer.aim(180f, -4f);
        renderer.settle(500);

        // A point out in front of the camera to circle - the world-space stand-in for a click
        // on the terrain. The orbit works in world units and never touches the target, which
        // is what keeps the frame (scaled by the target's latitude) still underneath it.
        Vector3 eye = renderer.cameraPosition();
        Vector3 direction = renderer.cameraDirection();
        final float reach = 0.05f;                    // ~5.5 km in world units
        Vector3 centre = new Vector3(
                eye.x + direction.x * reach, eye.y + direction.y * reach, eye.z);

        float radiusBefore = radius(eye, centre);
        renderer.startOrbit(centre);
        assertTrue(renderer.isOrbiting(), "the orbit should be running");

        renderer.settle(2_500);
        Vector3 moved = renderer.cameraPosition();

        assertTrue(radius(moved, centre) > 0,
                "the camera should still be off the centre");
        // It went round, not toward or away.
        assertEquals(radiusBefore, radius(moved, centre), radiusBefore * 0.02f,
                "the orbit holds its radius");
        assertTrue(radius(moved, eye) > radiusBefore * 0.02f,
                "the camera should have travelled along the circle, not sat still");
        assertEquals(eye.z, moved.z, 1e-4f, "the height is held, so the subject does not bob");

        // Still pointed at what it is circling.
        Vector3 toCentre = new Vector3(centre).sub(moved).nor();
        Vector3 facing = renderer.cameraDirection();
        float alignment = toCentre.dot(facing);
        assertTrue(alignment > 0.999f,
                "the camera should still look at the centre, dot was " + alignment);

        renderer.stopOrbit();
        assertTrue(!renderer.isOrbiting(), "stopOrbit should end it");
        Vector3 stopped = renderer.cameraPosition();
        renderer.settle(1_000);
        assertEquals(stopped.x, renderer.cameraPosition().x, 1e-5f,
                "once stopped, the camera stays put");
    }

    /** Horizontal distance between two world points. */
    private static float radius(Vector3 a, Vector3 b) {
        float dx = a.x - b.x, dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    @Test
    @Order(9)
    @DisplayName("moving the camera repeatedly does not leak tile meshes")
    void movingDoesNotLeakMeshes() {
        // A tile builds a fresh Mesh every time it is redrawn, and it is redrawn whenever a
        // neighbour welds to it - so a moving camera creates them by the hundred. The mesh
        // used to be assigned over the previous one, abandoning a vertex and an index buffer
        // on the GPU that only dispose() can free. Nothing showed on the Java heap, and a
        // scripted render grew about 40 MB a frame until the kernel killed it at ~215 frames.
        //
        // Live counts, not totals: creating meshes is normal, keeping them is not.
        double[] ring = {0, 60, 120, 180, 240, 300};
        long liveTilesBefore = ResourceStats.tilesCreated.get() - ResourceStats.tilesDisposed.get();
        for (double azimuth : ring) {
            double radians = Math.toRadians(azimuth);
            renderer.moveTo(LAT + 0.06 * Math.cos(radians), LON + 0.09 * Math.sin(radians));
            renderer.awaitTilesLoaded(60_000);
        }
        long liveTiles = ResourceStats.tilesCreated.get() - ResourceStats.tilesDisposed.get();
        long liveMeshes = ResourceStats.meshesCreated.get() - ResourceStats.meshesDisposed.get();
        long created = ResourceStats.meshesCreated.get();

        assertTrue(created > ring.length,
                "the ride should have rebuilt plenty of meshes, only made " + created);
        // One mesh per live tile is the healthy state; the allowance is for tiles being built
        // right now. With the leak this ran to thousands against a few hundred tiles.
        assertTrue(liveMeshes <= liveTiles + 100,
                "live meshes (" + liveMeshes + ") should track live tiles (" + liveTiles
                        + "), started at " + liveTilesBefore + " tiles; a mesh count that "
                        + "climbs with camera movement is the leak returning");
    }

    @Test
    @Order(8)
    @DisplayName("clicking the terrain puts the pin at the clicked screen point, even after a resize")
    void pinLandsWhereClicked() throws Exception {
        renderer.moveTo(LAT, LON);
        renderer.awaitTilesLoaded(60_000);
        // A resize is what exposes the bug: the scene2d stage runs in ExtendViewport world
        // units fixed at start-up, so after resizing, stage units and window pixels differ
        // (here by 2x) - and the pin used to be positioned in the wrong one of the two.
        renderer.resizeWindow(WIDTH * 2, HEIGHT * 2);
        renderer.settle(4_000); // depth pixmaps re-render at the new size

        final int clickX = WIDTH;              // window centre horizontally (after doubling)
        final int clickY = (int) (HEIGHT * 1.5); // lower half: terrain, not sky

        final com.badlogic.gdx.math.Vector2 pinOnScreen = new com.badlogic.gdx.math.Vector2();
        final boolean[] placed = new boolean[1];
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        com.badlogic.gdx.Gdx.app.postRunnable(() -> {
            try {
                com.peaknav.viewer.screens.MapViewerScreen screen =
                        com.peaknav.viewer.MapViewerSingleton.getAppInstance().mapViewerScreen;
                screen.impact = screen.detectClicked3DPosition(clickX, clickY);
                placed[0] = screen.updateImpact();
                if (placed[0]) {
                    // The pin's bottom-centre, mapped through the stage's own viewport back
                    // to screen (touch) coordinates - the same space the click arrived in.
                    pinOnScreen.set(screen.buttonPinLoc.getWidth() * 0.5f, 0f);
                    screen.buttonPinLoc.localToScreenCoordinates(pinOnScreen);
                }
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS), "render thread hung");
        assertTrue(placed[0], "the click should hit terrain and place the pin");
        assertEquals(clickX, pinOnScreen.x, 3f, "pin X must match the clicked X");
        assertEquals(clickY, pinOnScreen.y, 3f, "pin Y must match the clicked Y");

        renderer.resizeWindow(WIDTH, HEIGHT);
        renderer.settle(2_000);
    }

    private File newTempFile(String name) {
        try {
            return Files.createTempDirectory("peaknav-test").resolve(name).toFile();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @Order(10)
    @DisplayName("the sky-labels switch clears every caption, and overrides the finer settings")
    void skyLabelsSwitchGovernsEveryCaption() throws Exception {
        renderer.moveTo(LAT, LON);
        renderer.awaitTilesLoaded(60_000);
        // Night, looking up: constellation names, star names and planet names are all in play,
        // which is the case the switch has to cover.
        renderer.setSky(true);
        renderer.setSkyMode(2);
        renderer.setConstellations(true);
        renderer.setStarNames(true);
        renderer.aim(180f, 35f);

        renderer.setSkyLabels(true);
        // Before every capture, not just the first: this test compares frames pixel by pixel,
        // and satellite imagery keeps upgrading tiles for a while after the first await - a
        // sharper texture arriving between two captures once put 120,000 differing pixels of
        // terrain into what was meant to be a comparison of sky captions.
        renderer.awaitTilesLoaded(60_000);
        renderer.settle(1_500);
        File withLabels = newTempFile("sky-labels-on.png");
        renderer.capture(withLabels);

        renderer.setSkyLabels(false);
        renderer.awaitTilesLoaded(60_000);
        renderer.settle(1_500);
        File without = newTempFile("sky-labels-off.png");
        renderer.capture(without);

        int changed = differingPixels(ImageIO.read(withLabels), ImageIO.read(without));
        assertTrue(changed > 200,
                "switching the sky labels off should visibly clear captions; "
                        + changed + " pixels changed");

        // The point of a master switch: with it off, the finer settings stop mattering. If
        // this fails, some caption is escaping the gate - which is exactly the bug that let
        // star names through when only the constellations had been turned off.
        renderer.setStarNames(true);
        renderer.setConstellations(true);
        renderer.awaitTilesLoaded(60_000);
        renderer.settle(1_500);
        File offAgain = newTempFile("sky-labels-off-2.png");
        renderer.capture(offAgain);
        int drift = differingPixels(ImageIO.read(without), ImageIO.read(offAgain));
        assertEquals(0, drift,
                "with the labels off, turning star names and constellations on must change "
                        + "nothing; " + drift + " pixels differed");

        // And it comes back: the switch gates the settings rather than clearing them.
        renderer.setSkyLabels(true);
        renderer.awaitTilesLoaded(60_000);
        renderer.settle(1_500);
        File restored = newTempFile("sky-labels-restored.png");
        renderer.capture(restored);
        assertTrue(differingPixels(ImageIO.read(restored), ImageIO.read(without)) > 200,
                "switching the labels back on should restore the captions");
    }

    @Test
    @Order(10)
    @DisplayName("the frozen-time pill can be switched off, and sits at the bottom when on")
    void skyTimeLabelIsOptionalAndAtTheBottom() throws Exception {
        renderer.moveTo(LAT, LON);
        renderer.awaitTilesLoaded(60_000);
        renderer.setSky(true);
        renderer.setSkyMode(1);
        renderer.aim(210f, -4f);
        // Freezing the time is what makes the pill appear at all.
        renderer.setSkyTimeMillis(java.time.Instant.parse("2026-07-15T09:30:00Z").toEpochMilli());

        renderer.setSkyTimeLabel(true);
        renderer.settle(1_500);
        File shown = newTempFile("clock-on.png");
        renderer.capture(shown);

        renderer.setSkyTimeLabel(false);
        renderer.settle(1_500);
        File hidden = newTempFile("clock-off.png");
        renderer.capture(hidden);

        BufferedImage on = ImageIO.read(shown), off = ImageIO.read(hidden);
        assertTrue(differingPixels(on, off) > 200,
                "switching the frozen-time pill off should remove it from the frame");

        // Where the difference is, is the point of this test. The pill used to be drawn at the
        // top centre, over the view; it belongs at the bottom with the other read-outs. Every
        // changed pixel must be in the bottom quarter - if any land up top, it has moved back.
        int top = 0, bottom = 0;
        int boundary = on.getHeight() * 3 / 4;
        for (int y = 0; y < on.getHeight(); y++) {
            for (int x = 0; x < on.getWidth(); x++) {
                if (on.getRGB(x, y) != off.getRGB(x, y)) {
                    if (y < boundary) {
                        top++;
                    } else {
                        bottom++;
                    }
                }
            }
        }
        assertTrue(bottom > 200, "the pill should be in the bottom quarter; found " + bottom);
        assertEquals(0, top,
                "nothing should change in the upper three quarters, but " + top
                        + " pixels did - the clock is being drawn over the view again");
    }

    @Test
    @Order(11)
    @DisplayName("with label auto-update held, camera movement stops reshuffling labels")
    void labelHoldStopsTheReshuffles() {
        renderer.moveTo(LAT, LON);
        renderer.setLabel(PeakNavRenderer.Label.PEAKS, true);
        renderer.awaitTilesLoaded(60_000);
        renderer.aim(0f, -4f);
        renderer.settle(1_000);

        // Held: a 40-degree sweep - far past the 5-degree trigger threshold - must not
        // run a single visibility pass. This is the property that stops video flicker:
        // the camera moves every frame, the label set does not.
        renderer.setLabelAutoUpdate(false);
        // Every test before this one queued visibility work, and the queue drains at
        // only a few passes per second (each pass sleeps internally). Holding stops new
        // passes being SCHEDULED, not ones already queued - so wait for the backlog to
        // finish, or the counter counts old work and convicts an innocent hold.
        long before = renderer.labelVisibilityRuns();
        long quietSince = System.currentTimeMillis();
        long deadline = quietSince + 60_000;
        while (System.currentTimeMillis() < deadline
                && System.currentTimeMillis() - quietSince < 2_000) {
            renderer.settle(200);
            long now = renderer.labelVisibilityRuns();
            if (now != before) {
                before = now;
                quietSince = System.currentTimeMillis();
            }
        }
        for (int step = 0; step <= 40; step += 5) {
            renderer.aim(step, -4f);
            renderer.settle(120);
        }
        assertEquals(before, renderer.labelVisibilityRuns(),
                "held: rotating the camera must not trigger label recomputation");

        // The explicit refresh is the only way through, and it must actually run.
        renderer.refreshLabels();
        renderer.settle(1_500);
        assertTrue(renderer.labelVisibilityRuns() > before,
                "refreshLabels() must run a pass even while held");

        // Released: the same sweep triggers again, so the interactive app is unchanged.
        renderer.setLabelAutoUpdate(true);
        long released = renderer.labelVisibilityRuns();
        for (int step = 40; step >= 0; step -= 5) {
            renderer.aim(step, -4f);
            renderer.settle(120);
        }
        renderer.settle(1_000);
        assertTrue(renderer.labelVisibilityRuns() > released,
                "released: rotation must trigger recomputation again");
    }

    /** How many pixels differ between two frames of the same size. */
    private static int differingPixels(BufferedImage a, BufferedImage b) {
        assertEquals(a.getWidth(), b.getWidth());
        assertEquals(a.getHeight(), b.getHeight());
        int count = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Mean of (blue − avg(red, green)) over a horizontal band: how sky-like it is. */
    private static double meanBlueness(BufferedImage image, int fromY, int toY) {
        long total = 0;
        int count = 0;
        for (int y = fromY; y < toY; y += 3) {
            for (int x = 0; x < image.getWidth(); x += 7) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                total += b - (r + g) / 2;
                count++;
            }
        }
        return count == 0 ? 0 : (double) total / count;
    }

    private static int distinctColours(BufferedImage image) {
        int first = image.getRGB(0, 0);
        int count = 1;
        for (int y = 0; y < image.getHeight(); y += 8) {
            for (int x = 0; x < image.getWidth(); x += 8) {
                if (image.getRGB(x, y) != first) {
                    return ++count;
                }
            }
        }
        return count;
    }

    @Test
    @Order(12)
    @DisplayName("the wait for a settled view includes the area names")
    void awaitTilesLoadedCoversTheAreaNames() {
        // Area names are read a few tiles per rendered frame, so the set is still growing over
        // the opening frames - which is why the first seconds of a video were missing range,
        // island and town names that turned up a moment later.
        renderer.moveTo(45.9237, 6.8694); // Chamonix: a neighbourhood the boot never loaded
        assertTrue(renderer.awaitTilesLoaded(120_000), "the view should reach a settled state");
        assertTrue(renderer.areaNamesLoaded(),
                "the wait returned while area names were still loading - a frame taken now is"
                        + " missing some of them");
    }

    @Test
    @Order(14)
    @DisplayName("a GPX document is drawn without moving the camera, and the lens can be widened")
    void gpxLoadsAndTheLensWidens() {
        renderer.moveTo(LAT, LON);
        renderer.awaitTilesLoaded(60_000);
        renderer.aim(210f, 6f);
        renderer.settle(300);
        String gpx = "<gpx xmlns=\"http://www.topografix.com/GPX/1/1\" version=\"1.1\"><trk>"
                + "<name>t</name><trkseg>"
                + "<trkpt lat=\"46.0207\" lon=\"7.7491\"><ele>1608</ele></trkpt>"
                + "<trkpt lat=\"46.0000\" lon=\"7.7300\"><ele>2000</ele></trkpt>"
                + "<trkpt lat=\"45.9833\" lon=\"7.7853\"><ele>3089</ele></trkpt>"
                + "</trkseg></trk></gpx>";
        assertEquals(1, renderer.loadGpx(gpx), "one track in the document");
        assertEquals(0, renderer.loadGpx("<gpx version=\"1.1\"></gpx>"), "nothing to draw");
        // The app's file picker would fly off to frame the track; a scripted load must not,
        // and the view must still render with the track and the wider lens in it.
        renderer.setFieldOfView(62f);
        renderer.settle(300);
        File withGpx = newTempFile("gpx.png");
        renderer.capture(withGpx);
        assertTrue(withGpx.length() > 10_000, "a frame was rendered with the track loaded");
        assertThrows(IllegalArgumentException.class, () -> renderer.setFieldOfView(0f));
        renderer.setFieldOfView(30f);
    }

    @Test
    @Order(13)
    @DisplayName("the wait for a settled view includes the roads and paths on the map")
    void awaitTilesLoadedCoversTheRoadLayer() {
        // A tile counts as drawn once its elevation mesh is ready; its roads and paths are
        // rasterised afterwards, on their own executor. So "every tile is drawn" was reported
        // while the map was still bare, and the opening frames of a video came out with no
        // paths on them - the map filled in a second after the shutter.
        renderer.setLabel(PeakNavRenderer.Label.ROADS, true);
        // Somewhere the current tiles do not already cover, so the road layer genuinely has
        // to be drawn: Saas-Fee, one valley east of Zermatt.
        renderer.moveTo(46.1088, 7.9290);
        assertTrue(renderer.awaitTilesLoaded(120_000), "the view should reach a settled state");
        assertEquals(0, renderer.pendingRoadWork(),
                "the wait returned with roads still to draw - a frame taken now has no paths");
    }

    @Test
    @Order(15)
    @DisplayName("the photo skyline aligner reads the loaded tiles: a horizon with the Matterhorn in it")
    void skylineHorizonFromLoadedTiles() {
        // TestSkylineMatcher and the benchmark read the dataset files directly; this is the
        // one check that the in-app sampler (the tiles the viewer holds, through the app's
        // own elevation lookup) agrees with them: units, coverage out to the far tiles, and
        // the Matterhorn where it stands - 235 degrees from Zermatt, 8 km away, about 16
        // degrees up with the elevation model's rounded-off summit.
        float ground = com.peaknav.viewer.PhotoSkylineAligner.loadedTerrain().elevationMeters(LAT, LON);
        assumeTrue(!Float.isNaN(ground), "no elevation data for Zermatt on this machine");
        assertTrue(ground > 1500 && ground < 1750, "Zermatt lies at about 1600 m, read " + ground);

        com.peaknav.skyline.TerrainHorizon horizon = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            horizon = com.peaknav.skyline.TerrainHorizon.compute(
                    com.peaknav.viewer.PhotoSkylineAligner.loadedTerrain(), LAT, LON, 20, 720);
            if (horizon.coverage >= 0.9) {
                break;
            }
            sleepQuietly(2000);
        }
        assertTrue(horizon.coverage >= 0.9,
                "the loaded tiles should cover the ray march; coverage " + horizon.coverage);
        float matterhorn = horizon.angleAt(235);
        assertTrue(matterhorn > 12 && matterhorn < 20,
                "the Matterhorn at 235 deg should stand about 16 degrees up, read " + matterhorn);
        assertTrue(horizon.angleAt(90) > 10, "the valley side at 90 deg rises steeply, read " + horizon.angleAt(90));
        assertTrue(horizon.reliefDeg(0, 360) > 3, "Zermatt's horizon is anything but flat");
    }

    @Test
    @Order(16)
    @DisplayName("the match button turns the camera to a photo's direction, end to end")
    void matchButtonTurnsTheCamera() throws Exception {
        // Earlier tests leave the viewpoint elsewhere and high up (the GPX framing, the
        // altitude checks); the button matches at the viewer's own position and height,
        // so put the camera back on the ground at Zermatt first.
        renderer.moveTo(LAT, LON);
        renderer.awaitTilesLoaded(120_000);
        renderer.setElevationMeters(0);
        com.peaknav.skyline.ElevationSampler terrain = com.peaknav.viewer.PhotoSkylineAligner.loadedTerrain();
        assumeTrue(!Float.isNaN(terrain.elevationMeters(LAT, LON)), "no elevation data for Zermatt on this machine");
        com.peaknav.skyline.TerrainHorizon horizon = com.peaknav.skyline.TerrainHorizon.compute(terrain, LAT, LON, 20, 720);
        assumeTrue(horizon.coverage >= 0.9, "terrain not loaded far enough");

        // A "photograph" painted from the app's own horizon: sky above the ridge, textured
        // ground below, looking WNW at the ridges above the Zmutt valley - which stand 20-30
        // degrees up from the village, hence the steep pitch and wide lens that keep the
        // skyline inside the frame. No EXIF, so the aligner has no location for it and the
        // button must assume "here".
        final float bearing = 300f, pitch = 22f, vfov = 50f;
        final int w = 640, h = 480;
        float[] ridge;
        {
            com.peaknav.skyline.SkylineMatcher m = new com.peaknav.skyline.SkylineMatcher(
                    horizon, new float[w], new float[w], w, h);
            java.lang.reflect.Method pm = com.peaknav.skyline.SkylineMatcher.class.getDeclaredMethod(
                    "projectHorizon", double.class, double.class, double.class, double.class);
            pm.setAccessible(true);
            ridge = (float[]) pm.invoke(m, (double) bearing, (double) pitch, (double) vfov, 0.0);
        }
        java.util.Random rng = new java.util.Random(5);
        BufferedImage photo = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean sky = y < ridge[x];
                float n = (float) rng.nextGaussian() * 0.03f;
                float r, g, b;
                if (sky) {
                    r = 0.55f + 0.2f * y / h + n; g = 0.7f + 0.15f * y / h + n; b = 0.95f + n;
                } else {
                    float t = rng.nextFloat() * 0.25f;
                    r = 0.35f + t + n; g = 0.33f + t + n; b = 0.3f + t + n;
                }
                photo.setRGB(x, y, (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b));
            }
        }
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        ImageIO.write(photo, "png", bytes);

        renderer.aim(90f, 0f);   // start somewhere else entirely
        com.peaknav.utils.PeakNavUtils.setBytesAsBackgroundImage(bytes.toByteArray());
        com.peaknav.viewer.PhotoSkylineAligner.matchNow();

        double got = Double.NaN;
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            Vector3 d = renderer.cameraDirection();
            got = (Math.toDegrees(Math.atan2(d.x, d.y)) + 360) % 360;
            if (Math.abs(((got - bearing + 540) % 360) - 180) < 2) {
                break;
            }
            sleepQuietly(1000);
        }
        assertEquals(0, Math.abs(((got - bearing + 540) % 360) - 180), 2.0,
                "the camera should have turned to the photo's bearing, points at " + got);
        Vector3 d = renderer.cameraDirection();
        assertEquals(pitch, Math.toDegrees(Math.asin(d.z)), 1.5, "and taken its pitch");

        // The debug "save sample" button: the photo, the pose the camera now has and the
        // overlay go into the samples directory, and the manifest gains an entry the
        // benchmark can read - with the bearing the camera was just turned to.
        Path samples = Files.createTempDirectory("peaknav-samples");
        System.setProperty("peaknav.samplesDir", samples.toString());
        try {
            com.peaknav.viewer.PhotoSkylineAligner.saveSample();
            File manifest = samples.resolve("manifest.json").toFile();
            deadline = System.currentTimeMillis() + 30_000;
            while (!manifest.exists() && System.currentTimeMillis() < deadline) {
                sleepQuietly(500);
            }
            assertTrue(manifest.exists(), "manifest.json should have been written under " + samples);
            String json = new String(Files.readAllBytes(manifest.toPath()), "UTF-8");
            java.util.regex.Matcher hm = java.util.regex.Pattern.compile("\"heading\":\\s*([0-9.]+)").matcher(json);
            assertTrue(hm.find(), "manifest entry should carry a heading: " + json);
            double saved = Double.parseDouble(hm.group(1));
            assertEquals(0, Math.abs(((saved - bearing + 540) % 360) - 180), 2.0, "saved heading " + saved);
            File[] dirs = samples.toFile().listFiles(File::isDirectory);
            assertTrue(dirs != null && dirs.length == 1, "one sample directory");
            assertTrue(new File(dirs[0], "photo.png").length() > 1000, "the photo file as loaded");
            File view = new File(dirs[0], "view.png");
            deadline = System.currentTimeMillis() + 15_000;
            while (!(view.exists() && view.length() > 1000) && System.currentTimeMillis() < deadline) {
                sleepQuietly(500);
            }
            assertTrue(view.length() > 1000, "the rendered view should be saved beside the photo");
            String sample = new String(Files.readAllBytes(new File(dirs[0], "sample.json").toPath()), "UTF-8");
            assertTrue(sample.contains("\"ridgeRows\"") && sample.contains("\"angleDeg\""),
                    "sample.json should hold the overlay and the horizon");
        } finally {
            System.clearProperty("peaknav.samplesDir");
        }
    }

    @Test
    @Order(17)
    @DisplayName("a pinned point of the photo stays under the finger through a drag and a zoom")
    void photoPinHoldsThePoint() throws Exception {
        // Any picture will do: the pin only needs a photo to be shown.
        BufferedImage tiny = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        ImageIO.write(tiny, "png", bytes);
        com.peaknav.utils.PeakNavUtils.setBytesAsBackgroundImage(bytes.toByteArray());
        renderer.aim(300f, 10f);

        final float px = 320f, py = 200f;              // the pin, touch coordinates (y down)
        final float sx = px + 120f, sy = py;           // finger start, right of the pin
        final float ex = px, ey = py + 120f;           // finger end, a quarter turn around it
        final Vector3 pinned = new Vector3(), atStart = new Vector3(), check = new Vector3();
        final float[] fov = new float[2];
        renderer.runOnRenderThread(() -> {
            com.peaknav.viewer.screens.MapViewerScreen screen = com.peaknav.viewer.MapViewerSingleton.getViewerInstance();
            pinned.set(screen.cam.getPickRayStable(px, py).direction);
            com.peaknav.gesture.PhotoPin.set(px, py, pinned);
            atStart.set(screen.cam.getPickRayStable(sx, sy).direction);
            screen.controller.touchDown((int) sx, (int) sy, 0, 0);
            screen.controller.touchDragged((int) ex, (int) ey, 0);
            screen.controller.touchUp((int) ex, (int) ey, 0, 0);
            check.set(screen.cam.getPickRayStable(px, py).direction);
        });
        assertTrue(check.dot(pinned) > 0.9999f, "the pinned direction must stay on its pixel after a drag: " + check.dot(pinned));
        renderer.runOnRenderThread(() -> {
            com.peaknav.viewer.screens.MapViewerScreen screen = com.peaknav.viewer.MapViewerSingleton.getViewerInstance();
            check.set(screen.cam.getPickRayStable(ex, ey).direction);
        });
        assertTrue(check.dot(atStart) > 0.999f,
                "the terrain that was under the finger should have followed it round the pin: " + check.dot(atStart));

        renderer.runOnRenderThread(() -> {
            com.peaknav.viewer.screens.MapViewerScreen screen = com.peaknav.viewer.MapViewerSingleton.getViewerInstance();
            fov[0] = screen.cam.fieldOfView;
            screen.controller.zoom(0.01f);
            fov[1] = screen.cam.fieldOfView;
            check.set(screen.cam.getPickRayStable(px, py).direction);
        });
        assertTrue(Math.abs(fov[1] - fov[0]) > 0.5f, "the pinch should have changed the field of view");
        assertTrue(check.dot(pinned) > 0.99999f, "and the pin must still be on its pixel: " + check.dot(pinned));
        com.peaknav.gesture.PhotoPin.clear();
    }

    private static int clamp255(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255)));
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
