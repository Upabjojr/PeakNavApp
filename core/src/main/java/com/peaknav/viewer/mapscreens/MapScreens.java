package com.peaknav.viewer.mapscreens;

import static com.peaknav.utils.PeakNavUtils.getC;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.peaknav.utils.Units;
import com.peaknav.viewer.MapApp;
import com.peaknav.viewer.MapViewerSingleton;
import com.peaknav.viewer.imgmapprovider.SatelliteImageProvider;

/**
 * The search screen and the map-data download chooser, drawn with scene2d so that every
 * platform has them: before, only Android did, with osmdroid fragments.
 *
 * <p>Each screen is an opaque layer over whichever screen the app is showing - the map, or the
 * welcome screen for the first-run download - and one at a time. Everything here runs on the
 * render thread; {@link #openSearch} and friends may be called from any thread and post.
 */
public final class MapScreens {

    private MapScreens() {
    }

    /** The screen showing now, or null. Render thread; read from others through {@link #isOpen}. */
    private static Base current;
    private static volatile boolean open;

    public static void openSearch() {
        Gdx.app.postRunnable(() -> show(new SearchScreen()));
    }

    /**
     * @param wizard the first-run chooser: it starts on the whole world, asks where the reader
     *               is, and has no Back button - there is nowhere to go back to
     */
    public static void openDownloadChooser(double lat, double lon, boolean goToAfterDownload, boolean wizard) {
        Gdx.app.postRunnable(() -> show(new DownloadAreaScreen(lat, lon, goToAfterDownload, wizard)));
    }

    /** Whether one of these screens is up: the system Back key closes it rather than the app. */
    public static boolean isOpen() {
        return open;
    }

    /** The system Back key, from a platform that has one. Any thread: it posts, as opening does. */
    public static void back() {
        Gdx.app.postRunnable(() -> {
            if (current != null) {
                current.back();
            }
        });
    }

    private static void show(Base screen) {
        if (current != null) {
            current.close();
        }
        Stage stage = currentStage();
        if (stage == null) {
            return;
        }
        current = screen;
        open = true;
        screen.attach(stage);
    }

    /** The stage of the screen on show: the welcome screen's for the wizard, else the map's. */
    private static Stage currentStage() {
        MapApp app = MapViewerSingleton.getAppInstance();
        if (app == null) {
            return null;
        }
        if (app.getScreen() == app.introScreen && app.introScreen.getStage() != null) {
            return app.introScreen.getStage();
        }
        return app.mapViewerScreen.getStage();
    }

    /**
     * The imagery of the 3D view's satellite layer, which these maps share: one setting, chosen
     * here or in the options, and one disk cache of tiles behind both. Landsat if none is set.
     */
    static SatelliteImageProvider mapProvider() {
        SatelliteImageProvider chosen = com.peaknav.utils.PreferencesManager.P == null
                ? null : com.peaknav.utils.PreferencesManager.P.getUnderlayImageProvider();
        return chosen != null ? chosen
                : SatelliteImageProvider.SatelliteProviderOptions.LANDSAT.getSatelliteImageProvider();
    }

    static float unit() {
        return Units.getWidgetUnitStep();
    }

    static Drawable white() {
        return getC().widgetTextures.getUniformDrawable(Color.WHITE);
    }

    static TextButton button(String text) {
        return getC().widgetGetter.getTextButton(text, false);
    }

    static Label.LabelStyle darkCaption() {
        return new Label.LabelStyle(getC().styleSingleton.getBitmapFontSmall(), Color.BLACK);
    }

    /**
     * What both screens share: an opaque layer over the whole stage that keeps every touch, key
     * and scroll to itself, a map, and closing.
     */
    abstract static class Base {

        final Table root = new Table();
        SlippyMap map;
        private Stage stage;

        Base() {
            root.setFillParent(true);
            root.setTouchable(Touchable.enabled);
            root.setBackground(getC().widgetTextures.getUniformDrawable(new Color(0.94f, 0.94f, 0.94f, 1f)));
            // The map screen's input goes to the stage first and to the 3D camera after it, and
            // the stage passes on anything no listener claims: without this a drag on the list
            // or a tap between buttons turned the camera behind the screen, and the desktop's
            // arrow keys flew it about while a place name was being typed.
            root.addListener(new InputListener() {
                @Override
                public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                    return true;
                }

                @Override
                public boolean keyDown(InputEvent event, int keycode) {
                    if (keycode == Input.Keys.ESCAPE || keycode == Input.Keys.BACK) {
                        back();
                    }
                    return true;
                }

                @Override
                public boolean keyUp(InputEvent event, int keycode) {
                    return true;
                }

                @Override
                public boolean keyTyped(InputEvent event, char character) {
                    return true;
                }

                @Override
                public boolean scrolled(InputEvent event, float x, float y, float amountX, float amountY) {
                    return true;
                }
            });
        }

