package com.peaknav.utils;

import java.io.File;

/**
 * Where iOS lets an app keep things it can re-fetch.
 *
 * <p>{@code $HOME/Library/Caches} inside the app's sandbox container: the directory iOS
 * itself may empty when storage runs short, which is the correct home for downloaded map
 * tiles and rendered bitmaps - losing them costs a re-download, not user data. (Anything
 * the user would mind losing belongs in Documents, and nothing here is that.)
 *
 * <p>Read from {@code $HOME} rather than through NSSearchPath: on iOS the process's home
 * IS the sandbox container, so the two agree, and this needs no bindings.
 */
public class IOSCaches extends PeakNavCaches {

    @Override
    public File getCacheDir() {
        String home = System.getenv("HOME");
        File caches = new File(home == null ? "." : home, "Library/Caches/peaknav");
        if (!caches.exists()) {
            caches.mkdirs();
        }
        return caches;
    }
}
