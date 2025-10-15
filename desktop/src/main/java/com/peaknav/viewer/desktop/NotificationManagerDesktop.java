package com.peaknav.viewer.desktop;

import com.peaknav.compatibility.NotificationManagerPeakNav;

public class NotificationManagerDesktop extends NotificationManagerPeakNav {

    private static NotificationManagerDesktop instance;

    private NotificationManagerDesktop() {}

    public static NotificationManagerDesktop getInstance() {
        if (instance == null) {
            synchronized (NotificationManagerDesktop.class) {
                if (instance == null)
                    instance = new NotificationManagerDesktop();
            }
        }
        return instance;
    }

    @Override
    public void setText(String text, float progress) {
        new ToastDesktop(text, 1500).setVisible(true);
    }

    @Override
    public void clear() {

    }
}