        /** The list of imagery providers, opened by the map's imagery button. */
        private Table providerPanel;

        /**
         * The buttons down the map's right edge - locate, zoom in, zoom out, imagery - and the
         * list of imagery providers that opens beside them.
         */
        Table mapControls(float unit, Runnable locate) {
            Table column = new Table();
            com.badlogic.gdx.scenes.scene2d.ui.Button here =
                    getC().widgetTextures.getButtonWithIcon("icons/icon_here_gps.png");
            here.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, com.badlogic.gdx.scenes.scene2d.Actor actor) {
                    locate.run();
                }
            });
            column.add(here).size(unit).padBottom(0.2f * unit).row();
            column.add(zoomButton("+", 1f)).size(unit).padBottom(0.2f * unit).row();
            column.add(zoomButton("-", -1f)).size(unit).padBottom(0.2f * unit).row();
            com.badlogic.gdx.scenes.scene2d.ui.Button imagery =
                    getC().widgetTextures.getButtonWithIcon("icons/icon_checkbox_satellite.png");
            imagery.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, com.badlogic.gdx.scenes.scene2d.Actor actor) {
                    showProviders(!providerPanel.isVisible());
                }
            });
            column.add(imagery).size(unit);

            providerPanel = new Table();
            providerPanel.top();
            providerPanel.setVisible(false);
            Table controls = new Table();
            controls.add(providerPanel).top().width(7f * unit).padRight(0.2f * unit);
            controls.add(column).top();
            return controls;
        }

        private TextButton zoomButton(String text, float delta) {
            TextButton button = button(text);
            button.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, com.badlogic.gdx.scenes.scene2d.Actor actor) {
                    map.zoomBy(delta);
                }
            });
            return button;
        }

        /**
         * Every provider defined for the app - the built-in imagery and those added in the
         * options - the same list the options offer, filled afresh each time it opens. Adding one
         * is left to the options, where the setting lives.
         */
        private void showProviders(boolean visible) {
            providerPanel.setVisible(visible);
            if (!visible) {
                return;
            }
            providerPanel.clearChildren();
            for (final SatelliteImageProvider provider
                    : com.peaknav.utils.PreferencesManager.P.getSatelliteProviderRegistry().getAllProviders()) {
                boolean current = provider.getId().equals(map.getProvider().getId());
                providerPanel.add(panelRow(provider.getProviderName(), current, () -> choose(provider)))
                        .growX().padBottom(1f).row();
            }
        }

        private Table panelRow(String text, boolean current, Runnable onTap) {
            float unit = unit();
            Label label = new Label(text, darkCaption());
            label.setWrap(true);
            Table row = new Table();
            row.setBackground(current
                    ? getC().widgetTextures.getUniformDrawable(new Color(0.75f, 0.87f, 1f, 1f))
                    : white());
            row.setTouchable(Touchable.enabled);
            row.add(label).growX().pad(0.2f * unit, 0.3f * unit, 0.2f * unit, 0.3f * unit).minHeight(0.6f * unit);
            row.addListener(new com.badlogic.gdx.scenes.scene2d.utils.ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    onTap.run();
                }
            });
            return row;
        }

        /** The same as choosing it in the options: the 3D view's satellite layer follows. */
        private void choose(SatelliteImageProvider provider) {
            showProviders(false);
            map.setProvider(provider);
            getC().submitExecutorGeneric(() -> {
                com.peaknav.utils.PreferencesManager.P.setUnderlayImageProvider(provider);
                getC().widgetGetter.setCopyrightLabel(provider.getCopyrightNotice());
                getC().tileManager.tileRenderer.drawSatelliteLayer();
            });
        }

        SlippyMap newMap() {
            map = new SlippyMap(mapProvider(), unit(), white(),
                    getC().widgetTextures.getTextureRegionDrawable("icons/icon_loc_pin.png"),
                    getC().styleSingleton.getBitmapFontVerySmallWhite());
            return map;
        }

        void attach(Stage stage) {
            this.stage = stage;
            stage.addActor(root);
            root.toFront();
            stage.setKeyboardFocus(root);
            if (map != null) {
                stage.setScrollFocus(map);
            }
            onShown();
        }

        /** Called once the layer is on the stage. */
        void onShown() {
        }

        void back() {
            close();
        }

        boolean isShowing() {
            return current == this && root.getStage() != null;
        }

        void close() {
            Gdx.input.setOnscreenKeyboardVisible(false);
            if (stage != null) {
                stage.unfocus(root);
                if (map != null) {
                    stage.unfocus(map);
                }
            }
            root.remove();
            if (map != null) {
                map.dispose();
            }
            if (current == this) {
                current = null;
                open = false;
            }
        }

        Stage stage() {
            return stage;
        }
    }
}
