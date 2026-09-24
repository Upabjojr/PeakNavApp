package com.peaknav.headless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector3;
import com.peaknav.gesture.MountainInputController;
import com.peaknav.utils.PeakNavUtils;
import com.peaknav.utils.Units;
import com.peaknav.viewer.MapViewerSingleton;
import com.peaknav.viewer.PhotoSkylineAligner;
import com.peaknav.viewer.screens.MapViewerScreen;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The camera's own controls against the other things that move it: the gyroscope, an orbit,
 * and a tap on the ground close by. Zermatt, as in {@link PeakNavRendererTest}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CameraControlsTest {

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
        renderer.awaitTilesLoaded(120_000);
        assumeTrue(!Float.isNaN(PhotoSkylineAligner.loadedTerrain().elevationMeters(LAT, LON)),
                "no elevation data for Zermatt on this machine");
    }

    @AfterAll
    void shutdown() {
        if (renderer != null) {
            renderer.close();
        }
    }

    private static MapViewerScreen screen() {
        return MapViewerSingleton.getViewerInstance();
    }

    private void setGyroscope(boolean on) {
        renderer.runOnRenderThread(() -> screen().tableTool.buttonOrientation.setChecked(on));
    }

    /** A drag, a zoom (pinch, wheel and key all end in zoom()) and a held arrow key. */
    private void pokeTheCamera() {
        renderer.runOnRenderThread(() -> {
            MountainInputController controller = screen().controller;
            controller.zoom(0.01f);
            controller.touchDown(100, 100, 0, Input.Buttons.LEFT);
            controller.touchDragged(220, 160, 0);
            controller.touchUp(220, 160, 0, Input.Buttons.LEFT);
            controller.keyDown(Input.Keys.LEFT);
        });
        renderer.settle(300);
        renderer.runOnRenderThread(() -> screen().controller.keyUp(Input.Keys.LEFT));
    }

    @Test
    @Order(1)
    @DisplayName("with the gyroscope on, drags, zooms and keys leave the camera alone; off, they work again")
    void gyroscopeSuspendsTheControls() {
        renderer.aim(40, 5);
        renderer.settle(200);
        setGyroscope(true);
        final float[] fov = new float[1];
        renderer.runOnRenderThread(() -> fov[0] = screen().cam.fieldOfView);
        Vector3 direction = renderer.cameraDirection();

        pokeTheCamera();

        final float[] after = new float[1];
        renderer.runOnRenderThread(() -> after[0] = screen().cam.fieldOfView);
        assertEquals(fov[0], after[0], 1e-4f, "the zoom went through while the gyroscope was on");
        assertTrue(direction.dot(renderer.cameraDirection()) > 0.99999f,
                "the drag or the arrow key turned the camera while the gyroscope was on");

        setGyroscope(false);
        renderer.aim(40, 5);
        renderer.settle(200);
        direction = renderer.cameraDirection();
        renderer.runOnRenderThread(() -> fov[0] = screen().cam.fieldOfView);

        pokeTheCamera();

        renderer.runOnRenderThread(() -> after[0] = screen().cam.fieldOfView);
        assertTrue(Math.abs(fov[0] - after[0]) > 1e-3f, "the zoom did not come back with the gyroscope off");
        assertTrue(direction.dot(renderer.cameraDirection()) < 0.9999f,
                "dragging did not come back with the gyroscope off");
    }

    @Test
    @Order(2)
    @DisplayName("a tap on the ground steeply below the camera finds it")
    void tapOnTheGroundCloseBy() {
        renderer.moveTo(LAT, LON);
        renderer.awaitTilesLoaded(120_000);
        renderer.setElevationMeters(30);
        // 60 degrees down: well below the depth maps, which reach 28 degrees under the horizon.
        renderer.aim(0, -60);
        renderer.settle(1_500);

        final Vector3[] hit = new Vector3[1];
        renderer.runOnRenderThread(() -> hit[0] = screen().detectClicked3DPosition(WIDTH / 2f, HEIGHT / 2f));
        assertNotNull(hit[0], "nothing was found under a tap on the ground just below the camera");

        Vector3 eye = renderer.cameraPosition();
        float meters = Units.convertLatitsToMeters(hit[0].dst(eye));
        // 50 m above the ground at the target (the bar's 30 plus its 20), 60 degrees down:
        // about 58 m on flat ground, and Zermatt's valley floor is not far from flat.
        assertTrue(meters > 5 && meters < 400, "the tap found a point " + meters + " m away");

        float lat = hit[0].y;
        float lon = Units.convertLatitsToLonits(hit[0].x, (float) PeakNavUtils.getC().L.getTargetLatitude());
        float groundMeters = PhotoSkylineAligner.loadedTerrain().elevationMeters(lat, lon);
        float ground = Units.convertMetersToLatits(groundMeters)
                - com.peaknav.elevation.ElevationUtils.getElevationCorrectionForRoundEarth(lat, lon);
        assertEquals(0f, Units.convertLatitsToMeters(hit[0].z - ground), 2f,
                "the point found is not on the ground");
    }

    @Test
    @Order(3)
    @DisplayName("after an orbit, the elevation bar measures from the ground under the camera")
    void orbitLeavesTheBarOnTheGroundBelow() {
        renderer.moveTo(LAT, LON);
        renderer.awaitTilesLoaded(120_000);
        renderer.setElevationMeters(400);
        renderer.settle(500);
        Vector3 eye = renderer.cameraPosition();
        // A centre 3 km north: a 6-degree-a-second orbit carries the camera about 1 km in 3 s.
        Vector3 centre = new Vector3(eye.x, eye.y + Units.convertMetersToLatits(3000), eye.z);
        renderer.startOrbit(centre);
        renderer.settle(3_000);
        renderer.stopOrbit();
        renderer.settle(500);

        Vector3 stopped = renderer.cameraPosition();
        float lat = stopped.y;
        float lon = Units.convertLatitsToLonits(stopped.x, (float) PeakNavUtils.getC().L.getTargetLatitude());
        float groundMeters = PhotoSkylineAligner.loadedTerrain().elevationMeters(lat, lon);
        assumeTrue(!Float.isNaN(groundMeters), "no terrain where the orbit stopped");
        float ground = Units.convertMetersToLatits(groundMeters)
                - com.peaknav.elevation.ElevationUtils.getElevationCorrectionForRoundEarth(lat, lon);
        float expected = Units.convertLatitsToMeters(stopped.z - ground - screen().LIFT_ELEV);

        final double[] barMeters = new double[1];
        renderer.runOnRenderThread(() -> barMeters[0] = screen().getCameraElevationMeters());
        assertEquals(expected, barMeters[0], 2.0,
                "the bar measures from the ground the orbit started over, not the ground below");
    }

    @Test
    @Order(4)
    @DisplayName("the elevation readout gives the viewpoint's height above the sea, over the coordinates")
    void elevationReadout() throws Exception {
        renderer.moveTo(LAT, LON);
        renderer.awaitTilesLoaded(120_000);
        renderer.setElevationMeters(250);
        renderer.aim(245, 5);
        final boolean[] was = new boolean[1];
        renderer.runOnRenderThread(() -> {
            was[0] = com.peaknav.utils.PreferencesManager.P.isShowElevation();
            com.peaknav.utils.PreferencesManager.P.setShowElevation(true);
        });
        try {
            renderer.settle(800);
            Vector3 eye = renderer.cameraPosition();
            float lat = eye.y;
            float lon = Units.convertLatitsToLonits(eye.x, (float) PeakNavUtils.getC().L.getTargetLatitude());
            float ground = PhotoSkylineAligner.loadedTerrain().elevationMeters(lat, lon);
            final double[] bar = new double[1];
            final String[] text = new String[1];
            renderer.runOnRenderThread(() -> {
                bar[0] = screen().getCameraElevationMeters();
                text[0] = screen().labelRenderer.getElevationText();
            });
            double expected = ground + bar[0] + Units.convertLatitsToMeters(screen().LIFT_ELEV);
            System.out.println("[elevation] " + text[0] + " expected " + expected);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(-?\\d+) m$").matcher(text[0]);
            assertTrue(m.find(), "no height in metres in \"" + text[0] + "\"");
            assertEquals(expected, Integer.parseInt(m.group(1)), 3.0, text[0]);
            renderer.captureWithUi(new java.io.File(System.getProperty("java.io.tmpdir"), "peaknav-feature-info/elevation.png"));

            // With a track loaded, the scrub bar runs just above the coordinates: the readout goes over it.
            renderer.loadGpx("<gpx xmlns=\"http://www.topografix.com/GPX/1/1\" version=\"1.1\"><trk><trkseg>"
                    + "<trkpt lat=\"46.0207\" lon=\"7.7491\"/><trkpt lat=\"46.0000\" lon=\"7.7300\"/>"
                    + "</trkseg></trk></gpx>");
            renderer.settle(800);
            renderer.captureWithUi(new java.io.File(System.getProperty("java.io.tmpdir"), "peaknav-feature-info/elevation_gpx.png"));
            renderer.clearGpx();
        } finally {
            renderer.runOnRenderThread(() -> com.peaknav.utils.PreferencesManager.P.setShowElevation(was[0]));
        }
    }

    /** The field of view change one typed character makes, with Shift held or not. */
    private float zoomStep(char character, boolean shift) {
        final float[] fov = new float[2];
        renderer.runOnRenderThread(() -> {
            MountainInputController controller = screen().controller;
            fov[0] = screen().cam.fieldOfView;
            if (shift) controller.keyDown(Input.Keys.SHIFT_RIGHT);
            controller.keyTyped(character);
            if (shift) controller.keyUp(Input.Keys.SHIFT_RIGHT);
            fov[1] = screen().cam.fieldOfView;
        });
        return fov[1] - fov[0];
    }

    @Test
    @Order(5)
    @DisplayName("Shift makes the zoom keys finer, with the characters Shift turns them into")
    void shiftZoomsFiner() {
        renderer.aim(40, 5);
        renderer.settle(200);
        float in = zoomStep('+', false);
        float out = zoomStep('-', false);
        // '*' is Shift and '+' on Italian and German keyboards; '_' is Shift and '-'.
        float fineIn = zoomStep('*', true);
        float fineOut = zoomStep('_', true);
        assertTrue(in != 0f && out != 0f, "the zoom keys did nothing");
        assertEquals(Math.signum(in), Math.signum(fineIn), "Shift and '+' zoomed the wrong way");
        assertEquals(Math.signum(out), Math.signum(fineOut), "Shift and '-' zoomed the wrong way");
        assertTrue(Math.abs(fineIn) < 0.5f * Math.abs(in), "Shift and '+' was not finer: " + fineIn + " vs " + in);
        assertTrue(Math.abs(fineOut) < 0.5f * Math.abs(out), "Shift and '-' was not finer: " + fineOut + " vs " + out);
        assertEquals(0f, zoomStep('*', false), 0f, "'*' without Shift zoomed");
    }
}
