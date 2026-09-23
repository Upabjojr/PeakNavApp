package com.peaknav.headless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.badlogic.gdx.math.Vector3;
import com.peaknav.areas.MapArea;
import com.peaknav.utils.PeakNavUtils;
import com.peaknav.viewer.MapViewerSingleton;
import com.peaknav.viewer.PhotoSkylineAligner;
import com.peaknav.viewer.labels.DrawLabel;
import com.peaknav.viewer.labels.DrawLabelCategory;
import com.peaknav.viewer.labels.FeatureInfo;
import com.peaknav.viewer.labels.PoiObject;
import com.peaknav.viewer.renderer_gdx.LabelRenderer;
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
 * A tapped label opens a pane about what it names; a tap anywhere else closes it. Zermatt, looking
 * at the Matterhorn, as in {@link PeakNavRendererTest}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FeatureInfoPaneTest {

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

    private static File capture(String name) {
        File dir = new File(System.getProperty("java.io.tmpdir"), "peaknav-feature-info");
        dir.mkdirs();
        return new File(dir, name);
    }

    /** A point well inside a label's plate, in window pixels, y down. */
    private static int[] onThePlate(DrawLabel label) {
        DrawLabelCategory c = label.drawLabelCategory;
        float along = 12, across = 6;
        float x = label.getScreenPoiX() + c.rotationAngleCos * along - c.rotationAngleSin * across;
        float y = label.getScreenLabelY() + c.rotationAngleSin * along + c.rotationAngleCos * across;
        return new int[]{Math.round(x), Math.round(HEIGHT - y)};
    }

    @Test
    @Order(1)
    @DisplayName("tapping a peak's label opens its pane, puts the pin on it; a tap elsewhere closes it")
    void aPeaksLabelOpensItsPane() {
        renderer.aim(245, 12);
        renderer.settle(1_000);
        assertTrue(renderer.refreshLabelsAndWait(60_000), "the labels did not come");
        renderer.settle(500);

        final PoiObject[] peak = new PoiObject[1];
        final int[][] at = new int[1][];
        renderer.runOnRenderThread(() -> PeakNavUtils.getC().O.iterateOverDisplayablePois(poi -> {
            if (peak[0] == null && poi.drawLabelCategory == DrawLabelCategory.PEAK && poi.drawLabel.isVisible()) {
                int[] p = onThePlate(poi.drawLabel);
                if (p[0] > 20 && p[0] < WIDTH - 20 && p[1] > 20 && p[1] < HEIGHT - 20) {
                    peak[0] = poi;
                    at[0] = p;
                }
            }
        }));
        assertNotNull(peak[0], "no peak label on screen to tap");

        renderer.tap(at[0][0], at[0][1]);
        renderer.settle(300);
        final FeatureInfo[] shown = new FeatureInfo[1];
        final List<?>[] lines = new List<?>[1];
        renderer.runOnRenderThread(() -> {
            shown[0] = screen().featureInfoPane.getShown();
            lines[0] = screen().featureInfoPane.getShownLines();
        });
        assertNotNull(shown[0], "the tap on " + peak[0].name + "'s label opened no pane");
        System.out.println("[feature] " + lines[0]);
        assertEquals(peak[0].name, shown[0].title);
        assertEquals(PeakNavUtils.s("Feature_kind_peak"), shown[0].kind);
        String text = String.valueOf(lines[0]);
        assertTrue(text.contains(PeakNavUtils.s("Feature_elevation") + ": "), text);
        assertTrue(text.contains(PeakNavUtils.s("Feature_distance") + ": "), text);
        assertTrue(text.contains(PeakNavUtils.s("Feature_coordinates") + ": "), text);
        assertFalse(shown[0].tags.isEmpty(), "the peak's tags are not listed");

        // The pin went on the peak, as a tap on the ground there would put it.
        final Vector3[] impact = new Vector3[1];
        renderer.runOnRenderThread(() -> impact[0] = screen().impact == null ? null : screen().impact.cpy());
        assertNotNull(impact[0], "no pin on the peak");
        assertEquals(peak[0].getPosition3D(new Vector3()).dst(impact[0]), 0f, 1e-6f);
        renderer.captureWithUi(capture("feature_peak.png"));

        // A tap on the pane itself, through the app's input as a finger's goes, stays on the pane:
        // not a new point picked, nor the pane closed, by the map beneath it.
        renderer.settle(300);
        renderer.runOnRenderThread(() -> {
            com.badlogic.gdx.scenes.scene2d.Actor panel = screen().featureInfoPane.getTable().getChildren().first();
            com.badlogic.gdx.math.Vector2 inside = panel.localToStageCoordinates(
                    new com.badlogic.gdx.math.Vector2(4, panel.getHeight() * 0.4f));
            com.badlogic.gdx.math.Vector2 window = screen().getStage().stageToScreenCoordinates(inside);
            com.badlogic.gdx.InputProcessor input = com.badlogic.gdx.Gdx.input.getInputProcessor();
            input.touchDown((int) window.x, (int) window.y, 0, com.badlogic.gdx.Input.Buttons.LEFT);
            input.touchUp((int) window.x, (int) window.y, 0, com.badlogic.gdx.Input.Buttons.LEFT);
        });
        renderer.settle(300);
        final Vector3[] after = new Vector3[1];
        final FeatureInfo[] still = new FeatureInfo[1];
        renderer.runOnRenderThread(() -> {
            after[0] = screen().impact == null ? null : screen().impact.cpy();
            still[0] = screen().featureInfoPane.getShown();
        });
        assertNotNull(still[0], "a tap on the pane went through and closed it");
        assertEquals(peak[0].name, still[0].title, "a tap on the pane went through to another label");
        assertNotNull(after[0]);
        assertEquals(0f, impact[0].dst(after[0]), 1e-6f, "a tap on the pane went through and moved the pin");

        renderer.runOnRenderThread(() -> screen().featureInfoPane.setTagsOpen(true));
        renderer.settle(300);
        renderer.runOnRenderThread(() -> lines[0] = screen().featureInfoPane.getShownLines());
        assertTrue(lines[0].size() > shown[0].rows.size() + shown[0].tags.size(), String.valueOf(lines[0]));
        renderer.captureWithUi(capture("feature_peak_tags.png"));

        // Low in the middle, on the ground: no label there.
        renderer.tap(WIDTH / 2, HEIGHT - 60);
        renderer.settle(300);
        final boolean[] open = new boolean[1];
        renderer.runOnRenderThread(() -> open[0] = screen().featureInfoPane.isShown());
        assertFalse(open[0], "a tap away from the labels left the pane open");
    }

    @Test
    @Order(2)
    @DisplayName("tapping an area's label - a lake, a range - opens its pane")
    void anAreasLabelOpensItsPane() {
        final LabelRenderer.DrawnArea[] area = new LabelRenderer.DrawnArea[1];
        for (int bearing = 0; bearing < 360 && area[0] == null; bearing += 45) {
            renderer.aim(bearing, 3);
            renderer.settle(1_500);
            renderer.runOnRenderThread(() -> {
                for (LabelRenderer.DrawnArea a : screen().labelRenderer.drawnAreas()) {
                    if (area[0] == null && a.width > 10 && a.height > 6) {
                        area[0] = a;
                    }
                }
            });
        }
        assumeTrue(area[0] != null, "no area label drawn around Zermatt");
        int x = Math.round(area[0].x + area[0].width / 2);
        int y = Math.round(HEIGHT - (area[0].y + area[0].height / 2));
        // A peak's label may lie over the area's plate, and then it is the one tapped.
        final Object[] hit = new Object[1];
        renderer.runOnRenderThread(() -> hit[0] = screen().labelRenderer.featureAt(x, HEIGHT - y, 0));
        assumeTrue(hit[0] instanceof MapArea, "the area's plate is covered by another label");

        renderer.tap(x, y);
        renderer.settle(300);
        final FeatureInfo[] shown = new FeatureInfo[1];
        final List<?>[] lines = new List<?>[1];
        renderer.runOnRenderThread(() -> {
            shown[0] = screen().featureInfoPane.getShown();
            lines[0] = screen().featureInfoPane.getShownLines();
        });
        assertNotNull(shown[0], "the tap on " + area[0].area.name + "'s label opened no pane");
        System.out.println("[feature] " + lines[0]);
        assertEquals(area[0].area.name, shown[0].title);
        // The pin moved to the area's middle.
        final Vector3[] impact = new Vector3[1];
        renderer.runOnRenderThread(() -> impact[0] = screen().impact == null ? null : screen().impact.cpy());
        assertNotNull(impact[0], "no pin on the area");
        assertEquals(area[0].area.lat, impact[0].y, 1e-4f, "the pin is not at the area's middle");
        renderer.captureWithUi(capture("feature_area.png"));

        renderer.runOnRenderThread(() -> screen().featureInfoPane.hide());
    }
}
