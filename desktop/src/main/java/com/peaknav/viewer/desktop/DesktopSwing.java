package com.peaknav.viewer.desktop;

import com.badlogic.gdx.Gdx;
import com.peaknav.utils.PeakNavUtils;
import com.peaknav.viewer.MapViewerSingleton;

import javax.swing.SwingUtilities;

/**
 * The one way into Swing from the desktop app, because two things can go wrong there and
 * both used to go wrong in silence.
 *
 * <p><b>A runtime without a desktop.</b> A "headless" Java - Debian and Ubuntu's
 * {@code openjdk-*-jre-headless}, the usual one on a server and a common one to have
 * installed by accident - has no AWT at all, so every window constructor throws
 * {@link java.awt.HeadlessException}. The map itself does not care: it draws through
 * GLFW, not AWT. Place search, "open an image", the GPX pickers and every dialog do,
 * and on such a runtime the buttons did exactly nothing: the exception was thrown on
 * the event thread, where the app's crash logger writes nothing and shows nothing.
 * That is what "the search menu and open image from gallery do not work" was.
 *
 * <p><b>Anything else thrown on the event thread.</b> Same silence, same cause; the
 * stack trace is printed here instead of vanishing.
 *
 * <p>The packaged app (installer, .deb, .dmg, .AppImage) bundles a full JRE and never
 * takes this path - only {@code java -jar peaknav.jar} on a runtime without AWT does.
 */
public final class DesktopSwing {

    /**
     * Whether this runtime can show windows. Asked once: the answer cannot change, and
     * a runtime missing the AWT libraries can fail with an error rather than an
     * exception, which a plain {@code isHeadless()} call would not survive.
     */
    private static final boolean AVAILABLE = detectAvailable();

    /** So a user clicking three buttons gets one explanation, not three. */
    private static volatile boolean reported;

    /** Long enough to read two lines on a map that keeps drawing behind it. */
    private static final long TOAST_MILLIS = 6000;

    /**
     * Said on the console when a window cannot be opened. "Headless" is avoided here: in
     * this project that word means PeakNav's own off-screen renderer (the {@code :headless}
     * module, {@code peaknav-headless.jar}), which is a different thing entirely - this is
     * about the Java installation the app was started with.
     */
    public static final String NO_WINDOWS_CONSOLE =
            "PeakNav: this Java installation cannot open windows - it has no AWT, which is what"
            + " the '-jre-headless' packages leave out. The map itself runs; place search,"
            + " opening a picture or a GPX file, and every dialog cannot. Install a full Java"
            + " (on Debian/Ubuntu 'openjdk-17-jre', not 'openjdk-17-jre-headless'), or use the"
            + " PeakNav installer for your system, which brings its own Java. This has nothing"
            + " to do with PeakNav's headless renderer.";

    private DesktopSwing() {
    }

    private static boolean detectAvailable() {
        try {
            return !java.awt.GraphicsEnvironment.isHeadless();
        } catch (Throwable noAwtAtAll) {
            return false;
        }
    }

    /** True when Swing windows can be opened; false on a headless runtime. */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /**
     * Runs {@code work} on the event dispatch thread, or explains why it cannot.
     *
     * <p>Safe from any thread, and in particular from the render thread, which is where
     * the buttons call from.
     */
    public static void onEdt(Runnable work) {
        if (!AVAILABLE) {
            reportUnavailable();
            return;
        }
        SwingUtilities.invokeLater(() -> {
            try {
                work.run();
            } catch (Throwable thrown) {
                // Not rethrown: one broken dialog must not take the map down with it.
                System.err.println("PeakNav: a window failed to open");
                thrown.printStackTrace();
            }
        });
    }

    /** The map's own toast, which needs no AWT, plus a line on the console for whoever started it. */
    private static void reportUnavailable() {
        if (!reported) {
            reported = true;
            // Both streams on purpose: the app routes its own logging to stdout, and a
            // launcher script may keep only one of the two.
            System.out.println(NO_WINDOWS_CONSOLE);
            System.err.println(NO_WINDOWS_CONSOLE);
        }
        // The toast touches the scene, so it belongs on the render thread.
        Gdx.app.postRunnable(() -> MapViewerSingleton.getViewerInstance()
                .toast(PeakNavUtils.s("Java_no_desktop"), TOAST_MILLIS));
    }
}
