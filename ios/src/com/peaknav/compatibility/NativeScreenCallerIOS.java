package com.peaknav.compatibility;

import static com.peaknav.compatibility.PeakNavAppState.getAppState;
import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.s;
import static com.peaknav.utils.PreferencesManager.P;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.Base64Coder;

import com.peaknav.controller.LocationControllerIOS;
import com.peaknav.controller.OrientationPointerControllerIOS;
import com.peaknav.database.LuceneGeonameSearch;
import com.peaknav.database.MissingDataDownloader;
import com.peaknav.gesture.OrientationPointerListener;
import com.peaknav.network.NominatimResponse;
import com.peaknav.ui.ClickCallback;
import com.peaknav.ui.CurrentLocationCallback;
import com.peaknav.ui.CurrentLocationListener;
import com.peaknav.ui.TextFieldsCallback;
import com.peaknav.utils.UtilsOSIOS;

import org.robovm.apple.coregraphics.CGRect;
import org.robovm.apple.corelocation.CLLocation;
import org.robovm.apple.corelocation.CLLocationCoordinate2D;
import org.robovm.apple.foundation.NSArray;
import org.robovm.apple.foundation.NSData;
import org.robovm.apple.foundation.NSObject;
import org.robovm.apple.foundation.NSProcessInfo;
import org.robovm.apple.foundation.NSString;
import org.robovm.apple.foundation.NSURL;
import org.robovm.apple.photos.PHAsset;
import org.robovm.apple.photos.PHAuthorizationStatus;
import org.robovm.apple.photos.PHPhotoLibrary;
import org.robovm.apple.uikit.UIActivityViewController;
import org.robovm.apple.uikit.UIAlertAction;
import org.robovm.apple.uikit.UIAlertActionStyle;
import org.robovm.apple.uikit.UIAlertController;
import org.robovm.apple.uikit.UIAlertControllerStyle;
import org.robovm.apple.uikit.UIApplication;
import org.robovm.apple.uikit.UIApplicationOpenURLOptions;
import org.robovm.apple.uikit.UIBarButtonItem;
import org.robovm.apple.uikit.UIBarButtonItemStyle;
import org.robovm.apple.uikit.UIDocumentPickerDelegateAdapter;
import org.robovm.apple.uikit.UIDocumentPickerMode;
import org.robovm.apple.uikit.UIDocumentPickerViewController;
import org.robovm.apple.uikit.UIImage;
import org.robovm.apple.uikit.UIImagePickerController;
import org.robovm.apple.uikit.UIImagePickerControllerDelegateAdapter;
import org.robovm.apple.uikit.UIImagePickerControllerEditingInfo;
import org.robovm.apple.uikit.UIImagePickerControllerSourceType;
import org.robovm.apple.uikit.UINavigationController;
import org.robovm.apple.uikit.UIPopoverArrowDirection;
import org.robovm.apple.uikit.UIPopoverPresentationController;
import org.robovm.apple.uikit.UIScreen;
import org.robovm.apple.uikit.UITextField;
import org.robovm.apple.uikit.UIViewController;
import org.robovm.apple.uikit.UIWindow;
import org.robovm.apple.webkit.WKWebView;

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
 * <p>Every screen the shared UI can ask for is built here: GPS, the gyroscope camera,
 * the image pickers, the GPX picker, the tutorial and the app-info page. See the class
 * comment on {@code IOSLoadFactory} for the one thing core treats as absent rather than
 * broken - there is no mapsforge graphics backend, so there is no road and path layer.
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

    /** How long {@link #present} keeps waiting for an in-flight alert transition to end. */
    private static final int PRESENT_RETRY_MAX = 27;
    private static final long PRESENT_RETRY_MS = 150L;

    private void present(final UIViewController controller) {
        presentWhenIdle(controller, 0);
    }

    /**
     * Presents from the topmost controller, once no transition is in flight.
     *
     * <p>The old version called {@code root.presentViewController} unconditionally, and UIKit
     * drops that on the floor - a console warning, no dialog - in two situations this app
     * actually produces. First, chained dialogs: tapping Yes on "go to this photo's location?"
     * navigates, the arrival raises the missing-data prompt two frames later, and at that
     * point the first alert is still animating out - root is mid-dismissal, so the prompt
     * never appeared (the defect this fixes; a debug-hook test missed it because the hook
     * navigated with no alert on screen). Second, presenting from root while root already
     * presents something else - an info screen, the photo picker - is refused outright;
     * UIKit wants the presentation to come from the top of the stack.
     *
     * <p>So: walk to the topmost presented controller; a live toast up there is dismissed
     * rather than presented upon (its auto-dismissal would take any child down with it);
     * while the top is mid-transition, retry on a short timer; then present from the top.
     * The retry gives up after {@link #PRESENT_RETRY_MAX} attempts and presents anyway,
     * which is at worst the old behaviour.
     */
    private void presentWhenIdle(final UIViewController controller, final int attempt) {
        onMainThread(() -> {
            UIViewController root = rootController();
            if (root == null) {
                return;
            }
            UIViewController host = root;
            while (host.getPresentedViewController() != null) {
                host = host.getPresentedViewController();
            }
            if (attempt < PRESENT_RETRY_MAX) {
                if (host == activeToast) {
                    // A toast is informational and about to vanish anyway; a dialog wins.
                    // Dismiss it and come back, rather than presenting on top of a
                    // controller whose scheduled dismissal would drag the dialog down.
                    activeToast = null;
                    host.dismissViewController(true, null);
                    scheduleRetry(controller, attempt);
                    return;
                }
                if (host.isBeingDismissed() || host.isBeingPresented()) {
                    scheduleRetry(controller, attempt);
                    return;
                }
            }
            // iPad requires a source anchor for anything that presents as a popover -
            // UIActivityViewController (the share sheet) and the photo-library picker.
            // Present one without it and iPad throws NSInvalidArgumentException, which on
            // this app reads as the Share button crashing the whole app. On iPhone these
            // controllers are full-screen and getPopoverPresentationController() is null,
            // so this block is a no-op there. Anchored to the centre of the root view with
            // no arrow: the app has no button frame to point at from here, and centre is
            // the least surprising place for a sheet with no origin.
            UIPopoverPresentationController popover = controller.getPopoverPresentationController();
            if (popover != null && root.getView() != null) {
                CGRect bounds = root.getView().getBounds();
                popover.setSourceView(root.getView());
                popover.setSourceRect(new CGRect(
                        bounds.getWidth() / 2, bounds.getHeight() / 2, 0, 0));
                popover.setPermittedArrowDirections(UIPopoverArrowDirection.None);
            }
            host.presentViewController(controller, true, null);
        });
    }

    private void scheduleRetry(final UIViewController controller, final int attempt) {
        DISMISS_TIMER.schedule(new java.util.TimerTask() {
            @Override
            public void run() {
                presentWhenIdle(controller, attempt + 1);
            }
        }, PRESENT_RETRY_MS);
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
        alert("PeakNav", message, s("OK"));
    }

    @Override
    public void comingSoon() {
        // The localized string, as Android's comingSoon shows - not hardcoded English.
        alert("PeakNav", s("Coming_soon"), s("OK"));
    }

    /**
     * The nearest thing iOS has to a toast: an alert with no buttons, dismissed on a timer.
     * Deliberately brief - it is used for distances and elevations while the user is
     * dragging the view, and anything that had to be tapped away would be an obstacle.
     */
    /**
     * The toast currently on screen, if any. {@link #presentWhenIdle} dismisses it rather
     * than presenting a dialog on top of it - the toast's scheduled dismissal below would
     * otherwise take the dialog down with it.
     */
    private volatile UIViewController activeToast;

    @Override
    public void makeToast(final String message) {
        onMainThread(() -> {
            final UIAlertController controller = new UIAlertController(
                    null, message, UIAlertControllerStyle.Alert);
            UIViewController root = rootController();
            if (root == null || root.getPresentedViewController() != null) {
                // Something real is up (a dialog, a screen - or another toast): a toast is
                // too unimportant to queue behind it, and presenting from busy root was a
                // silent no-op anyway. Skipping keeps that behaviour, now deliberate.
                return;
            }
            activeToast = controller;
            root.presentViewController(controller, true, null);
            // Dismissed on a plain timer that hands the work back to the main thread. The
            // Objective-C way would be performSelector:withObject:afterDelay:, but a
            // scheduled Runnable posted through libGDX lands on the same thread with none
            // of the selector plumbing, and is far easier to see the correctness of.
            DISMISS_TIMER.schedule(new java.util.TimerTask() {
                @Override
                public void run() {
                    onMainThread(() -> {
                        if (activeToast == controller) {
                            activeToast = null;
                            controller.dismissViewController(true, null);
                        }
                        // else presentWhenIdle already dismissed it to make room.
                    });
                }
            }, (long) (TOAST_SECONDS * 1000));
        });
    }

    @Override
    public void warnCannotReadImageLocation() {
        alert(s("Image_location_missing_title"), s("Image_location_missing"), s("OK"));
    }

    @Override
    public void promptGoToImageLocation(final double lat, final double lon) {
        onMainThread(() -> {
            UIAlertController controller = new UIAlertController(
                    s("Image_location_found"),
                    s("Go_to_image_location_prompt"), UIAlertControllerStyle.Alert);
            controller.addAction(new UIAlertAction(s("Yes"), UIAlertActionStyle.Default,
                    (UIAlertAction action) -> Gdx.app.postRunnable(
                            // checkMissing = true, matching Android's 2-arg call: travelling to a
                            // photo's location is a real destination like any other, so an area with
                            // no downloaded data must raise the missing-data download prompt. The
                            // false here meant it silently arrived at bare ground and offered nothing.
                            () -> com.peaknav.utils.PeakNavUtils.getC().L
                                    .setCurrentTargetCoords(lat, lon, true))));
            controller.addAction(new UIAlertAction(s("No"), UIAlertActionStyle.Cancel,
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
        // Locale.ENGLISH is not optional: on a device set to a comma-decimal language,
        // bare %f writes ll=46,185894,10,640152 - four fields where Maps expects two -
        // and the button looks simply dead. The Android side formats the same way.
        openUrl(String.format(java.util.Locale.ENGLISH, "https://maps.apple.com/?ll=%f,%f&q=%f,%f",
                latitude, longitude, latitude, longitude));
    }

    /**
     * The licence, privacy statement and third-party notices, in the app - the same
     * bundled info/app_info.html every platform shows. This must not shell out to a
     * website: the whole point of the screen is that the legal text is readable exactly
     * where the app is, offline included.
     */
    @Override
    public void openAppInfoScreen() {
        onMainThread(() -> {
            String html = Gdx.files.internal("info/app_info.html").readString();
            // The page carries no viewport meta (Android's WebView does not need one);
            // WKWebView without it lays the page out at desktop width and the text
            // arrives microscopic.
            html = html.replace("<head>",
                    "<head>\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
            presentHtml(html);
        });
    }

    /** A bundled HTML page, full screen, with a Back button to come home on. */
    private void presentHtml(final String html) {
        onMainThread(() -> {
            WKWebView webView = new WKWebView(UIScreen.getMainScreen().getBounds());
            webView.loadHTMLString(html, null);
            UIViewController content = new UIViewController();
            content.setView(webView);
            final UINavigationController nav = new UINavigationController(content);
            content.getNavigationItem().setRightBarButtonItem(new UIBarButtonItem(
                    s("Back"), UIBarButtonItemStyle.Done,
                    item -> nav.dismissViewController(true, null)));
            present(nav);
        });
    }

    private void openUrl(final String url) {
        onMainThread(() -> {
            try {
                UIApplication application = UIApplication.getSharedApplication();
                if (application == null) {
                    return;
                }
                // The three-argument form, not the one-argument openURL: - that one has
                // been deprecated since iOS 10, and this call must keep working on iOS
                // versions newer than the binding. The completion logs the outcome
                // because a refused open is otherwise indistinguishable from a dead
                // button, which is the worst kind of report to receive.
                application.openURL(new NSURL(url), new UIApplicationOpenURLOptions(),
                        opened -> System.err.println("[openUrl] " + url + " -> " + opened));
            } catch (Throwable failed) {
                // The render loop swallows exceptions, so say it here or nowhere.
                System.err.println("[openUrl] threw for " + url);
                failed.printStackTrace();
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

    // Constructed lazily on the main thread: CLLocationManager's delegate callbacks arrive
    // on the run loop of the creating thread, and only the main one is guaranteed to spin.
    private LocationControllerIOS locationController;

    private LocationControllerIOS locationController() {
        if (locationController == null) {
            locationController = new LocationControllerIOS();
        }
        return locationController;
    }

    @Override
    public void ensureLocationPermissions() {
        onMainThread(() -> {
            LocationControllerIOS controller = locationController();
            if (controller.isDenied()) {
                // The user said no earlier; only the Settings app can change that answer
                // now, so point there rather than silently doing nothing forever.
                askOpenSettings();
                return;
            }
            controller.ensureAuthorization();
        });
    }

    /** The "location is off for this app" dialog: explain, and offer the Settings page. */
    private void askOpenSettings() {
        UIAlertController controller = new UIAlertController(
                s("Location_permission_missing"),
                s("Location_permissions_in_device_settings_are_advised_to_use_app"),
                UIAlertControllerStyle.Alert);
        controller.addAction(new UIAlertAction(s("Cancel"), UIAlertActionStyle.Cancel,
                (UIAlertAction action) -> { }));
        controller.addAction(new UIAlertAction(s("Open_settings"), UIAlertActionStyle.Default,
                (UIAlertAction action) -> openUrl(UIApplication.getOpenSettingsURLString())));
        present(controller);
    }

    private final OrientationPointerListener orientationPointerListener =
            new OrientationPointerListener() {
                // Created on first use, then kept: CMMotionManager instances are meant to be
                // long-lived, and start/stop toggles updates on the one instance.
                private OrientationPointerControllerIOS controller;

                @Override
                public void start() {
                    onMainThread(() -> {
                        if (controller == null) {
                            controller = new OrientationPointerControllerIOS();
                        }
                        controller.start();
                    });
                }

                @Override
                public void stop() {
                    onMainThread(() -> {
                        if (controller != null) {
                            controller.stop();
                        }
                    });
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
                    onMainThread(() -> locationController().getCurrentLocation(callback));
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
     * fetch. On a first launch nothing is chosen yet, and this is where Android prompts for
     * GPS (its region-picker wizard asks the moment it opens) - so iOS asks here too, and
     * with no picker to show, the fix itself chooses the download region. On denial or no
     * fix the behaviour is exactly the old one: the intro is let through, and the search
     * dialog / missing-data prompt take over once the user picks a place by hand.
     */
    @Override
    public void openMapDataDownloadChooserWizard() {
        if (getC().L.isCurrentLocationNotSet()) {
            // The fix can arrive twice - the cached position first, the fresh one after -
            // and both may aim the camera, but only one download should start.
            final AtomicBoolean downloadStarted = new AtomicBoolean(false);
            ensureLocationPermissions();
            onMainThread(() -> locationController().getCurrentLocation(
                    (longitude, latitude) -> {
                        // Delivered on the render thread (LocationControllerIOS posts), so
                        // the camera move is safe to make directly.
                        getC().L.setCurrentTargetCoordsFromGPS(latitude, longitude);
                        if (downloadStarted.compareAndSet(false, true)) {
                            getC().submitExecutorGeneric(
                                    () -> downloadAround(latitude, longitude, false));
                        }
                    }));
            // Let the intro through now rather than after the fix: the permission answer
            // may never come, and the app must not hang on it.
            getAppState().setMapDataDownloaded(true);
            return;
        }
        getC().submitExecutorGeneric(() -> {
            downloadAround(getC().L.getTargetLatitude(), getC().L.getTargetLongitude(), false);
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
        onMainThread(() -> {
            if (!UIImagePickerController.isSourceTypeAvailable(
                    UIImagePickerControllerSourceType.Camera)) {
                // The simulator, in practice. Every real target device has a camera.
                return;
            }
            presentImagePicker(UIImagePickerControllerSourceType.Camera);
        });
    }

    @Override
    public void openGalleryPick() {
        onMainThread(() -> {
            // The library permission is asked before the picker, not for the picking - the
            // picker runs out of process and works unauthorized - but for the geotag: iOS
            // strips GPS EXIF from the file the picker hands over, so the coordinates only
            // exist on the PHAsset, and the PHAsset is only present once reading is allowed.
            // This is Android's ACCESS_MEDIA_LOCATION request before its picker, replayed.
            if (PHPhotoLibrary.getAuthorizationStatus() == PHAuthorizationStatus.NotDetermined) {
                PHPhotoLibrary.requestAuthorization(status -> onMainThread(
                        () -> presentImagePicker(UIImagePickerControllerSourceType.PhotoLibrary)));
                return;
            }
            presentImagePicker(UIImagePickerControllerSourceType.PhotoLibrary);
        });
    }

    /**
     * The slideshow tutorial - the same bundled page as Android, with the same trick: the
     * screenshots are substituted into the page as base64 data URLs, because a page loaded
     * from a string has no base directory to resolve relative image paths against.
     */
    @Override
    public void openAppTutorial() {
        onMainThread(() -> {
            String html = Gdx.files.internal("info/app_tutorial.html").readString();
            StringBuilder getImage = new StringBuilder("function get_image(k) {\n");
            String[] imgFiles = {
                    "imageBase.jpg", "imageOptions.jpg", "imageOptionsSat.jpg", "imageBaseSat.jpg"};
            for (String imgFile : imgFiles) {
                byte[] imgBytes = Gdx.files.internal("info/" + imgFile).readBytes();
                getImage.append("if (k == '").append(imgFile)
                        .append("') data = 'data:image/jpeg;base64,")
                        .append(new String(Base64Coder.encode(imgBytes)))
                        .append("';\n");
            }
            getImage.append("\nlet img = new Image();\nimg.src = data;\nreturn img;\n}\n");
            presentHtml(html.replace("// OVERLOAD::get_image", getImage.toString()));
        });
    }

    // ------------------------------------------------------------------ GPX

    /** The presented GPX picker, held strongly until it reports - see the delegate. */
    private UIDocumentPickerViewController gpxPicker;

    // Strongly held for the same reason as the image picker's delegate below.
    private final UIDocumentPickerDelegateAdapter gpxPickerDelegate =
            new UIDocumentPickerDelegateAdapter() {

                @Override
                public void didPickDocuments(UIDocumentPickerViewController controller,
                                             NSArray<NSURL> urls) {
                    gpxPicker = null;
                    if (urls != null && !urls.isEmpty()) {
                        loadGpxFrom(urls.first());
                    }
                }

                // The pre-iOS-11 single-document form of the same callback.
                @Override
                public void didPickDocument(UIDocumentPickerViewController controller, NSURL url) {
                    gpxPicker = null;
                    if (url != null) {
                        loadGpxFrom(url);
                    }
                }

                @Override
                public void wasCancelled(UIDocumentPickerViewController controller) {
                    gpxPicker = null;
                }
            };

    /**
     * The options menu's "Load GPX file". Import mode copies the chosen file into the
     * app's own tmp inbox, so no security-scoped bookkeeping survives past the callback.
     * The types are deliberately broad - GPX has no single agreed identifier, and the
     * Android picker offers every file for the same reason.
     */
    @Override
    public void pickGpxFile() {
        onMainThread(() -> {
            UIDocumentPickerViewController picker = new UIDocumentPickerViewController(
                    java.util.Arrays.asList("public.xml", "public.data"),
                    UIDocumentPickerMode.Import);
            picker.setDelegate(gpxPickerDelegate);
            gpxPicker = picker;
            present(picker);
        });
    }

    private void loadGpxFrom(NSURL url) {
        byte[] bytes = readFile(url.getPath());
        if (bytes == null) {
            return;
        }
        final String xml = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        // loadFromXml toasts and moves the camera, so it belongs on the render thread.
        Gdx.app.postRunnable(() -> getC().gpxManager.loadFromXml(xml));
    }

    // ------------------------------------------------------------------ picking images

    /** The presented picker, held strongly until dismissed - see the delegate's comment. */
    private UIImagePickerController imagePicker;

    // Both the picker and this adapter must stay reachable from Java fields while the
    // picker is up: the Objective-C back-references are weak, invisible to RoboVM's
    // collector, and a collected delegate ends the flow with no callback and no error.
    private final UIImagePickerControllerDelegateAdapter imagePickerDelegate =
            new UIImagePickerControllerDelegateAdapter() {

                @Override
                public void didFinishPickingMedia(UIImagePickerController picker,
                                                  UIImagePickerControllerEditingInfo info) {
                    boolean fromCamera = picker.getSourceType()
                            == UIImagePickerControllerSourceType.Camera;
                    final byte[] bytes = pickedImageBytes(info);
                    final CLLocation assetLocation = pickedAssetLocation(info);
                    picker.dismissViewController(true, null);
                    imagePicker = null;
                    if (bytes == null) {
                        return;
                    }
                    // Decoding a full-size photo is too much work for the render thread.
                    getC().submitExecutorGeneric(() -> {
                        com.peaknav.utils.PeakNavUtils.setBytesAsBackgroundImage(bytes);
                        if (fromCamera) {
                            // A photo taken just now was taken right here - Android's
                            // camera view does not prompt to travel either.
                            return;
                        }
                        if (assetLocation != null) {
                            CLLocationCoordinate2D coordinate = assetLocation.getCoordinate();
                            promptGoToImageLocation(
                                    coordinate.getLatitude(), coordinate.getLongitude());
                        } else {
                            // No PHAsset (library access refused, or a very old iOS): the
                            // shared EXIF reader still catches files whose GPS survived,
                            // and warns properly when nothing does.
                            com.peaknav.utils.PeakNavUtils.checkImageGpsAndPrompt(bytes);
                        }
                    });
                }

                @Override
                public void didCancel(UIImagePickerController picker) {
                    picker.dismissViewController(true, null);
                    imagePicker = null;
                }
            };

    private void presentImagePicker(UIImagePickerControllerSourceType sourceType) {
        UIImagePickerController picker = new UIImagePickerController();
        picker.setSourceType(sourceType);
        picker.setDelegate(imagePickerDelegate);
        imagePicker = picker;
        present(picker);
    }

    /**
     * The picked image as undecoded bytes - what core's decoder and EXIF reader both want.
     *
     * <p>The original file is preferred: its bytes still carry the EXIF orientation tag,
     * which core applies itself because libGDX's decoder ignores it. The re-encoded
     * fallback covers camera captures, which have no file behind them.
     */
    private byte[] pickedImageBytes(UIImagePickerControllerEditingInfo info) {
        NSObject url = info.get(new NSString("UIImagePickerControllerImageURL"));
        if (url instanceof NSURL && ((NSURL) url).isFileURL()) {
            byte[] bytes = readFile(((NSURL) url).getPath());
            if (bytes != null) {
                return bytes;
            }
        }
        UIImage image = info.getOriginalImage();
        if (image == null) {
            return null;
        }
        NSData jpeg = image.toJPEGData(0.92);
        return jpeg == null ? null : jpeg.getBytes();
    }

    /** Where the photo was taken, from the library's own record - null when unknown. */
    private CLLocation pickedAssetLocation(UIImagePickerControllerEditingInfo info) {
        NSObject asset = info.get(new NSString("UIImagePickerControllerPHAsset"));
        if (asset instanceof PHAsset) {
            return ((PHAsset) asset).getLocation();
        }
        return null;
    }

    private byte[] readFile(String path) {
        // Byte-shuffling loop rather than Files.readAllBytes: java.nio.file does not
        // exist on RoboVM's runtime (see AGENTS.md).
        try (java.io.FileInputStream in = new java.io.FileInputStream(path)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (java.io.IOException failed) {
            return null;
        }
    }

}
