package com.peaknav.controller;

import com.badlogic.gdx.Gdx;
import com.peaknav.ui.CurrentLocationCallback;

import org.robovm.apple.corelocation.CLAuthorizationStatus;
import org.robovm.apple.corelocation.CLLocation;
import org.robovm.apple.corelocation.CLLocationCoordinate2D;
import org.robovm.apple.corelocation.CLLocationManager;
import org.robovm.apple.corelocation.CLLocationManagerDelegateAdapter;
import org.robovm.apple.foundation.NSArray;
import org.robovm.apple.foundation.NSError;

import java.util.ArrayList;
import java.util.List;

/**
 * One-shot GPS fixes through CoreLocation, shaped like the Android implementation: the
 * last-known position is delivered immediately for instant feedback, then a single fresh
 * fix follows and the request is done - this is "where am I now", not continuous tracking.
 *
 * <p>Authorization is asynchronous on iOS exactly as the runtime permission is on Android,
 * and the same trick answers it: callbacks that arrive before the user has decided wait in
 * {@link #waiting}, and the authorization-change delegate callback drains the queue the
 * moment the grant lands. A denial leaves the queue in place silently - the Android side
 * returns without an answer there too, and the "here" button simply stays un-lit.
 *
 * <p>Everything here must run on the main thread: the delegate's callbacks arrive on the
 * run loop of the thread the manager was created on, and on this backend the main thread is
 * also the GL thread, so the callbacks may reach core directly. {@code NativeScreenCallerIOS}
 * does the hop before calling in.
 */
public class LocationControllerIOS {

    private CLLocationManager manager;
    private final List<CurrentLocationCallback> waiting = new ArrayList<>();

    // The delegate must be reachable from a Java field for as long as the manager lives:
    // the manager's back-reference is a weak Objective-C pointer that RoboVM's collector
    // does not see, and a collected delegate is a silent end to every callback.
    private final CLLocationManagerDelegateAdapter delegate = new CLLocationManagerDelegateAdapter() {

        @Override
        public void didUpdateLocations(CLLocationManager m, NSArray<CLLocation> locations) {
            if (locations == null || locations.isEmpty()) {
                return;
            }
            deliver(locations.last(), true);
        }

        @Override
        public void didFail(CLLocationManager m, NSError error) {
            // requestLocation() reports exactly once, here on failure. The queue stays:
            // a later grant or a fresh tap of the button retries and drains it.
        }

        // iOS 14 calls the second form and the adapter implements both selectors, so the
        // pre-14 form never fires there unless routed by hand. Both funnel to one place.

        @Override
        public void didChangeAuthorizationStatus(CLLocationManager m, CLAuthorizationStatus status) {
            authorizationChanged();
        }

        @Override
        public void locationManagerDidChangeAuthorization(CLLocationManager m) {
            authorizationChanged();
        }
    };

    private void ensureManager() {
        if (manager == null) {
            manager = new CLLocationManager();
            manager.setDelegate(delegate);
        }
    }

    /** Prompt for the when-in-use permission if the user has not been asked yet. */
    public void ensureAuthorization() {
        ensureManager();
        if (CLLocationManager.getAuthorizationStatus() == CLAuthorizationStatus.NotDetermined) {
            manager.requestWhenInUseAuthorization();
        }
    }

    /** The user has answered the permission prompt before, and the answer was no. */
    public boolean isDenied() {
        CLAuthorizationStatus status = CLLocationManager.getAuthorizationStatus();
        return status == CLAuthorizationStatus.Denied
                || status == CLAuthorizationStatus.Restricted;
    }

    public void getCurrentLocation(CurrentLocationCallback callback) {
        ensureManager();
        if (isDenied()) {
            return;
        }
        waiting.add(callback);
        if (CLLocationManager.getAuthorizationStatus() == CLAuthorizationStatus.NotDetermined) {
            // The queued callback is answered from authorizationChanged() if the user says
            // yes. ensureAuthorization() has normally prompted already; asking again here
            // is a no-op when the prompt is on screen.
            manager.requestWhenInUseAuthorization();
            return;
        }
        requestFix();
    }

    private void authorizationChanged() {
        if (!waiting.isEmpty() && !isDenied()
                && CLLocationManager.getAuthorizationStatus() != CLAuthorizationStatus.NotDetermined) {
            requestFix();
        }
    }

    private void requestFix() {
        // The cached fix first, exactly as Android hands over getLastKnownLocation: the
        // camera jumps somewhere close immediately, and the fresh fix corrects it after.
        CLLocation cached = manager.getLocation();
        if (cached != null) {
            deliver(cached, false);
        }
        manager.requestLocation();
    }

    private void deliver(CLLocation location, boolean fresh) {
        CLLocationCoordinate2D coordinate = location.getCoordinate();
        float longitude = (float) coordinate.getLongitude();
        float latitude = (float) coordinate.getLatitude();
        // Iterate over a copy: setCurrentLocation reaches core, and core may well ask for
        // the location again from inside the callback.
        List<CurrentLocationCallback> callbacks = new ArrayList<>(waiting);
        if (fresh) {
            waiting.clear();
        }
        for (CurrentLocationCallback callback : callbacks) {
            Runnable work = () -> callback.setCurrentLocation(longitude, latitude);
            if (Gdx.app != null) {
                Gdx.app.postRunnable(work);
            } else {
                work.run();
            }
        }
    }
}
