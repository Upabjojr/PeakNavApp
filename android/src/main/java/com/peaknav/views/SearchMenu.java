package com.peaknav.views;

import static androidx.core.content.ContextCompat.getSystemService;
import static com.peaknav.database.CheckMissingData.checkMissingElevationForCoord;
import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.getNativeScreenCaller;
import static com.peaknav.utils.PeakNavUtils.s;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.peaknav.R;
import com.peaknav.compatibility.NativeScreenCallerAndroid;
import com.peaknav.database.LuceneGeonameSearch;
import com.peaknav.network.NominatimResponse;
import com.peaknav.utils.CoordinateSearch;

import org.osmdroid.api.IMapController;
import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.util.MapTileIndex;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

public class SearchMenu extends Fragment {

    private ArrayList<NominatimResponse> nominatimResponses = new ArrayList<>();
    private EditText searchMenuText;
    private ListView searchResultListView;
    private ArrayAdapter<String> arrayAdapter;
    private Button buttonGoTo;
    private Button buttonMapBack;
    private ImageView buttonGoToCurrent;
    private IMapController iMapController;
    private GeoPoint pointGoTo;
    private final List<LuceneGeonameSearch.GeonameResult> geonameResults = new LinkedList<>();
    private View view;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {

        view = inflater.inflate(R.layout.search_menu, container, false);

        createMapEntities();
        createMapButtons();

        searchResultListView = view.findViewById(R.id.search_result_list_view);

        arrayAdapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_list_item_1);
        searchResultListView.setAdapter(arrayAdapter);
        searchResultListView.setOnItemClickListener((adapterView, view, i, l) -> {
            LuceneGeonameSearch.GeonameResult feature = this.geonameResults.get(i);
            destinationChosen(feature);
            /*
            NominatimResponse nominatimResponse = nominatimResponses.get(i);
            destinationChosen(nominatimResponse);
             */

            hideKeyboard();
        });

        searchMenuText = view.findViewById(R.id.search_menu_text);
        Button doSearch = view.findViewById(R.id.do_search);
        doSearch.setText(s("Search"));

