package com.peaknav.viewer;

import org.robovm.apple.foundation.NSAutoreleasePool;
import org.robovm.apple.foundation.NSURL;
import org.robovm.apple.uikit.UIApplication;
import org.robovm.apple.uikit.UIApplicationOpenURLOptions;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.iosrobovm.IOSApplication;
import com.badlogic.gdx.backends.iosrobovm.IOSApplicationConfiguration;
import com.badlogic.gdx.graphics.glutils.HdpiMode;
import com.peaknav.compatibility.IOSDeviceTable;
import com.peaknav.utils.PeakNavUtils;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Timer;
import java.util.TimerTask;

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
 * <p>Beyond launching, this is also where GPX tracks and photos opened from other apps
 * arrive: Info.plist registers the .gpx, .jpeg and .png document types, and the system
 * calls {@link #openURL} with a copy of the file in Documents/Inbox.
 */
public class IOSLauncher extends IOSApplication.Delegate {

    /** Give a cold-started app this long to bring the map screen up before dropping a file. */
    private static final int DELIVERY_ATTEMPTS = 60;
    private static final long RETRY_MS = 250L;

    /**
     * A .gpx or a photo handed over by another app - Files, Mail, a share sheet. The bytes
     * decide which it is, as on Android: JPEG or PNG magic is a photo, anything else a
     * track. They are read
     * immediately (the Inbox copy is ours, but there is no reason to gamble on its
     * lifetime), and delivery waits for the map screen: on a cold start this fires long
     * before core exists, the same race Android's share intent has, resolved the same way.
     */
    @Override
    public boolean openURL(UIApplication app, NSURL url, UIApplicationOpenURLOptions options) {
        if (url == null || !url.isFileURL()) {
            return false;
        }
        byte[] bytes = readFile(url.getPath());
        if (bytes == null) {
            return false;
        }
        deliverWhenReady(bytes, 0);
        return true;
    }

    private void deliverWhenReady(final byte[] bytes, final int attempt) {
        if (attempt > DELIVERY_ATTEMPTS) {
            return;
        }
        if (MapViewerSingleton.getViewerInstance() == null || Gdx.app == null) {
            new Timer("file-delivery", true).schedule(new TimerTask() {
                @Override
                public void run() {
                    deliverWhenReady(bytes, attempt + 1);
                }
            }, RETRY_MS);
            return;
        }
        if (PeakNavUtils.looksLikeImage(bytes)) {
            // As a photo picked from the library: "Loading..." up, decoded on a worker.
            MapViewerSingleton.getViewerInstance().setPhotoLoading(true);
            PeakNavUtils.getC().submitExecutorGeneric(() -> {
                try {
                    PeakNavUtils.setBytesAsBackgroundImage(bytes);
                    PeakNavUtils.checkImageGpsAndPrompt(bytes);
                } catch (RuntimeException unreadable) {
                    MapViewerSingleton.getViewerInstance().setPhotoLoading(false);
                }
            });
            return;
        }
        // loadFromXml toasts and moves the camera, so it belongs on the render thread.
        final String xml = new String(bytes, StandardCharsets.UTF_8);
        Gdx.app.postRunnable(() -> PeakNavUtils.getC().gpxManager.loadFromXml(xml));
    }

    private static byte[] readFile(String path) {
        // A byte-shuffling loop rather than Files.readAllBytes: java.nio.file does not
        // exist on RoboVM's runtime (see AGENTS.md).
        try (InputStream in = new FileInputStream(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException failed) {
            return null;
        }
    }

    @Override
    protected IOSApplication createApplication() {
        IOSApplicationConfiguration config = new IOSApplicationConfiguration();
        // The screen densities of the devices libGDX's own table does not know (see IOSDeviceTable).
        IOSDeviceTable.register(config);
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

        // No audio, deliberately: the app plays none, yet libGDX's iOS backend would
        // otherwise open an OpenAL device and an AVAudioSession at launch (useAudio
        // defaults true). That native init runs before the first frame, OUTSIDE the only
        // Throwable guard the app has (MapApp.render), so when CoreAudio is slow to answer
        // - the alcOpenDevice / AudioUnit RPC timeout seen intermittently - it takes the
        // whole launch down with a SIGABRT. A launch crash is an App Store rejection;
        // turning the subsystem off removes the entire failure mode at no cost.
        config.useAudio = false;
        // 60 frames a second at most. libGDX's default is the screen's maximum, 120 on a
        // ProMotion iPhone: twice the drawing, and the battery that goes with it.
        config.preferredFramesPerSecond = 60;
        // And 20 while nothing on the map is happening; see IdleFrameRate. The desktop keeps
        // drawing every frame.
        com.peaknav.viewer.screens.IdleFrameRate.setEnabled(true);
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
