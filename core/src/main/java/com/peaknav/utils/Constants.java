package com.peaknav.utils;

import com.badlogic.gdx.graphics.Color;

public interface Constants {

    interface PREFERENCES {
        String PREF_NAME = "PeakNav.prefs";

        String FIRST_TIME_APP_RUN = "firstTimeAppRun";

        String VIEWER_SHOW_PEAKS = "viewerShowPeaks";
        String VIEWER_SHOW_PLACE_NAMES = "viewerShowPlaceNames";
        String VIEWER_SHOW_ALPINE_HUTS = "viewerShowAlpineHuts";
        String VIEWER_SHOW_PISTES = "viewerShowPistes";
        String VIEWER_LARGE_FONTS = "viewerLargeFonts";
        String VIEWER_LAYER_VISIBLE_UNDERLAY_LAYER = "viewerLayerVisibleUnderlayLayer";
        String VIEWER_LAYER_VISIBLE_BASE_ROADS = "viewerLayerVisibleBaseRoads";
        String UNDERLAY_IMAGE_PROVIDER = "underlayImageProviderChosen";
        String VIEWER_UNIT_SYSTEM = "viewerUnitSystem";

        String COLLECT_ANONYMOUS_STATS = "collectAnonStats";
        String COLLECT_ANONYMOUS_STATS_QUERIED = "collectAnonStats";
        String COLLECT_DOWNLOAD_INFO = "collectDownloadInfo";
        // String COLLECT_ANONYMOUS_STATS_PROMPTED = "collectAnonStatsPrompted";
        String LOCATION_PERMISSION_DENIED = "locationPermissionDenied";
        String LAST_LATITUDE = "lastLatitude";
        String LAST_LONGITUDE = "lastLongitude";

        String COORDINATES_FIRST_TIME = "coordinatesFirstTime";

        String LAST_CAMERA_DIRECTION_X = "lastCameraDirectionX";
        String LAST_CAMERA_DIRECTION_Y = "lastCameraDirectionY";
        String LAST_CAMERA_DIRECTION_Z = "lastCameraDirectionZ";
        
        String LAST_CAMERA_UP_X = "lastCameraUpX";
        String LAST_CAMERA_UP_Y = "lastCameraUpY";
        String LAST_CAMERA_UP_Z = "lastCameraUpZ";

    }

    float peakNavGrey = 0.5f;
    Color peakNavGreyColor = new Color(peakNavGrey, peakNavGrey, peakNavGrey, 1.0f);
}
