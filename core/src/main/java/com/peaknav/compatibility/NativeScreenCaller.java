package com.peaknav.compatibility;

import static com.peaknav.utils.PeakNavUtils.getC;

import com.badlogic.gdx.graphics.Pixmap;
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
