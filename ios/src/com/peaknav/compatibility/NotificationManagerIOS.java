package com.peaknav.compatibility;

/**
 * Progress notifications, which iOS does not have an equivalent of.
 *
 * <p>Android shows map downloads in the notification shade; iOS gives a foreground app no
 * such surface, and a local notification would be wrong - it would fire while the user is
 * looking at the very screen reporting the same progress. The app's own on-screen progress
 * is the whole story on this platform, so this deliberately does nothing rather than
 * pretending to.
 */
public class NotificationManagerIOS extends NotificationManagerPeakNav {

    @Override
    public void setText(String text, float progress) {
    }

    @Override
    public void clear() {
    }
}
