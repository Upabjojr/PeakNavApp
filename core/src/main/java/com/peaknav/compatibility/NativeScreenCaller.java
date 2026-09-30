package com.peaknav.compatibility;

import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.s;
import static com.peaknav.utils.PreferencesManager.P;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.peaknav.network.IpLocation;
import com.peaknav.viewer.mapscreens.MapScreens;
import com.peaknav.ui.CurrentLocationCallback;
import com.peaknav.gesture.OrientationPointerListener;
import com.peaknav.ui.ClickCallback;
import com.peaknav.ui.CurrentLocationListener;
import com.peaknav.ui.TextFieldsCallback;

public abstract class NativeScreenCaller {

    public abstract void getCallOnUIThread(Runnable runnable);

    public void openMapDataDownloadChooser() {
        openMapDataDownloadChooser(
                getC().L.getTargetLatitude(),
                getC().L.getTargetLongitude(),
                false);
    }

    public abstract void openMapDataDownloadChooser(double lat, double lon, boolean goToAfterDownload);

    public abstract void openMapDataDownloadChooserWizard();

    // public abstract void openScreenMapLocationChoose(double lat, double lon, ClickCallback callback);

    public abstract void openScreenSearchLocation(ClickCallback callback);


    public abstract void openCameraPictureView();

    public abstract void openGalleryPick();

    /**
     * Open a native file picker for a {@code .gpx} file and hand its text to
     * {@code getC().gpxManager.loadFromXml(...)}. Platforms without a file picker leave this as a
     * no-op (the GPX-from-URL option still works everywhere).
     */
    public void pickGpxFile() {
    }

    /**
     * Ask the user whether to navigate to the coordinates a background image was
     * taken at (recovered from its EXIF GPS metadata).
     */
    public abstract void promptGoToImageLocation(double lat, double lon);

    /**
     * Tell the user the imported image carries no readable location, so the map cannot
     * jump to where it was taken. On Android this also happens when the app lacks the
     * ACCESS_MEDIA_LOCATION permission, which makes the system strip the GPS EXIF.
     */
    public abstract void warnCannotReadImageLocation();

    /**
     * A yes/no question in the platform's own dialog. {@code onYes} runs on whatever thread
     * the platform answers from - hop to the render thread before touching the map.
     */
    public abstract void promptYesNo(String title, String message, Runnable onYes);

    public abstract void openAppInfoScreen();

    public abstract OrientationPointerListener getOrientationPointerListener();

    /**
     * Whether this device can take a picture with a camera of its own, and whether it can
     * tell which way it is pointed. Both are true on a phone and false on a computer, which
     * has no camera the app can use and, save for a few convertibles, no motion sensors at
     * all - the buttons for them are left out of the interface there rather than sitting in
     * it doing nothing.
     */
    public boolean hasCamera() {
        return true;
    }

    /** @see #hasCamera() */
    public boolean hasOrientationSensors() {
        return true;
    }

    public abstract CurrentLocationListener getCurrentLocationListener();

    /**
     * Asks where the device is, asking for the permission first where that is needed. The
     * answer may come on any thread, more than once, or never.
     *
     * <p>For the map screens in {@code core} (search and the download chooser), which cannot
     * know that Android wants its location requests made on the UI thread and repeated once
     * the permission is granted, or that iOS wants them on the main thread. Those override it.
     */
    public void requestCurrentLocation(com.peaknav.ui.CurrentLocationCallback callback) {
        ensureLocationPermissions();
        CurrentLocationListener listener = getCurrentLocationListener();
        if (listener != null) {
            listener.getCurrentLocation(callback);
        }
    }

    public abstract void askForDownloadScreen(double lat, double lon);

    /**
     * Saves or shares a captured view. {@code info} says where and how it was taken, for
     * the picture's EXIF block ({@link com.peaknav.utils.ExifWriter}); may be null.
     */
    public abstract void shareSnapshot(Pixmap pixmap, com.peaknav.utils.SnapshotInfo info);

    /**
     * Hands a GPX track to the user: a save dialog on the desktop, the system share sheet on a
     * phone. For tracks that exist nowhere else on the device (see GpxManager.loadShareableXml).
     */
    public abstract void shareGpx(String fileName, String xml);

    public abstract void makeToast(String message);

    public abstract void ensureLocationPermissions();

    /**
     * The platform refused a location request because location access is off for the app.
     * Before any place has ever been chosen - the first run, when the download chooser asks
     * where the user is - the reader is offered the two other ways to a place: an estimate
     * from the internet connection, or the search box (see {@link #offerEstimateOrSearch}).
     * After that the refusal is left as it was: the reader searches or taps the map.
     *
     * @return whether the choice was offered, so the caller can skip its own "open the
     *         settings" prompt rather than put two dialogs up at once
     */
    public boolean locationPermissionDenied(CurrentLocationCallback callback) {
        if (!isFirstRun()) {
            return false;
        }
        offerEstimateOrSearch(callback, "Ip_location_choice");
        return true;
    }

    /**
     * Location access is granted, but no position came: location is switched off, or there
     * is no signal. On a first run the reader is offered the same choice as after a refusal,
     * rather than waiting forever for a fix; after that, a toast says so, and the reader
     * searches or taps the map.
     */
    public void locationUnavailable(CurrentLocationCallback callback) {
        if (isFirstRun()) {
            offerEstimateOrSearch(callback, "Ip_location_choice_no_fix");
        } else {
            makeToast(s("Position_not_found"));
        }
    }

