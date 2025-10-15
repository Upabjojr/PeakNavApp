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

            InputMethodManager imm = (InputMethodManager) getSystemService(getContext(), InputMethodManager.class);
            if (imm != null) {
                imm.hideSoftInputFromWindow(searchMenuText.getWindowToken(), 0);
            }
            searchMenuText.clearFocus();

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
            doSearchResults();
        });

        // addNominatimResponses();
        return view;
    }

    private void doSearchResults() {
        String searchText = searchMenuText.getText().toString().strip();

        if (searchText.isEmpty()) {
            // clear previous results
            addGeoNameResponses(new ArrayList<>());
        } else {
            ((NativeScreenCallerAndroid)getNativeScreenCaller()).runOnUiThread(() -> {
                List<LuceneGeonameSearch.GeonameResult> searchResults = getC().luceneGeonameSearch.searchGeoName(searchText);
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
        GeoPoint point = new GeoPoint(p.getLatitude(), p.getLongitude());

        endMarker.setPosition(point);
        endMarker.setVisible(true);
        endMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        pointGoTo.setCoords(p.getLatitude(), p.getLongitude());

        map.getController().animateTo(point);
    }

}
