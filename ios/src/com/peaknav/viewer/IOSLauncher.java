package com.peaknav.viewer;

import org.robovm.apple.foundation.NSAutoreleasePool;
import org.robovm.apple.uikit.UIApplication;

import com.badlogic.gdx.backends.iosrobovm.IOSApplication;
import com.badlogic.gdx.backends.iosrobovm.IOSApplicationConfiguration;

import com.peaknav.compatibility.IOSLoadFactory;

/**
 * The iOS entry point: hands the shared {@link MapApp} to libGDX's RoboVM backend, with
 * {@link IOSLoadFactory} supplying everything platform-shaped.
 *
 * <p>This used to carry a LoadFactory written inline that returned null from every method,
 * including two that no longer exist on the interface. It compiled only because the module
 * never built its own sources - {@code ios/build.gradle} declared no source set, so Gradle
 * looked in {@code src/main/java}, found nothing there, and reported success. That is fixed;
 * the launcher is now held to the same compiler as the rest of the project.
 *
 * <p>It does not yet launch on a device: see {@link IOSLoadFactory} for the two platform
 * pieces still missing, and why they throw with an explanation rather than returning null.
 */
public class IOSLauncher extends IOSApplication.Delegate {

    @Override
    protected IOSApplication createApplication() {
        IOSApplicationConfiguration config = new IOSApplicationConfiguration();
        MapApp mapApp = new MapApp(new IOSLoadFactory());
        return new IOSApplication(mapApp, config);
    }

    public static void main(String[] argv) {
        NSAutoreleasePool pool = new NSAutoreleasePool();
        UIApplication.main(argv, null, IOSLauncher.class);
        pool.close();
    }
}