    /**
     * No position from the device: the reader chooses between an estimate from the internet
     * connection and searching for the place. The estimate leaves the device's address with
     * an online service, so choosing it is the consent to that, remembered; once given, the
     * estimate is made without asking. Search - or an estimate that fails - puts the cursor in
     * the download chooser's search box ({@link MapScreens#offerSearch}).
     *
     * @param messageKey the explanation: phones say location access is off, computers that
     *                   they have no GPS
     */
    public void offerEstimateOrSearch(CurrentLocationCallback callback, String messageKey) {
        if (P.isIpLocationConsent()) {
            joinNetworkLookup(callback);
            return;
        }
        promptChoice(s("Ip_location_title"), s(messageKey),
                s("Ip_location_estimate"), s("Search"),
                () -> {
                    // Off the UI thread: writing a preference flushes it to disk.
                    getC().submitExecutorGeneric(() -> P.setIpLocationConsent(true));
                    joinNetworkLookup(callback);
                },
                MapScreens::offerSearch);
    }

    /**
     * Asks the reader to choose between two answers, each with its own button. {@code onSecond}
     * also runs when the dialog is dismissed without an answer, where the platform allows it:
     * the second answer should be the one that asks nothing more of the reader.
     */
    public abstract void promptChoice(String title, String message, String first, String second,
                                      Runnable onFirst, Runnable onSecond);

    /** No place chosen yet: a fresh install. */
    public static boolean isFirstRun() {
        return P.getCoordinatesFirstTime() && getC() != null && getC().L != null
                && getC().L.isCurrentLocationNotSet();
    }

    /** Callbacks waiting on the lookup under way; guarded by itself. */
    private final java.util.List<CurrentLocationCallback> networkEstimateWaiting = new java.util.ArrayList<>();

    /**
     * Estimates the position from the internet connection ({@link IpLocation}) and hands it
     * to {@code callback} on the render thread. The estimate leaves the device's address
     * with an online service, so the user is asked first, with the text under
     * {@code consentKey}, and a yes is remembered; and it is an estimate, so a toast names
     * the place it landed on rather than quietly moving the map somewhere odd.
     */
    public void estimateLocationFromNetwork(CurrentLocationCallback callback, String consentKey) {
        if (P.isIpLocationConsent()) {
            joinNetworkLookup(callback);
            return;
        }
        promptYesNo(s("Ip_location_title"), s(consentKey), () -> {
            // Off the UI thread: writing a preference flushes it to disk.
            getC().submitExecutorGeneric(() -> P.setIpLocationConsent(true));
            joinNetworkLookup(callback);
        });
    }

    /** Requests made while a lookup is under way share its answer rather than send another. */
    private void joinNetworkLookup(CurrentLocationCallback callback) {
        synchronized (networkEstimateWaiting) {
            networkEstimateWaiting.add(callback);
            if (networkEstimateWaiting.size() > 1) {
                return;
            }
        }
        lookUpNetworkLocation();
    }

    private void lookUpNetworkLocation() {
        makeToast(s("Ip_location_searching"));
        IpLocation.locate(new IpLocation.Listener() {
            @Override
            public void located(double latitude, double longitude, String placeName) {
                final java.util.List<CurrentLocationCallback> callbacks;
                synchronized (networkEstimateWaiting) {
                    callbacks = new java.util.ArrayList<>(networkEstimateWaiting);
                    networkEstimateWaiting.clear();
                }
                // The callbacks move the camera or a map screen, which belong on the render
                // thread; the HTTP answer arrives on a network one.
                Gdx.app.postRunnable(() -> {
                    for (CurrentLocationCallback c : callbacks) {
                        c.setCurrentLocation((float) longitude, (float) latitude);
                    }
                });
                String where = placeName.isEmpty()
                        ? String.format(java.util.Locale.ROOT, "%.3f, %.3f", latitude, longitude)
                        : placeName;
                makeToast(s("Ip_location_estimated") + " " + where);
            }

            @Override
            public void failed() {
                synchronized (networkEstimateWaiting) {
                    networkEstimateWaiting.clear();
                }
                makeToast(s("Ip_location_failed"));
                // On a first run, the search box is the way left to a place.
                MapScreens.offerSearch();
            }
        });
    }

    /**
     * Hands a coordinate to whatever else the device can open it with.
     *
     * <p>Deliberately vague about the destination, because the right answer differs by
     * platform: Android has a standard "here is a point" intent that any installed map app
     * can answer, so the choice belongs to the system and the person using it. A desktop has
     * no such notion, so it opens a page that lists the map services for that point.
     *
     * @param latitude  degrees north
     * @param longitude degrees east
     */
    public abstract void openCoordinate(double latitude, double longitude);

    public abstract void comingSoon();
    public abstract void alertMessage(String message);

    /**
     * Opens a native picker to freeze the sky at a chosen date/time (in the device's local zone), or
     * reset it to the live device clock. Concrete no-op default so platforms without one still build.
     */
    public void chooseSkyTime() { }

    /**
     * A map data download began (true) or the last one running ended (false). Where the system
     * stops an app it cannot see - Android freezes one in the background, or with the screen
     * locked, within seconds - this is where the platform asks to be let go on with it.
     * No-op by default: the desktop runs on whatever the window is doing.
     */
    public void setMapDataDownloadRunning(boolean running) { }

    /** How far the running download has got, 0 to 1, for whatever the platform shows of it. */
    public void setMapDataDownloadProgress(float ratio) { }

    /**
     * Asks the user to fill in one or more text fields in a native dialog.
     *
     * @param title         dialog title
     * @param message       optional explanatory text shown above the fields (null/empty to omit)
     * @param labels        one label per field, shown next to it
     * @param initialValues initial contents, same length as {@code labels} (entries may be null)
     * @param callback      receives the entered values, or a cancellation
     */
    public abstract void promptForTextFields(
            String title, String message, String[] labels, String[] initialValues, TextFieldsCallback callback);

    public abstract long getTotalMemory();

    // public abstract void setUpBillings();
}
