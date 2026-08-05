package com.peaknav.viewer;

import org.robovm.apple.foundation.NSAutoreleasePool;
import org.robovm.apple.uikit.UIApplication;

import com.badlogic.gdx.backends.iosrobovm.IOSApplication;
import com.badlogic.gdx.backends.iosrobovm.IOSApplicationConfiguration;
import com.badlogic.gdx.graphics.glutils.HdpiMode;


/**
 * The iOS entry point: hands the shared {@link MapApp} to libGDX's RoboVM backend, with
 * {@code IOSLoadFactory} supplying everything platform-shaped.
 *
 * <p>This used to carry a LoadFactory written inline that returned null from every method,
 * including two that no longer exist on the interface. It compiled only because the module
 * never built its own sources - {@code ios/build.gradle} declared no source set, so Gradle
 * looked in {@code src/main/java}, found nothing there, and reported success. That is fixed;
 * the launcher is now held to the same compiler as the rest of the project.
 *
 * <p>The app launches and renders. What is not built on this platform - search, the download
 * chooser, the gallery and camera pickers, GPS and the compass - says so when tapped, and
 * {@code IOSLoadFactory} explains the one piece core treats as absent rather than broken:
 * there is no mapsforge graphics backend, so there is no road and path layer.
 */
public class IOSLauncher extends IOSApplication.Delegate {

    @Override
    protected IOSApplication createApplication() {
        IOSApplicationConfiguration config = new IOSApplicationConfiguration();
        // Report sizes in real pixels, not points.
        //
        // The backend defaults to HdpiMode.Logical, where Gdx.graphics.getWidth() gives points
        // - 375 on a 2x phone whose framebuffer is 750 wide. Shared code passes that straight
        // to Gdx.gl.glViewport (AbstractScreen, IntroScreen, MapViewerScreen all do), so the
        // whole app was drawn into the bottom-left quarter of the screen.
        //
        // Touch made it worse rather than merely offset: DefaultIOSInput multiplies touch
        // coordinates by pixelsPerPoint whatever this mode says, so input was already in
        // pixels while the UI was laid out in points. Nothing could be tapped, because
        // rendering and input disagreed by exactly the display scale.
        //
        // Pixels makes getWidth() the framebuffer width, which agrees with both the viewport
        // and the touch coordinates - and matches Android, whose convention this shared code
        // was written against. Desktop and Android are untouched by this setting.
        config.hdpiMode = HdpiMode.Pixels;
        // Through MapViewerIOSSingleton, not `new MapApp(...)`: shared code looks the running
        // app up via MapViewerSingleton.getAppInstance(), and an instance built around the
        // singleton leaves that null - which made getAppInstance() build a second, broken one.
        // See MapViewerIOSSingleton.
        MapViewerIOSSingleton.initializeIOSLoadFactory();
        MapApp mapApp = MapViewerIOSSingleton.getAppInstance();
        return new IOSApplication(mapApp, config);
    }

    public static void main(String[] argv) {
        NSAutoreleasePool pool = new NSAutoreleasePool();
        UIApplication.main(argv, null, IOSLauncher.class);
        pool.close();
    }
}
