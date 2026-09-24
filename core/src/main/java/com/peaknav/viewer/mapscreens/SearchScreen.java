package com.peaknav.viewer.mapscreens;

import static com.peaknav.database.CheckMissingData.checkMissingElevationForCoord;
import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.getNativeScreenCaller;
import static com.peaknav.utils.PeakNavUtils.s;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Stack;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Align;
import com.peaknav.database.LuceneGeonameSearch;
import com.peaknav.network.NominatimResponse;
import com.peaknav.utils.CoordinateSearch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finding a place: a search box, its results over a map, and the map to pick a point on.
 *
 * <p>Behaves as the Android screen did. Every keystroke asks the offline index, which answers in
 * a millisecond or two; Search (or Enter) also asks the online search, whose answers are added
 * below when they come. Coordinates in any common form are offered as the one result. Picking a
 * result or tapping the map puts the pin there; Go To flies to the pin, or, where nothing is
 * downloaded yet, offers to download it first.
 */
class SearchScreen extends MapScreens.Base {

    private final TextField field;
    private final Table results = new Table();
    private final ScrollPane resultsPane;
    /** Where the pin is: the target to start with, as on Android, so Go To is never undefined. */
    private double pinLat, pinLon;
    /** Distinguishes the latest online search from older ones still on their way back. */
    private int searchGeneration = 0;
    /** The result rows in list order, and where each one points, for the arrow keys. */
    private final List<Table> resultRows = new ArrayList<>();
    private final List<double[]> resultPoints = new ArrayList<>();
    /** The row the arrow keys have highlighted, or -1: Enter goes there instead of searching. */
    private int selected = -1;

