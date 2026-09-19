package com.peaknav.views;

import static com.peaknav.compatibility.PeakNavAppState.getAppState;
import static com.peaknav.database.MissingDataDownloader.getBoundingBoxOfTargetTiles;
import static com.peaknav.utils.PeakNavPermissions.checkLocationPermission;
import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.getNativeScreenCaller;
import static com.peaknav.utils.PeakNavUtils.s;
import static com.peaknav.utils.PreferencesManager.P;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.peaknav.geo.BoundingBox;
import com.peaknav.geo.Tile;
import org.osmdroid.api.IMapController;
import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polygon;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import com.peaknav.compatibility.NativeScreenCallerAndroid;
import com.peaknav.database.MapSqliteAndroid;
import com.peaknav.database.MissingDataDownloader;
import com.peaknav.network.PeakNavDownloadManager;
import com.peaknav.pbf.PbfLayer;
import com.peaknav.ui.CurrentLocationCallback;
import com.peaknav.R;

public class MapDataDownloadChooser extends Fragment {

    private static final double ZOOM_LEVEL_WORLD = 3.5;
    private static final double ZOOM_LEVEL_CLOSE = 9.5;
    private final double lat;
    private final double lon;
    private final boolean goToAfterDownload;
    private boolean wizard;
    Intent intent;
    MapView map = null;
    private MissingDataDownloader missingDataDownloader;
    private ProgressDialog progressDialog;
    private Marker mapMarker;
    private Polygon polygon;
    private Button buttonDownloadSelectedArea;
    private final Executor executorDownload = Executors.newSingleThreadExecutor();
    private IMapController iMapController;
    private GeoPoint geoPoint;
    private boolean firstTimeLocation = true;

    private final CurrentLocationCallback callback = new CurrentLocationCallback() {
        @Override
        public void setCurrentLocation(float longitude, float latitude) {
            if (screenIsGone()) {
                return;
            }
            geoPoint.setLatitude(latitude);
            geoPoint.setLongitude(longitude);
            iMapController.animateTo(geoPoint);
            progressDialog.dismiss();
            if (geoPoint.getLatitude() != 0 && geoPoint.getLongitude() != 0
                    && wizard && firstTimeLocation) {
                setDownloadRefPoint(geoPoint, ZOOM_LEVEL_CLOSE);
                firstTimeLocation = false;
            }
        }
    };
    private View view;

    public MapDataDownloadChooser(double lat, double lon, boolean goToAfterDownload, boolean wizard) {
        this.lat = lat;
        this.lon = lon;
        this.goToAfterDownload = goToAfterDownload;
        this.wizard = wizard;
    }