        searchMenuText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void afterTextChanged(Editable editable) {
                doSearchResults();
            }
        });

        doSearch.setOnClickListener(view -> {
            // Coordinates need no list: pressing Search marks them on the map, ready for Go To.
            double[] coordinates = CoordinateSearch.parseCoordinates(searchMenuText.getText().toString());
            if (coordinates != null) {
                destinationChosen(new GeoPoint(coordinates[0], coordinates[1]));
                hideKeyboard();
                return;
            }
            doSearchResults();
        });

        // addNominatimResponses();
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // The screen exists to be typed into, so it opens with the cursor in the box and the
        // keyboard up. Nothing asked for either before: the map took the eye and the reader had
        // to tap the box first. Once, here, rather than in onResume, so coming back to a list of
        // results the reader has already scrolled does not throw the keyboard up over it again.
        // Posted: the keyboard only answers a view that is attached and focused, which it is not
        // until the transaction that adds this fragment has run.
        searchMenuText.requestFocus();
        searchMenuText.post(this::showKeyboard);
        // And back up whenever the box is touched again, after a tap on the map or a result put
        // the keyboard away. Android raises it by itself on a touch that gives the box focus, but
        // not reliably on one that finds it already focused with the keyboard dismissed (the back
        // key hides the keyboard and leaves the focus). Posted, so the touch has given the focus
        // by then; not consumed, so the cursor still goes where the finger is.
        searchMenuText.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                v.post(this::showKeyboard);
            }
            return false;
        });
    }

    private void showKeyboard() {
        if (!isAdded() || searchMenuText == null || !searchMenuText.hasFocus()) {
            return;
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(getContext(), InputMethodManager.class);
        if (imm != null) {
            imm.showSoftInput(searchMenuText, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void hideKeyboard() {
        // Now also reached from a location fix (the locate button goes through handleSingleTap),
        // which can arrive after the reader has left; with no context there is no keyboard to hide.
        if (getContext() == null || searchMenuText == null) {
            return;
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(getContext(), InputMethodManager.class);
        if (imm != null) {
            imm.hideSoftInputFromWindow(searchMenuText.getWindowToken(), 0);
        }
        searchMenuText.clearFocus();
    }

    private void doSearchResults() {
        String typed = searchMenuText.getText().toString();
        String searchText = CoordinateSearch.cleanQuery(typed);

        // Coordinates, in any of the common printed forms, are offered as the one result while
        // typing - not jumped to on every keystroke, since "46.02, 7" on the way to "46.02, 7.74"
        // already reads as coordinates.
        double[] coordinates = CoordinateSearch.parseCoordinates(typed);
        if (coordinates != null) {
            String label = String.format(Locale.ROOT, "%.5f, %.5f", coordinates[0], coordinates[1]);
            List<LuceneGeonameSearch.GeonameResult> point = new ArrayList<>();
            point.add(new LuceneGeonameSearch.GeonameResult(
                    label, label, (float) coordinates[0], (float) coordinates[1], -1));
            addGeoNameResponses(point);
            return;
        }

        if (searchText.isEmpty()) {
            // clear previous results
            addGeoNameResponses(new ArrayList<>());
        } else {
            ((NativeScreenCallerAndroid)getNativeScreenCaller()).runOnUiThread(() -> {
                // The index is built after start-up, and this fires on every keystroke - so
                // typing into the search box before it is ready called searchGeoName on a
                // null and killed the app (seen on a device: NullPointerException ...
                // LuceneGeonameSearch.searchGeoName ... SearchMenu.doSearchResults). No
                // results is the honest answer while it loads, and the next keystroke asks
                // again, so nothing is lost by waiting.
                LuceneGeonameSearch search = getC().luceneGeonameSearch;
                if (search == null) {
                    addGeoNameResponses(new ArrayList<>());
                    return;
                }
                List<LuceneGeonameSearch.GeonameResult> searchResults = search.searchGeoName(searchText);
                addGeoNameResponses(searchResults);
            });
        }
    }

    private void createMapButtons() {
        buttonGoTo = view.findViewById(R.id.button_search_go_to);
        buttonGoTo.setText(s("Go_To"));
        buttonMapBack = view.findViewById(R.id.button_search_map_back);
        buttonMapBack.setText(s("Back"));
        buttonGoToCurrent = view.findViewById(R.id.button_search_go_to_current_location);

        buttonGoTo.setOnClickListener(view -> {
            double lat1 = pointGoTo.getLatitude();
            double lon1 = pointGoTo.getLongitude();
            if (checkMissingElevationForCoord(lat1, lon1)) {
                getNativeScreenCaller().askForDownloadScreen(lat1, lon1);
                ((NativeScreenCallerAndroid)getNativeScreenCaller()).popStack();
            } else {
                getC().submitExecutorGeneric(
                        () -> getC().L.setCurrentTargetCoords(lat1, lon1));
                ((NativeScreenCallerAndroid)getNativeScreenCaller()).popStack();
            }
        });
        buttonMapBack.setOnClickListener(view -> ((NativeScreenCallerAndroid)getNativeScreenCaller()).popStack());
        buttonGoToCurrent.setOnClickListener(view -> ((NativeScreenCallerAndroid)getNativeScreenCaller())
                .getCurrentLocationListener(getActivity())
                .getCurrentLocation(
                        (longitude, latitude) -> {
                            GeoPoint p = new GeoPoint(latitude, longitude);
                            iMapController.setCenter(p);
                            handleSingleTap(p);
                        }
                ));

    }

    private MapView map = null;
    private Marker startMarker;
    private Marker endMarker;

    private void createMapEntities() {

        float lat = getC().L.getTargetLatitude();
        float lon = getC().L.getTargetLongitude();

        pointGoTo = new GeoPoint(lat, lon);

        OnlineTileSourceBase tileSrc = new OnlineTileSourceBase("NASA_GIBS", 1, 19, 256, ".jpeg", new String[]{}) {
            @Override
            public String getTileURLString(long pMapTileIndex) {
                // https://gitc.earthdata.nasa.gov/wmts/epsg3857/best/Landsat_WELD_CorrectedReflectance_TrueColor_Global_Annual/default/default//GoogleMapsCompatible_Level12/{z}/{y}/{x}.jpeg
                StringBuilder url = new StringBuilder("https://gitc.earthdata.nasa.gov/wmts/epsg3857/best/Landsat_WELD_CorrectedReflectance_TrueColor_Global_Annual/default/default//GoogleMapsCompatible_Level12/");
                url.append(MapTileIndex.getZoom(pMapTileIndex));
                url.append("/");
                url.append(MapTileIndex.getY(pMapTileIndex));
                url.append("/");
                url.append(MapTileIndex.getX(pMapTileIndex));
                url.append(".jpeg");
                String res = url.toString();
                return res;
            }
        };

        map = view.findViewById(R.id.osmdroid_map);
        map.setTileSource(tileSrc);

        map.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT);
        map.setMaxZoomLevel(13.5);
        map.setMultiTouchControls(true);

        startMarker = new Marker(map);
        endMarker = new Marker(map);

        startMarker.setPosition(new GeoPoint(lat, lon));
        startMarker.setInfoWindow(null);
        startMarker.setTextIcon(s("Current_position"));
        startMarker.setIcon(getResources().getDrawable(org.osmdroid.library.R.drawable.person));
        startMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        map.getOverlays().add(startMarker);

        endMarker.setVisible(false);
        // endMarker.setIcon();
        endMarker.setInfoWindow(null);
        map.getOverlays().add(endMarker);

        Configuration.getInstance().setUserAgentValue("PeakNav-ua");

        iMapController = map.getController();
        iMapController.setZoom(9.5);
        GeoPoint geoPoint = new GeoPoint(lat, lon);
        iMapController.setCenter(geoPoint);

        MapEventsReceiver eventsReceiver = new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                handleSingleTap(p);
                return true;
            }

            @Override
            public boolean longPressHelper(GeoPoint p) {
                return false;
            }
        };
        MapEventsOverlay eventsOverlay = new MapEventsOverlay(getContext(), eventsReceiver);
        map.getOverlays().add(eventsOverlay);

    }

    private void addGeoNameResponses(List<LuceneGeonameSearch.GeonameResult> features) {
        this.geonameResults.clear();
        this.geonameResults.addAll(features);

        if (features.size() == 0) {
            // TODO: add "no results" message
        }

        arrayAdapter.clear();

        for (LuceneGeonameSearch.GeonameResult feature : features) {

            arrayAdapter.add(feature.getFullName());
        }
        arrayAdapter.notifyDataSetChanged();
    }

    private void addNominatimResponses() {

        ((NativeScreenCallerAndroid)getNativeScreenCaller()).runOnUiThread(() -> {

            if (nominatimResponses.size() == 0) {
                // TODO: add "no results" message
            }

            arrayAdapter.clear();

            for (NominatimResponse nominatimResponse : nominatimResponses) {
                arrayAdapter.add(nominatimResponse.displayName);
            }
            arrayAdapter.notifyDataSetChanged();
        });
    }

    private void destinationChosen(NominatimResponse nominatimResponse) {
        GeoPoint p = new GeoPoint(nominatimResponse.lat, nominatimResponse.lon);
        destinationChosen(p);
    }

    private void destinationChosen(LuceneGeonameSearch.GeonameResult feature) {
        GeoPoint p = new GeoPoint(feature.lat, feature.lon);
        destinationChosen(p);
    }

    private void destinationChosen(GeoPoint p) {
        endMarker.setPosition(p);
        endMarker.setVisible(true);
        endMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        pointGoTo.setCoords(p.getLatitude(), p.getLongitude());
        map.getController().animateTo(p);

        arrayAdapter.clear();
        arrayAdapter.notifyDataSetChanged();

        // MapViewerScreen mapViewerScreen = MapViewerAndroidSingleton.getViewerInstance();
        // getC().L.setCurrentTargetCoords(nominatimResponse.lat, nominatimResponse.lon);
        // mapViewerScreen.mapApp.resume();
        // finish();
    }

    private void handleSingleTap(GeoPoint p) {
        // A point picked on the map is an answer, as a result picked from the list is: the
        // keyboard has nothing more to do, and it covers half the map the point is on.
        hideKeyboard();
        GeoPoint point = new GeoPoint(p.getLatitude(), p.getLongitude());

        endMarker.setPosition(point);
        endMarker.setVisible(true);
        endMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        pointGoTo.setCoords(p.getLatitude(), p.getLongitude());

        map.getController().animateTo(point);
    }

}