    SearchScreen() {
        float unit = MapScreens.unit();
        pinLat = getC().L.getTargetLatitude();
        pinLon = getC().L.getTargetLongitude();

        field = new TextField("", fieldStyle(unit));
        field.setMessageText(s("Search_place_title"));
        field.setTextFieldListener((textField, c) -> {
            if (c == '\n' || c == '\r') {
                onEnter(textField.getText());
            } else {
                searchOffline(textField.getText());
            }
        });
        field.addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                if (keycode == Input.Keys.DOWN) {
                    return moveSelection(1);
                } else if (keycode == Input.Keys.UP) {
                    return moveSelection(-1);
                }
                return false;
            }
        });
        TextButton search = MapScreens.button(s("Search"));
        search.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                searchAll(field.getText());
            }
        });

        Table top = new Table();
        top.add(field).growX().height(unit).padRight(0.2f * unit);
        top.add(search).height(unit).minWidth(2.5f * unit);
        root.add(top).growX().pad(0.2f * unit).row();

        SlippyMap map = newMap();
        map.setZoomRange(1f, 14f);
        map.setCenter(pinLat, pinLon);
        map.setZoom(9.5f);
        map.setMarker(pinLat, pinLon);
        map.setTapListener((lat, lon) -> {
            // A point picked on the map is an answer, as a result from the list is: the list
            // and the keyboard have nothing more to do, and they cover the map the point is on.
            hideKeyboard();
            showResults(false);
            setPin(lat, lon, false);
        });

        results.top();
        ScrollPane.ScrollPaneStyle paneStyle = new ScrollPane.ScrollPaneStyle();
        resultsPane = new ScrollPane(results, paneStyle);
        resultsPane.setScrollingDisabled(true, false);
        resultsPane.setOverscroll(false, false);
        resultsPane.addListener(new InputListener() {
            @Override
            public void enter(InputEvent event, float x, float y, int pointer, Actor fromActor) {
                if (resultsPane.getStage() != null) {
                    resultsPane.getStage().setScrollFocus(resultsPane);
                }
            }

            @Override
            public void exit(InputEvent event, float x, float y, int pointer, Actor toActor) {
                if (resultsPane.getStage() != null && toActor != null && toActor.isDescendantOf(map)) {
                    resultsPane.getStage().setScrollFocus(map);
                }
            }
        });

        Table over = new Table();
        over.top();
        over.setTouchable(Touchable.childrenOnly);
        over.add(resultsPane).growX().maxHeight(6f * unit).top().colspan(2).row();
        over.add().grow();
        over.add(mapControls(unit, this::locate)).top().right().pad(0.2f * unit);
        showResults(false);

        Stack stack = new Stack();
        stack.add(map);
        stack.add(over);
        root.add(stack).grow().row();

        TextButton back = MapScreens.button(s("Back"));
        back.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                close();
            }
        });
        TextButton goTo = MapScreens.button(s("Go_To"));
        goTo.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                goToPin();
            }
        });
        Table bottom = new Table();
        bottom.add(back).height(unit).minWidth(3f * unit).expandX().left();
        bottom.add(goTo).height(unit).minWidth(3f * unit).expandX().right();
        root.add(bottom).growX().pad(0.2f * unit);
    }

    /** Locate: the pin and the map go to where the device is. */
    private void locate() {
        getNativeScreenCaller().requestCurrentLocation((longitude, latitude) ->
                // The fix can come on any thread, and after the screen has gone.
                Gdx.app.postRunnable(() -> {
                    if (isShowing()) {
                        setPin(latitude, longitude, true);
                    }
                }));
    }

    @Override
    void onShown() {
        // The screen is there to be typed into: the cursor in the box and the keyboard up.
        stage().setKeyboardFocus(field);
        Gdx.input.setOnscreenKeyboardVisible(true);
    }

    private void hideKeyboard() {
        Gdx.input.setOnscreenKeyboardVisible(false);
        if (stage() != null) {
            stage().setKeyboardFocus(root);
        }
    }

    private void setPin(double lat, double lon, boolean center) {
        pinLat = lat;
        pinLon = lon;
        map.setMarker(lat, lon);
        if (center) {
            map.setCenter(lat, lon);
            map.setZoom(Math.max(map.getZoom(), 10f));
        }
    }

    private void goToPin() {
        final double lat = pinLat, lon = pinLon;
        close();
        if (checkMissingElevationForCoord(lat, lon)) {
            getNativeScreenCaller().askForDownloadScreen(lat, lon);
        } else {
            getC().submitExecutorGeneric(() -> getC().L.setCurrentTargetCoords(lat, lon));
        }
    }

    private void searchOffline(String typed) {
        searchGeneration++;   // an online answer to an earlier text no longer belongs here
        List<LuceneGeonameSearch.GeonameResult> found = new ArrayList<>();
        double[] coordinates = CoordinateSearch.parseCoordinates(typed);
        if (coordinates != null) {
            // Offered as the one result while typing, not jumped to: "46.02, 7" on the way to
            // "46.02, 7.74" already reads as coordinates.
            String label = String.format(Locale.ROOT, "%.5f, %.5f", coordinates[0], coordinates[1]);
            found.add(new LuceneGeonameSearch.GeonameResult(
                    label, label, (float) coordinates[0], (float) coordinates[1], -1));
        } else {
            String query = CoordinateSearch.cleanQuery(typed);
            // The index is opened after start-up; until it is, no results is the honest answer,
            // and the next keystroke asks again.
            LuceneGeonameSearch index = getC().luceneGeonameSearch;
            if (!query.isEmpty() && index != null) {
                found.addAll(index.searchGeoName(query));
            }
        }
        setResults(found);
    }

    /** Search or Enter: the offline results at once, then the online ones added as they come. */
    private void searchAll(String typed) {
        double[] coordinates = CoordinateSearch.parseCoordinates(typed);
        if (coordinates != null) {
            hideKeyboard();
            showResults(false);
            setPin(coordinates[0], coordinates[1], true);
            return;
        }
        searchOffline(typed);
        String query = CoordinateSearch.cleanQuery(typed);
        if (query.isEmpty()) {
            return;
        }
        final int generation = searchGeneration;
        getC().onlineSearch.parseDestinationText(query, (ArrayList<NominatimResponse> responses) ->
                Gdx.app.postRunnable(() -> {
                    if (!isShowing() || generation != searchGeneration || responses == null) {
                        return;
                    }
                    for (NominatimResponse response : responses) {
                        addResult(response.displayName, response.lat, response.lon);
                    }
                    showResults(results.hasChildren());
                }));
    }

    /** Enter: with a result highlighted, it is picked and gone to, as a tap and Go To; else a search. */
    private void onEnter(String typed) {
        if (selected >= 0 && resultsPane.isVisible()) {
            double[] point = resultPoints.get(selected);
            setPin(point[0], point[1], false);
            goToPin();
        } else {
            searchAll(typed);
        }
    }

    /**
     * Up and Down: the highlight moves through the results, from none to the first, stopping
     * at either end, and the list scrolls to keep it in view. False, so the key goes on to
     * the text field, when there is no list to move in.
     */
    private boolean moveSelection(int step) {
        if (!resultsPane.isVisible() || resultRows.isEmpty()) {
            return false;
        }
        int next = Math.max(0, Math.min(resultRows.size() - 1, selected + step));
        select(next);
        Table row = resultRows.get(next);
        resultsPane.layout();
        resultsPane.scrollTo(row.getX(), row.getY(), row.getWidth(), row.getHeight());
        return true;
    }

    private void select(int index) {
        if (selected >= 0 && selected < resultRows.size()) {
            resultRows.get(selected).setBackground(MapScreens.white());
        }
        selected = index;
        if (index >= 0) {
            resultRows.get(index).setBackground(
                    getC().widgetTextures.getUniformDrawable(SELECTED_ROW));
        }
    }

    private static final Color SELECTED_ROW = new Color(0.78f, 0.87f, 1f, 1f);

    private void setResults(List<LuceneGeonameSearch.GeonameResult> found) {
        results.clearChildren();
        resultRows.clear();
        resultPoints.clear();
        selected = -1;
        for (LuceneGeonameSearch.GeonameResult result : found) {
            addResult(result.getFullName(), result.lat, result.lon);
        }
        showResults(!found.isEmpty());
    }

    private void addResult(String text, final double lat, final double lon) {
        float unit = MapScreens.unit();
        Label label = new Label(text, MapScreens.darkCaption());
        label.setWrap(true);
        label.setAlignment(Align.left);
        Table row = new Table();
        row.setBackground(MapScreens.white());
        row.setTouchable(Touchable.enabled);
        row.add(label).growX().pad(0.2f * unit, 0.3f * unit, 0.2f * unit, 0.3f * unit).minHeight(0.6f * unit);
        row.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                hideKeyboard();
                showResults(false);
                setPin(lat, lon, true);
            }
        });
        results.add(row).growX().padBottom(1f).row();
        resultRows.add(row);
        resultPoints.add(new double[]{lat, lon});
    }

    private void showResults(boolean visible) {
        if (!visible) {
            select(-1);
        }
        resultsPane.setVisible(visible);
        if (visible) {
            resultsPane.setScrollY(0);
        }
    }

    private static TextField.TextFieldStyle fieldStyle(float unit) {
        TextField.TextFieldStyle style = new TextField.TextFieldStyle();
        style.font = getC().styleSingleton.getBitmapFontMedium();
        style.fontColor = Color.BLACK;
        style.messageFont = style.font;
        style.messageFontColor = new Color(0.45f, 0.45f, 0.45f, 1f);
        com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable background =
                (com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable) MapScreens.white();
        com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable padded =
                new com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable(background);
        padded.setLeftWidth(0.25f * unit);
        padded.setRightWidth(0.25f * unit);
        style.background = padded;
        com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable cursor =
                (com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable)
                        getC().widgetTextures.getUniformDrawable(Color.BLACK);
        cursor = new com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable(cursor);
        cursor.setMinWidth(Math.max(2f, 0.03f * unit));
        style.cursor = cursor;
        style.selection = getC().widgetTextures.getUniformDrawable(new Color(0.55f, 0.75f, 1f, 1f));
        return style;
    }
}
