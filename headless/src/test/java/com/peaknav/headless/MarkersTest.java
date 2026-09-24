package com.peaknav.headless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.peaknav.markers.Marker;
import com.peaknav.markers.MarkerStore;
import com.peaknav.utils.PeakNavUtils;
import com.peaknav.viewer.MapViewerSingleton;
import com.peaknav.viewer.PhotoSkylineAligner;
import com.peaknav.viewer.labels.DrawLabelCategory;
import com.peaknav.viewer.labels.FeatureInfo;
import com.peaknav.viewer.labels.PoiObject;
import com.peaknav.viewer.screens.MapViewerScreen;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.File;
import java.util.List;

/**
 * Saving a point as a marker: the pin's button keeps it, a flag stands on the map, a tap on the
 * flag opens its pane, and the pane deletes it. Zermatt. Every marker the test saves it deletes
 * again, and the file goes if it was not there before: the renderer shares its data folder with
 * the desktop app.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MarkersTest {

    private static final int WIDTH = 640;
    private static final int HEIGHT = 400;
    private static final double LAT = 46.0207;
    private static final double LON = 7.7491;

    private PeakNavRenderer renderer;
    private boolean fileWasThere;

    @BeforeAll
    void boot() {
        assumeTrue(System.getenv("DISPLAY") != null,
                "no DISPLAY: the renderer cannot create a GL context here");
        renderer = PeakNavRenderer.start(WIDTH, HEIGHT);
        renderer.moveTo(LAT, LON);
        renderer.awaitTilesLoaded(120_000);
        assumeTrue(!Float.isNaN(PhotoSkylineAligner.loadedTerrain().elevationMeters(LAT, LON)),
                "no elevation data for Zermatt on this machine");
        fileWasThere = markersFile().exists();
    }

    @AfterAll
    void shutdown() {
        if (renderer != null) {
            renderer.close();
        }
    }

    private static FileHandle markersFile() {
        return com.badlogic.gdx.Gdx.files.external(MarkerStore.FILE_NAME);
    }

    private static MapViewerScreen screen() {
        return MapViewerSingleton.getViewerInstance();
    }

    private static File capture(String name) {
        File dir = new File(System.getProperty("java.io.tmpdir"), "peaknav-markers");
        dir.mkdirs();
        return new File(dir, name);
    }

    private void cleanUp(List<Marker> saved) {
        renderer.runOnRenderThread(() -> {
            for (Marker m : saved) {
                PeakNavUtils.getC().markerStore.remove(m.latitude, m.longitude);
            }
            if (!fileWasThere) {
                markersFile().delete();
            }
        });
    }

    private List<Marker> markers() {
        final List<?>[] out = new List<?>[1];
        renderer.runOnRenderThread(() -> out[0] = PeakNavUtils.getC().markerStore.getMarkers());
        @SuppressWarnings("unchecked")
        List<Marker> list = (List<Marker>) out[0];
        return list;
    }

    @Test
    @Order(1)
    @DisplayName("the pin's button saves the point, a flag stands there, and its pane deletes it")
    void aTappedPointBecomesAFlag() {
        int before = markers().size();
        renderer.aim(245, -6);
        renderer.settle(1_000);
        renderer.tap(WIDTH / 2, HEIGHT / 2 + 60);
        renderer.settle(300);
        final boolean[] pinned = new boolean[1];
        renderer.runOnRenderThread(() -> pinned[0] = screen().impact != null);
        assumeTrue(pinned[0], "the tap found no ground to pin");

        // The button, as a tap on it goes.
        renderer.runOnRenderThread(() -> screen().tableLocation.buttonSaveMarker.fire(new ChangeListener.ChangeEvent()));
        renderer.settle(500);
        List<Marker> after = markers();
        assertEquals(before + 1, after.size(), "no marker saved");
        Marker saved = after.get(after.size() - 1);
        try {
            assertTrue(saved.name.startsWith(PeakNavUtils.s("Marker_kind") + " "), saved.name);
            assertFalse(Double.isNaN(saved.elevation));
            assertTrue(markersFile().readString("UTF-8").contains("<name>" + saved.name + "</name>"),
                    "not written to " + markersFile().path());

            final List<?>[] drawn = new List<?>[1];
            final float[][] box = new float[1][];
            renderer.runOnRenderThread(() -> {
                drawn[0] = screen().labelRenderer.drawnMarkerNames();
                box[0] = screen().labelRenderer.drawnMarkerBox(saved.name);
            });
            assertTrue(drawn[0].contains(saved.name), "no flag drawn for " + saved.name + ": " + drawn[0]);
            renderer.captureWithUi(capture("marker_flag.png"));

            // A tap on the flag's cloth opens its pane, with the delete button.
            int x = Math.round(box[0][0] + box[0][2] * 0.2f);
            int y = Math.round(HEIGHT - (box[0][1] + box[0][3] * 0.8f));
            renderer.tap(x, y);
            renderer.settle(300);
            final FeatureInfo[] shown = new FeatureInfo[1];
            final List<?>[] lines = new List<?>[1];
            renderer.runOnRenderThread(() -> {
                shown[0] = screen().featureInfoPane.getShown();
                lines[0] = screen().featureInfoPane.getShownLines();
            });
            assertNotNull(shown[0], "the tap on the flag opened no pane");
            System.out.println("[marker] " + lines[0]);
            assertEquals(saved.name, shown[0].title);
            assertTrue(lines[0].contains("[" + PeakNavUtils.s("Marker_delete") + "]"), String.valueOf(lines[0]));
            renderer.captureWithUi(capture("marker_pane.png"));

            final boolean[] pressed = new boolean[1];
            renderer.runOnRenderThread(() -> pressed[0] = screen().featureInfoPane.pressAction());
            assertTrue(pressed[0]);
            renderer.settle(300);
            assertEquals(before, markers().size(), "the marker was not deleted");
            renderer.runOnRenderThread(() -> drawn[0] = screen().labelRenderer.drawnMarkerNames());
            assertFalse(drawn[0].contains(saved.name), "the deleted marker's flag is still drawn");
        } finally {
            cleanUp(after);
        }
    }

    @Test
    @Order(2)
    @DisplayName("a peak's pane saves it as a marker named after it")
    void aPeakSavedFromItsPane() {
        renderer.aim(245, 12);
        renderer.settle(1_000);
        assertTrue(renderer.refreshLabelsAndWait(60_000), "the labels did not come");
        renderer.settle(500);
        final PoiObject[] peak = new PoiObject[1];
        final int[][] at = new int[1][];
        renderer.runOnRenderThread(() -> PeakNavUtils.getC().O.iterateOverDisplayablePois(poi -> {
            if (peak[0] == null && poi.drawLabelCategory == DrawLabelCategory.PEAK && poi.drawLabel.isVisible()) {
                DrawLabelCategory c = poi.drawLabelCategory;
                float px = poi.drawLabel.getScreenPoiX() + c.rotationAngleCos * 12 - c.rotationAngleSin * 6;
                float py = poi.drawLabel.getScreenLabelY() + c.rotationAngleSin * 12 + c.rotationAngleCos * 6;
                if (px > 20 && px < WIDTH - 20 && py > 20 && py < HEIGHT - 20) {
                    peak[0] = poi;
                    at[0] = new int[]{Math.round(px), Math.round(HEIGHT - py)};
                }
            }
        }));
        assertNotNull(peak[0], "no peak label on screen to tap");
        renderer.tap(at[0][0], at[0][1]);
        renderer.settle(300);
        final boolean[] pressed = new boolean[1];
        renderer.runOnRenderThread(() -> pressed[0] = screen().featureInfoPane.pressAction());
        assertTrue(pressed[0], "the peak's pane had no Save as marker");
        renderer.settle(500);
        List<Marker> all = markers();
        try {
            Marker saved = all.get(all.size() - 1);
            assertEquals(peak[0].name, saved.name);
            assertEquals(peak[0].elevation, saved.elevation, 1.0);
            renderer.captureWithUi(capture("marker_peak.png"));
        } finally {
            cleanUp(all);
        }
    }
}