    public static List<GeoPoint> getGeoPointsFromBoundaryBox(BoundingBox bb) {
        List<GeoPoint> points = new ArrayList<>(4);
        points.add(new GeoPoint(bb.minLatitude, bb.minLongitude));
        points.add(new GeoPoint(bb.minLatitude, bb.maxLongitude));
        points.add(new GeoPoint(bb.maxLatitude, bb.maxLongitude));
        points.add(new GeoPoint(bb.maxLatitude, bb.minLongitude));
        return points;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {

        view = inflater.inflate(R.layout.map_download_choose, container, false);

        TextView selectAreaToDownload = view.findViewById(R.id.select_area_to_download);
        selectAreaToDownload.setText(s("select_area_to_download"));

        if (P == null) {
            finish();
        }

        map = view.findViewById(R.id.mapd);
        map.setTileSource(TileSourceFactory.USGS_TOPO);
        map.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT);
        map.setMaxZoomLevel(8.9);
        map.setMultiTouchControls(true);

        checkShowDisplayStatsDialog();

        Configuration.getInstance().setUserAgentValue("PeakNav-ua");

        iMapController = map.getController();
        if (wizard) {
            iMapController.setZoom(ZOOM_LEVEL_WORLD);
        } else {
            iMapController.setZoom(ZOOM_LEVEL_CLOSE);
        }
        geoPoint = new GeoPoint(lat, lon);
        iMapController.setCenter(geoPoint);

        progressDialog = new ProgressDialog(getContext());
        progressDialog.setMessage(s("Determining_GPS_position"));

        if (P.getCoordinatesFirstTime()) {
            map.setVisibility(View.VISIBLE);
            progressDialog.show();
            wizard = true;
        } else {
            iMapController.setCenter(geoPoint);
            map.setVisibility(View.VISIBLE);
            progressDialog.dismiss();
        }

        mapMarker = new Marker(map, getContext());
        mapMarker.setInfoWindow(null);
        mapMarker.setPosition(geoPoint);
        map.getOverlays().add(mapMarker);

        polygon = new Polygon(map);
        polygon.setInfoWindow(null);

        missingDataDownloader = getC().missingDataDownloader;
        missingDataDownloader.setCoords(lat, lon);

        buttonDownloadSelectedArea = view.findViewById(R.id.download_selected_area);
        buttonDownloadSelectedArea.setText(s("download_selected_area"));

        if (wizard) {
            setMapLocationToGPSreading();
        }

        updatePolygonAroundPoint(polygon, geoPoint);

        polygon.getOutlinePaint().setStrokeWidth(1);
        polygon.getOutlinePaint().setColor(0x77AA0000);
        polygon.getFillPaint().setColor(0x44770000);

        map.getOverlays().add(polygon);

        getC().submitExecutorGeneric(() -> {
            fillSquaresForPbfLayer(PbfLayer.PBF_POI, 0x4400AA00);
            fillSquaresForPbfLayer(PbfLayer.PBF_HIGHWAYS, 0x440000AA);
            fillSquaresForPbfLayer("elev", 0x22AA0000);
        });

        buttonDownloadSelectedArea.setVisibility(View.VISIBLE);
        buttonDownloadSelectedArea.setOnClickListener(v -> {

            if (!checkShowDisplayStatsDialog())
                return;

            final double latitude = geoPoint.getLatitude();
            final double longitude = geoPoint.getLongitude();

            if (P.getCoordinatesFirstTime()) {
                // if this was the first time, go to the chosen location:
                // (otherwise app will stay on null island)
                getC().L.setCurrentTargetCoords(latitude, longitude, false);
            }

            // PeakNavHuggingFaceDownloader pd = MapViewerSingleton.getAppInstance().peakNavHfDownloader;

            executorDownload.execute(() -> {

                getAppState().setMapDataDownloadStarted(true);
                 //   ((NativeScreenCallerAndroid) getNativeScreenCaller()).runOnUiThread(() -> Toast.makeText(getActivity(), s("Download_started"), Toast.LENGTH_LONG).show());

                missingDataDownloader.setCoords(
                        latitude,
                        longitude
                );
                missingDataDownloader.doDownload(goToAfterDownload);

                // ((NativeScreenCallerAndroid) getNativeScreenCaller()).runOnUiThread(() -> Toast.makeText(getActivity(), s("Download_complete"), Toast.LENGTH_LONG).show());
                getAppState().setMapDataDownloadStarted(false);
                getAppState().setMapDataDownloaded(true);
            });

            ((NativeScreenCallerAndroid)getNativeScreenCaller()).popStack();
        });

        Button buttonBack = view.findViewById(R.id.cancel_download_area);
        buttonBack.setText(s("Back"));
        if (wizard) {
            buttonBack.setVisibility(View.INVISIBLE);
        }
        buttonBack.setOnClickListener(v -> ((NativeScreenCallerAndroid) getNativeScreenCaller()).popStack());

        ImageView buttonGoToCurrent = view.findViewById(R.id.button_downloader_go_to_current_location);
        buttonGoToCurrent.setOnClickListener(view -> {
            AndroidLauncher androidLauncher = (AndroidLauncher) getActivity();
            androidLauncher.locationPermissionCallbacks.add(
                    () -> ((NativeScreenCallerAndroid) getNativeScreenCaller())
                            .getCurrentLocationListener(getActivity())
                            .getCurrentLocation(callback));
            checkLocationPermission(androidLauncher);
            ((NativeScreenCallerAndroid) getNativeScreenCaller())
                    .getCurrentLocationListener(getActivity())
                    .getCurrentLocation(
                            (longitude, latitude) -> {
                                if (screenIsGone()) {
                                    return;
                                }
                                GeoPoint p = new GeoPoint(latitude, longitude);
                                iMapController.setCenter(p);
                                if (p.getLatitude() != 0 && p.getLongitude() != 0 && wizard && firstTimeLocation) {
                                    setDownloadRefPoint(geoPoint, ZOOM_LEVEL_CLOSE);
                                    firstTimeLocation = false;
                                }
                            }
                    );
        });

        MapEventsReceiver eventsReceiver = new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                setDownloadRefPoint(p, null);
                return true;
            }

