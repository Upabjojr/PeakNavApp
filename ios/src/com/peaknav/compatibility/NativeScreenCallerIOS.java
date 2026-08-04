package com.peaknav.compatibility;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;

import com.peaknav.gesture.OrientationPointerListener;
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

    @Override
    public void openMapDataDownloadChooser(double lat, double lon, boolean goToAfterDownload) {
        notBuiltYet("Choosing map data to download");
    }

    @Override
    public void openMapDataDownloadChooserWizard() {
        notBuiltYet("The first-run download wizard");
    }

    @Override
    public void openScreenSearchLocation(ClickCallback callback) {
        notBuiltYet("Search");
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

    @Override
    public void askForDownloadScreen(double lat, double lon) {
        notBuiltYet("Downloading data for this area");
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
