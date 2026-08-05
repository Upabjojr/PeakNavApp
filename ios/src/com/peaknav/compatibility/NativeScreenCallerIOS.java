package com.peaknav.compatibility;

import static com.peaknav.compatibility.PeakNavAppState.getAppState;
import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.s;
import static com.peaknav.utils.PreferencesManager.P;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;

import com.peaknav.database.LuceneGeonameSearch;
import com.peaknav.database.MissingDataDownloader;
import com.peaknav.gesture.OrientationPointerListener;
import com.peaknav.network.NominatimResponse;
import com.peaknav.ui.ClickCallback;
import com.peaknav.ui.CurrentLocationCallback;
import com.peaknav.ui.CurrentLocationListener;
import com.peaknav.ui.TextFieldsCallback;
import com.peaknav.utils.UtilsOSIOS;

import org.robovm.apple.foundation.NSArray;
import org.robovm.apple.foundation.NSData;
import org.robovm.apple.foundation.NSObject;
import org.robovm.apple.foundation.NSProcessInfo;
import org.robovm.apple.foundation.NSURL;
import org.robovm.apple.uikit.UIActivityViewController;
import org.robovm.apple.uikit.UIAlertAction;
import org.robovm.apple.uikit.UIAlertActionStyle;
import org.robovm.apple.uikit.UIAlertController;
import org.robovm.apple.uikit.UIAlertControllerStyle;
import org.robovm.apple.uikit.UIApplication;
import org.robovm.apple.uikit.UIImage;
import org.robovm.apple.uikit.UITextField;
import org.robovm.apple.uikit.UIViewController;
import org.robovm.apple.uikit.UIWindow;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * The iOS side of everything the app asks the platform for: alerts, sharing, the browser,
 * permissions.
 *
 * <p>Where Android hands work to an Activity and the desktop opens a Swing window, iOS
 * presents a view controller on the key window - the same window libGDX is drawing into.
 * That is the whole pattern here, and the reason {@link #present} exists.
 *
 * <p><b>Threading.</b> UIKit may only be touched on the main thread, and every one of
 * these methods is called from libGDX's render thread. {@link #onMainThread} is where that
 * hop happens; forget it and the app dies at some unrelated moment later, which is the
 * worst kind of bug to be handed.
 *
 * <p>Several screens are not built yet and say so plainly rather than doing nothing: an
 * app that silently ignores a tap is indistinguishable from a broken one. See the class
 * comment on {@code IOSLoadFactory} for what remains before this target runs at all.
 */
public class NativeScreenCallerIOS extends NativeScreenCaller {

    /** iOS has no toast; a brief alert is the nearest thing, and this is how long it stays. */
    private static final double TOAST_SECONDS = 1.6;

    /** Dismisses the toast alerts. Daemon, so it can never hold the app open. */
    private static final java.util.Timer DISMISS_TIMER = new java.util.Timer("ios-toast", true);

    // ------------------------------------------------------------------ plumbing

    /**
     * Runs work on the UI thread. On iOS that is the main thread, and it is also the one
     * libGDX renders on, so the two are the same thread and this is a direct call - but it
     * is called through this method everywhere, so the day that stops being true there is
     * one place to change.
     */
    @Override
    public void getCallOnUIThread(Runnable runnable) {
        onMainThread(runnable);
    }

    private void onMainThread(final Runnable work) {
        // libGDX's iOS backend runs its render loop on the main thread, so posting through
        // it puts the work exactly where UIKit needs it, without a second dispatch queue.
        if (Gdx.app != null) {
            Gdx.app.postRunnable(work);
        } else {
            work.run();
        }
    }

    /** The controller everything is presented from: the one filling the app's window. */
    private UIViewController rootController() {
        UIApplication application = UIApplication.getSharedApplication();
        if (application == null) {
            return null;
        }
        UIWindow window = application.getKeyWindow();
        return window == null ? null : window.getRootViewController();
    }

    private void present(final UIViewController controller) {
        onMainThread(() -> {
            UIViewController root = rootController();
            if (root != null) {
                root.presentViewController(controller, true, null);
            }
        });
    }

    /** An alert with a single dismissing button. */
    private void alert(final String title, final String message, final String buttonText) {
        onMainThread(() -> {
            UIAlertController controller = new UIAlertController(
                    title == null ? "" : title, message == null ? "" : message,
                    UIAlertControllerStyle.Alert);
            controller.addAction(new UIAlertAction(buttonText, UIAlertActionStyle.Default,
                    (UIAlertAction action) -> { }));
            present(controller);
        });
    }

    // ------------------------------------------------------------------ messages

    @Override
    public void alertMessage(String message) {
        alert("PeakNav", message, "OK");
    }

    @Override
    public void comingSoon() {
        alert("PeakNav", "Not in this version yet.", "OK");
    }

    /**
     * The nearest thing iOS has to a toast: an alert with no buttons, dismissed on a timer.
     * Deliberately brief - it is used for distances and elevations while the user is
     * dragging the view, and anything that had to be tapped away would be an obstacle.
     */
    @Override
    public void makeToast(final String message) {
        onMainThread(() -> {
            final UIAlertController controller = new UIAlertController(
                    null, message, UIAlertControllerStyle.Alert);
            UIViewController root = rootController();
            if (root == null) {
                return;
            }
            root.presentViewController(controller, true, null);
            // Dismissed on a plain timer that hands the work back to the main thread. The
            // Objective-C way would be performSelector:withObject:afterDelay:, but a
            // scheduled Runnable posted through libGDX lands on the same thread with none
            // of the selector plumbing, and is far easier to see the correctness of.
            DISMISS_TIMER.schedule(new java.util.TimerTask() {
                @Override
                public void run() {
                    onMainThread(() -> controller.dismissViewController(true, null));
                }
            }, (long) (TOAST_SECONDS * 1000));
        });
    }

    @Override
    public void warnCannotReadImageLocation() {
        alert("No location in that photo",
                "The picture carries no usable coordinates, so there is nowhere to go to.",
                "OK");
    }

    @Override
    public void promptGoToImageLocation(final double lat, final double lon) {
        onMainThread(() -> {
            UIAlertController controller = new UIAlertController(
                    "Go to the photo's location?",
                    String.format("%.5f, %.5f", lat, lon), UIAlertControllerStyle.Alert);
            controller.addAction(new UIAlertAction("Go", UIAlertActionStyle.Default,
                    (UIAlertAction action) -> Gdx.app.postRunnable(
                            () -> com.peaknav.utils.PeakNavUtils.getC().L
                                    .setCurrentTargetCoords(lat, lon, false))));
            controller.addAction(new UIAlertAction("Stay", UIAlertActionStyle.Cancel,
                    (UIAlertAction action) -> { }));
            present(controller);
        });
    }

    /**
     * Asks for a set of values in one alert. iOS alerts take text fields directly, so this
     * is the platform's own dialogue rather than anything hand-built.
     */
    @Override
    public void promptForTextFields(final String title, final String message,
                                    final String[] labels, final String[] initialValues,
                                    final TextFieldsCallback callback) {
        onMainThread(() -> {
            UIAlertController controller = new UIAlertController(
                    title, message, UIAlertControllerStyle.Alert);
            final List<UITextField> fields = new ArrayList<>();
            for (int i = 0; i < labels.length; i++) {
                final String placeholder = labels[i];
                final String initial =
                        (initialValues != null && i < initialValues.length) ? initialValues[i] : null;
                controller.addTextField((UITextField field) -> {
                    field.setPlaceholder(placeholder);
                    if (initial != null) {
                        field.setText(initial);
                    }
                    fields.add(field);
                });
            }
            controller.addAction(new UIAlertAction("OK", UIAlertActionStyle.Default,
                    (UIAlertAction action) -> {
                        String[] values = new String[fields.size()];
                        for (int i = 0; i < values.length; i++) {
                            values[i] = fields.get(i).getText();
                        }
                        callback.onEntered(values);
                    }));
            controller.addAction(new UIAlertAction("Cancel", UIAlertActionStyle.Cancel,
                    (UIAlertAction action) -> callback.onCancelled()));
            present(controller);
        });
    }

    // ------------------------------------------------------------------ sharing

    /**
     * The system share sheet, with the frame as a UIImage - so a snapshot can go to
     * Photos, Messages, or anywhere else the user has.
     */
    @Override
    public void shareSnapshot(final Pixmap pixmap) {
        // Encoded on the calling thread: it is megabytes of work and the main thread is
        // also the render thread here, so doing it there would stall the picture.
        final byte[] png = new UtilsOSIOS().encodePng(pixmap);
        onMainThread(() -> {
            UIImage image = new UIImage(new NSData(png));
            NSArray<NSObject> items = new NSArray<>(image);
            UIActivityViewController sheet = new UIActivityViewController(items, null);
            present(sheet);
        });
    }

    @Override
    public void openCoordinate(double latitude, double longitude) {
        // Apple Maps, by the documented URL scheme: this is a "show me where this is"
        // request, and the platform's own map is the least surprising answer to it.
        openUrl(String.format("https://maps.apple.com/?ll=%f,%f&q=%f,%f",
                latitude, longitude, latitude, longitude));
    }

    @Override
    public void openAppInfoScreen() {
        openUrl("https://peaknav.com");
    }

    private void openUrl(final String url) {
        onMainThread(() -> {
            UIApplication application = UIApplication.getSharedApplication();
            if (application != null) {
                application.openURL(new NSURL(url));
            }
        });
    }

    // ------------------------------------------------------------------ device

    /**
     * Physical memory, which the app uses to decide how much it may cache.
     *
     * <p>Not the same question as "how much may this app use" - iOS gives a single app a
     * fraction of it and kills anything greedier - but it is the number the other platforms
     * report, and the caches are sized as a fraction of it.
     */
    @Override
    public long getTotalMemory() {
        return NSProcessInfo.getSharedProcessInfo().getPhysicalMemory();
    }

    @Override
    public void ensureLocationPermissions() {
        // Deliberately empty until the location listener below is real: asking for a
        // permission the app then makes no use of is how an app gets distrusted.
    }

    private final OrientationPointerListener orientationPointerListener =
            new OrientationPointerListener() {
                // Compass and attitude would come from CoreMotion. Until that is built the
                // app behaves as it does on the desktop, where there is no such sensor: the
                // view is steered by hand and nothing here has anything to start or stop.
                @Override
                public void start() {
                }

                @Override
                public void stop() {
                }
            };

    @Override
    public OrientationPointerListener getOrientationPointerListener() {
        return orientationPointerListener;
    }

    private final CurrentLocationListener currentLocationListener =
            new CurrentLocationListener() {
                @Override
                public void getCurrentLocation(CurrentLocationCallback callback) {
                    // CoreLocation, once the permission flow above is built. Until then the
                    // app behaves as it does on the desktop: no GPS, the user picks a place.
                }
            };

    @Override
    public CurrentLocationListener getCurrentLocationListener() {
        return currentLocationListener;
    }

    // ------------------------------------------------------------------ not yet built

    // ------------------------------------------------------------------ downloading data

    /**
     * Fetches the elevation and map data around a point.
     *
     * <p>No chooser screen, despite the name: Android opens a region picker here, and this
     * does what the desktop does instead - download for the point it was handed. The work is
     * all {@code core}'s; what a platform has to get right is doing it off the render thread
     * and clearing the started flag afterwards.
     */
    @Override
    public void openMapDataDownloadChooser(double lat, double lon, boolean goToAfterDownload) {
        getC().submitExecutorGeneric(() -> downloadAround(lat, lon, goToAfterDownload));
    }

    /**
     * The download itself, on whatever thread the caller is already on.
     *
     * <p>Separate from {@link #openMapDataDownloadChooser} so the consent prompt can set the
     * preference and download in one task rather than submitting two and hoping they run in
     * order - a download that starts before the consent lands fetches nothing at all, silently
     * (see {@code PeakNavDownloadManager}, which skips every request without it).
     */
    private void downloadAround(double lat, double lon, boolean goToAfterDownload) {
        MissingDataDownloader missingDataDownloader = getC().missingDataDownloader;
        missingDataDownloader.setCoords(lat, lon);
        // The started flag suppresses the missing-data prompt while a download runs
        // (CurrentLocation.shouldAskToDownloadMissingData). It MUST be cleared on every exit
        // path: left set, the prompt never appears again for the whole session.
        getAppState().setMapDataDownloadStarted(true);
        try {
            missingDataDownloader.doDownload(goToAfterDownload);
        } finally {
            getAppState().setMapDataDownloadStarted(false);
        }
        getAppState().setMapDataDownloaded(true);
    }

    /**
     * The intro screen's download button.
     *
     * <p>The button sets the download consent before calling this, so the workers really
     * fetch. With no location chosen yet there is nothing sensible to download - the intro is
     * still let through, and the missing-data prompt takes over once the user picks a place.
     */
    @Override
    public void openMapDataDownloadChooserWizard() {
        getC().submitExecutorGeneric(() -> {
            if (!getC().L.isCurrentLocationNotSet()) {
                downloadAround(getC().L.getTargetLatitude(), getC().L.getTargetLongitude(), false);
            }
            getAppState().setMapDataDownloaded(true);
        });
    }

    /**
     * The prompt shown when the camera arrives somewhere with no data.
     *
     * <p>Reached from inside the render loop, so everything here is posted rather than run:
     * presenting a view controller mid-frame is exactly the kind of thing that works in
     * testing and deadlocks on a device.
     */
    @Override
    public void askForDownloadScreen(final double lat, final double lon) {
        onMainThread(() -> {
            UIAlertController controller = new UIAlertController(
                    s("Missing_data_prompt"), null, UIAlertControllerStyle.Alert);
            controller.addAction(new UIAlertAction(s("Yes"), UIAlertActionStyle.Default,
                    (UIAlertAction action) -> askConsentThenDownload(lat, lon)));
            controller.addAction(new UIAlertAction(s("No"), UIAlertActionStyle.Cancel,
                    (UIAlertAction action) -> Gdx.app.postRunnable(
                            // Back where we were, WITHOUT re-running the missing-data check:
                            // doing that here pops this very prompt straight back up when the
                            // old spot has no data either.
                            () -> getC().L.setCurrentTargetCoords(
                                    getC().L.getCurrentLatitude(),
                                    getC().L.getCurrentLongitude(),
                                    false))));
            present(controller);
        });
    }

    /**
     * Asks for the download consent if it has not been given, then downloads.
     *
     * <p>Without this the download runs and fetches nothing: every request in
     * {@code PeakNavDownloadManager} is skipped unless {@code P.isCollectDownloadInfo()}, so a
     * user who reached the missing-data prompt without passing the intro button would get a
     * progress bar and no data. Android asks here too.
     */
    private void askConsentThenDownload(final double lat, final double lon) {
        if (P.isCollectDownloadInfo()) {
            openMapDataDownloadChooser(lat, lon, false);
            return;
        }
        onMainThread(() -> {
            UIAlertController consent = new UIAlertController(
                    s("Missing_data_download"), s("Missing_download_info_consent"),
                    UIAlertControllerStyle.Alert);
            consent.addAction(new UIAlertAction(s("Yes"), UIAlertActionStyle.Default,
                    (UIAlertAction action) -> getC().submitExecutorGeneric(() -> {
                        P.setCollectDownloadInfo(true);
                        downloadAround(lat, lon, false);
                    })));
            // No consent, no download - and no silent pretend-download either.
            consent.addAction(new UIAlertAction(s("No"), UIAlertActionStyle.Cancel,
                    (UIAlertAction action) -> { }));
            present(consent);
        });
    }

    // ------------------------------------------------------------------ search

    /**
     * "lat, lon" typed into the search box. Mirrors the pattern in {@code OnlineSearch}, which
     * navigates on a match and never calls the results listener - so this has to recognise the
     * same input, or the results dialogue below would sit waiting for a callback that is never
     * coming.
     */
    private static final Pattern COORDINATE_TEXT =
            Pattern.compile("\\s*(-?\\d+\\.?\\d*)\\s*,\\s*(-?\\d+\\.?\\d*)\\s*");

    /** Enough results to choose from; an alert with forty actions is not a list. */
    private static final int MAX_SEARCH_RESULTS = 10;

    /** How long to wait for Nominatim before showing whatever the offline index found. */
    private static final long ONLINE_SEARCH_TIMEOUT_MS = 8000;

    /**
     * Search, as an alert asking for text and a second alert offering what was found.
     *
     * <p>Not the scrolling result screen the desktop and Android build - this is the platform's
     * own dialogue, which needs no view controller of its own and is honest about being a
     * first implementation. Both sources the other platforms use are queried: the offline
     * geonames index (empty here unless {@code assets/geonames_index.362} was built) and
     * Nominatim. Typing coordinates goes straight there.
     *
     * <p>{@code callback} is unused, as on the desktop: picking a result sets the target
     * location, which is what every caller passing null wants.
     */
    @Override
    public void openScreenSearchLocation(ClickCallback callback) {
        promptForTextFields(s("Search_place_title"), s("Search_prompt"),
                new String[]{s("Search")}, new String[]{""},
                new TextFieldsCallback() {
                    @Override
                    public void onEntered(String[] values) {
                        if (values.length > 0 && values[0] != null && !values[0].trim().isEmpty()) {
                            runSearch(values[0].trim());
                        }
                    }

                    @Override
                    public void onCancelled() {
                    }
                });
    }

    private void runSearch(final String query) {
        if (COORDINATE_TEXT.matcher(query).matches()) {
            // OnlineSearch navigates for this itself, on the render thread where target
            // mutation belongs. Nothing to choose from, so no results dialogue - but the
            // listener is a no-op rather than null: if this pattern and OnlineSearch's ever
            // drift apart, the text falls through to Nominatim, and null would be an NPE on
            // the network thread instead of simply finding nothing.
            Gdx.app.postRunnable(() -> getC().onlineSearch.parseDestinationText(
                    query, (ArrayList<NominatimResponse> ignored) -> { }));
            return;
        }

        final List<LuceneGeonameSearch.GeonameResult> found = new ArrayList<>();
        LuceneGeonameSearch offline = getC().luceneGeonameSearch;
        if (offline != null) {
            // Safe with no index: searchGeoName returns empty rather than throwing when the
            // searcher never loaded, which is the normal state until the index is built.
            found.addAll(offline.searchGeoName(query));
        }

        // Presented once, by whichever arrives first - the response or the timeout. Without
        // the guard a slow-then-arriving response would stack a second dialogue on the first.
        final AtomicBoolean presented = new AtomicBoolean(false);

        getC().onlineSearch.parseDestinationText(query, (ArrayList<NominatimResponse> responses) -> {
            if (responses != null) {
                for (NominatimResponse response : responses) {
                    found.add(new LuceneGeonameSearch.GeonameResult(
                            response.displayName, response.displayName,
                            response.lat, response.lon, -1));
                }
            }
            if (presented.compareAndSet(false, true)) {
                showSearchResults(found);
            }
        });

        // OnlineSearch.failed() does not call the listener, so a network error would otherwise
        // leave the user staring at nothing. Show what the offline index gave instead.
        DISMISS_TIMER.schedule(new java.util.TimerTask() {
            @Override
            public void run() {
                if (presented.compareAndSet(false, true)) {
                    showSearchResults(found);
                }
            }
        }, ONLINE_SEARCH_TIMEOUT_MS);
    }

    private void showSearchResults(final List<LuceneGeonameSearch.GeonameResult> results) {
        onMainThread(() -> {
            if (results.isEmpty()) {
                alert(s("Search_place_title"), s("Search_results_hint"), s("OK"));
                return;
            }
            UIAlertController controller = new UIAlertController(
                    s("Search_place_title"), s("Search_results_hint"),
                    UIAlertControllerStyle.Alert);
            int shown = Math.min(results.size(), MAX_SEARCH_RESULTS);
            for (int i = 0; i < shown; i++) {
                final LuceneGeonameSearch.GeonameResult result = results.get(i);
                controller.addAction(new UIAlertAction(result.getFullName(),
                        UIAlertActionStyle.Default,
                        // Target mutation belongs on the render thread, not on whichever
                        // thread UIKit called this action back on.
                        (UIAlertAction action) -> Gdx.app.postRunnable(
                                () -> getC().L.setCurrentTargetCoords(result.lat, result.lon))));
            }
            controller.addAction(new UIAlertAction(s("Cancel"), UIAlertActionStyle.Cancel,
                    (UIAlertAction action) -> { }));
            present(controller);
        });
    }

    @Override
    public void openCameraPictureView() {
        notBuiltYet("Taking a photo to place behind the view");
    }

    @Override
    public void openGalleryPick() {
        notBuiltYet("Choosing a photo to place behind the view");
    }

    @Override
    public void openAppTutorial() {
        notBuiltYet("The tutorial");
    }

    /**
     * Says what is missing, out loud. The alternative - an empty method - makes a tapped
     * button look broken, and leaves whoever picks this up next to discover the gap by
     * reading the source.
     */
    private void notBuiltYet(String what) {
        alert("Not yet on iOS", what + " is not built on this platform yet.", "OK");
    }
}