            @Override
            public boolean longPressHelper(GeoPoint p) {
                return false;
            }
        };
        MapEventsOverlay eventsOverlay = new MapEventsOverlay(getContext(), eventsReceiver);
        map.getOverlays().add(eventsOverlay);

        return view;

    }

    private void finish() {
        ((NativeScreenCallerAndroid)getNativeScreenCaller()).popStack();
    }

    private void fillSquaresForPbfLayer(PbfLayer pbfLayer, int color) {
        fillSquaresForPbfLayer(pbfLayer.name(), color);
    }

    private void fillSquaresForPbfLayer(String pbfLayer, int color) {
        MapSqliteAndroid mapSqlite = (MapSqliteAndroid) getC().mapSqlite;
        List<Tile> downloadedTiles = mapSqlite.getListOfDownloadedTiles(pbfLayer);
        for (Tile downloadedTile : downloadedTiles) {
            Polygon polygon1 = new Polygon(map);
            polygon1.getOutlinePaint().setStrokeWidth(0);
            polygon1.getFillPaint().setColor(color);
            List<GeoPoint> pts = getGeoPointsFromBoundaryBox(downloadedTile.getBoundingBox());
            polygon1.setPoints(pts);
            polygon1.setInfoWindow(null);
            map.getOverlays().add(0, polygon1);
        }
    }

    private boolean checkShowDisplayStatsDialog() {
        if (P.isCollectDownloadInfo()) {
            return true;
        }
        AlertDialog.Builder alertBuilder = new AlertDialog.Builder(getContext());
        alertBuilder.setMessage(s("Missing_download_info_consent"))
                .setPositiveButton(s("Yes"), (dialogInterface, i) -> getC().executorGeneric.submit(()->P.setCollectDownloadInfo(true)))
                .setNegativeButton(s("No"), (dialogInterface, i) -> {})
                .setCancelable(false)
                .show();
        return false;
    }

    private void setMapLocationToGPSreading() {
        AndroidLauncher androidLauncher = (AndroidLauncher) getActivity();

        androidLauncher.locationPermissionCallbacks.add(
                () -> ((NativeScreenCallerAndroid) getNativeScreenCaller())
                        .getCurrentLocationListener(getActivity())
                        .getCurrentLocation(callback));

        checkLocationPermission(androidLauncher);
        ((NativeScreenCallerAndroid)getNativeScreenCaller())
                .getCurrentLocationListener(getActivity())
                .getCurrentLocation(callback);
    }

    /**
     * Whether this screen has been taken apart, and a caller arriving late should let go.
     *
     * <p>A location fix can arrive long after the reader has left. One is asked of the network
     * and of GPS both, and a GPS lock takes tens of seconds - time enough to start the download,
     * or to give up on the wizard and walk away. Nothing cancels the request, so it comes back
     * to a fragment whose map the framework has already detached: osmdroid empties its overlays
     * when the view leaves the window, and the polygon, still a perfectly good reference, no
     * longer has an outline to move. Answering at all is what crashed the app, on the main
     * thread, where a location fix is delivered. There is nothing left to point at.
     */
    private boolean screenIsGone() {
        return !isAdded() || map == null;
    }

    private void setDownloadRefPoint(GeoPoint p, Double zoomLevel) {
        if (mapMarker == null || polygon == null || map == null ||
                buttonDownloadSelectedArea == null || missingDataDownloader == null) {
            // This happens in case the window has been destroyed...
            return;
        }
        geoPoint.setCoords(p.getLatitude(), p.getLongitude());
        mapMarker.setPosition(p);
        updatePolygonAroundPoint(polygon, p);
        if (zoomLevel != null)
            iMapController.setZoom(zoomLevel);
        iMapController.animateTo(p);
        buttonDownloadSelectedArea.setVisibility(View.VISIBLE);
    }

    private void updatePolygonAroundPoint(Polygon polygon, GeoPoint startingPoint) {
        PeakNavDownloadManager pndm = missingDataDownloader.getPeakNavDownloadManager();
        List<Tile> tiles = pndm.getQueueMapData(
                startingPoint.getLatitude(), startingPoint.getLongitude(),
                pndm.getZoomPoi(), pndm.getRangePoi()
                // TODO: this will show the highways box instead:
                // pndm.getZoomHighways(), pndm.getRangeHighways()
        );
        // fillPolygonAroundPoint(ZOOM_LEVEL_HIGHWAYS, startingPoint, missingDataDownloader.getFilterHighways().tiles);
        List<GeoPoint> points = fillPolygonAroundPoint(pndm.getZoomHighways(), startingPoint, tiles);
        polygon.setPoints(points);
    }

    private List<GeoPoint> fillPolygonAroundPoint(byte zoomLevel, GeoPoint geoPoint, List<Tile> targetTiles) {
        BoundingBox bb = getBoundingBoxOfTargetTiles(targetTiles);
        return getGeoPointsFromBoundaryBox(bb);

    }

    /**
     * Lets go of everything belonging to the map, which the framework has just detached.
     *
     * <p>An overlay that has been detached keeps its reference and loses its contents, so a
     * caller arriving late - a location fix, most of all - cannot tell a live polygon from a
     * dead one by asking whether it is null. Dropping them here is what makes the checks in
     * {@link #setDownloadRefPoint} mean what they say.
     */
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        map = null;
        polygon = null;
        mapMarker = null;
        iMapController = null;
        buttonDownloadSelectedArea = null;
        view = null;
    }

    public void onResume() {
        super.onResume();
        if (map == null) {
            return;
        }
        map.onResume();
    }

    public void onPause() {
        super.onPause();
        if (map == null) {
            return;
        }
        map.onPause();
    }

}
