package com.peaknav.viewer.desktop;

import java.awt.Component;
import java.awt.Frame;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.SwingUtilities;

/**
 * Bringing one of the app's own windows back to the user.
 *
 * <p>Pressing a button whose window is already open should show you that window, wherever it
 * got to - behind the map, minimised to the taskbar, on top of nothing at all. The obvious
 * {@link Window#toFront()} does not achieve that on its own:
 *
 * <ul>
 *   <li>a minimised window stays minimised - {@code toFront} does not un-iconify;
 *   <li>and most X11 window managers <em>ignore</em> a raise request from an application that
 *       does not currently hold the focus. That is focus-stealing prevention, and it is
 *       usually the right policy - but here the raise is the direct result of the user
 *       clicking a button in this very application, which is the case it should not apply to.
 * </ul>
 *
 * <p>Momentarily setting the window always-on-top is the portable way to say "I mean it":
 * the flag is honoured where a bare raise is not, and it is put back immediately so the
 * window does not go on floating over everything else afterwards.
 */
public final class WindowRaiser {

    private WindowRaiser() {
    }

    /**
     * Shows {@code window}, un-minimises it, raises it above the others and gives it the
     * focus, returning the caret to whatever last held it inside.
     *
     * <p>Must be called on the event dispatch thread; does nothing when given null.
     */
    public static void bringToFront(Window window) {
        if (window == null) {
            return;
        }
        // Where the caret was, captured BEFORE anything touches the focus. Raising a window
        // makes the window itself the "most recent focus owner", so reading this afterwards
        // returns the frame and the caret is restored to nowhere (measured, not guessed).
        final Component caret = window.getMostRecentFocusOwner();
        // Armed before the raise, not after: the window manager can grant the focus before
        // the raising calls have even returned, and a listener added later misses it.
        if (caret != null && !window.isFocused()) {
            window.addWindowFocusListener(new WindowAdapter() {
                @Override
                public void windowGainedFocus(WindowEvent gained) {
                    window.removeWindowFocusListener(this);
                    caret.requestFocusInWindow();
                }
            });
        }

        if (window instanceof Frame) {
            Frame frame = (Frame) window;
            // Clear only the iconified bit: a window the user had maximised must come back
            // maximised, not restored to some default size.
            frame.setExtendedState(frame.getExtendedState() & ~Frame.ICONIFIED);
        }
        if (!window.isVisible()) {
            window.setVisible(true);
        }
        boolean wasAlwaysOnTop = window.isAlwaysOnTop();
        try {
            if (window.isAlwaysOnTopSupported()) {
                window.setAlwaysOnTop(true);
                window.toFront();
                window.setAlwaysOnTop(wasAlwaysOnTop);
            } else {
                window.toFront();
            }
        } catch (SecurityException notPermitted) {
            // Some sandboxes refuse always-on-top; the plain raise is then the best on offer.
            window.toFront();
        }

        requestApplicationForeground();

        if (caret == null) {
            window.requestFocus();
        } else if (window.isFocused()) {
            caret.requestFocusInWindow();
        }
    }

    /**
     * Asks macOS to bring the whole application forward, not just this window.
     *
     * <p>On macOS raising a window inside a background application leaves the application
     * itself behind: the window comes to the front of PeakNav's own windows and stays behind
     * whatever the user is actually in. {@code Desktop.requestForeground} is the supported
     * way to ask, and it is a no-op elsewhere - Windows and Linux report the action as
     * unsupported, where the raise above is what does the work.
     *
     * <p>Called reflectively because this module compiles at release 8 and both
     * {@code APP_REQUEST_FOREGROUND} and {@code requestForeground} arrived in Java 9. The
     * runtime is 17 (see the bundled JDKs in build.gradle), so the call is there; being
     * absent is simply nothing happening, which is what it does on those platforms anyway.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void requestApplicationForeground() {
        try {
            Class<?> desktopType = Class.forName("java.awt.Desktop");
            if (!(Boolean) desktopType.getMethod("isDesktopSupported").invoke(null)) {
                return;
            }
            Object desktop = desktopType.getMethod("getDesktop").invoke(null);
            Class actionType = Class.forName("java.awt.Desktop$Action");
            Object requestForeground = Enum.valueOf(actionType, "APP_REQUEST_FOREGROUND");
            boolean supported = (Boolean) desktopType
                    .getMethod("isSupported", actionType).invoke(desktop, requestForeground);
            if (supported) {
                // false: bring this application forward, without raising all of its windows
                // over one another - the one we just raised should stay on top of the rest.
                desktopType.getMethod("requestForeground", boolean.class)
                        .invoke(desktop, false);
            }
        } catch (ReflectiveOperationException | IllegalArgumentException | LinkageError
                | SecurityException | UnsupportedOperationException absent) {
            // Older runtime, headless, or a platform without the notion: the raise stands
            // on its own.
        }
    }

    /** {@link #bringToFront(Window)} for a component's window, e.g. a file chooser's dialog. */
    public static void bringToFront(Component insideTheWindow) {
        if (insideTheWindow instanceof Window) {
            bringToFront((Window) insideTheWindow);
            return;
        }
        bringToFront(SwingUtilities.getWindowAncestor(insideTheWindow));
    }
}
