package com.peaknav.viewer.desktop;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.peaknav.compatibility.NativeScreenCallerDesktop;
import com.peaknav.utils.PeakNavUtils;
import com.peaknav.viewer.MapApp;
import com.peaknav.viewer.MapViewerSingleton;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileFilter;


/**
 * The "open an image" file chooser, on the desktop.
 *
 * <p>Two rules govern everything here, and breaking either one took the app down:
 *
 * <ul>
 *   <li><b>Swing runs on the event dispatch thread.</b> This is reached from the widget's
 *       click handler, which is the libGDX render thread; building and showing a chooser
 *       there is undefined behaviour, and it was doing exactly that.
 *   <li><b>Only one chooser at a time.</b> {@code showOpenDialog} is modal - it does not
 *       return until the user answers it. Clicking the button twice therefore asked for a
 *       second modal dialog on top of a thread already parked inside the first, and that
 *       thread was the render loop.
 * </ul>
 *
 * <p>The picture is then decoded off both threads: a photo is megabytes to decode and
 * rotate, which would freeze the interface on the EDT and drop frames on the render
 * thread. {@code BackgroundPicManager.setBackgroundPixmap} is written for exactly that -
 * it defers its one GL call to the render thread itself.
 */
public class GalleryPickDesktop {

    /** True while a chooser is on screen. Only one may be; see the class comment. */
    private static final AtomicBoolean OPEN = new AtomicBoolean(false);

    /** The chooser on screen, so a second request can raise it instead of stacking one. */
    private static volatile JFileChooser showing;

    private GalleryPickDesktop() {
    }

    /**
     * Asks for an image and sets it as the background; does nothing if the user cancels.
     *
     * <p>Safe from any thread, and safe to call again while a chooser is already open -
     * the second call brings that one to the front rather than opening another.
     */
    public static void open() {
        DesktopSwing.onEdt(() -> {
            if (!OPEN.compareAndSet(false, true)) {
                // Already asking: show the user the chooser they already have, wherever it
                // has ended up - behind the map window, or minimised. See WindowRaiser.
                WindowRaiser.bringToFront(showing);
                return;
            }
            try {
                selectImage();
            } finally {
                showing = null;
                OPEN.set(false);
            }
        });
    }

    /**
     * Files dragged onto the window: every .gpx among them is loaded as a path, and the
     * first image becomes the background, exactly as if each had been picked in its
     * chooser. Anything else is ignored, and so is a drop while the map is not on screen
     * (the intro, the download chooser).
     *
     * <p>Called on the render thread by the window listener; the reading and decoding go
     * to other threads, as for a picked file.
     */
    public static void openDropped(String[] paths) {
        MapApp app = MapViewerSingleton.getAppInstance();
        if (app.getScreen() != app.mapViewerScreen) {
            return;
        }
        boolean imageTaken = false;
        for (String path : paths) {
            File file = new File(path).getAbsoluteFile();
            if (!file.isFile()) {
                continue;
            }
            if (file.getName().toLowerCase().endsWith(".gpx")) {
                NativeScreenCallerDesktop.loadGpxFile(file);
            } else if (!imageTaken && isImage(file)) {
                imageTaken = true;
                new Thread(() -> setAppBackgroundImage(file), "gallery-image-load").start();
            }
        }
    }

    private static boolean isImage(File f) {
        String name = f.getName().toLowerCase();
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg");
    }

    private static void selectImage() {
        // The system's own dialog where there is one, with its previews of the pictures.
        File picked = NativeFileDialogs.open(null,
                new NativeFileDialogs.Filter("Gallery files", "png", "jpg", "jpeg"));
        if (picked == null) {
            return;
        }
        if (picked != NativeFileDialogs.UNAVAILABLE) {
            new Thread(() -> setAppBackgroundImage(picked), "gallery-image-load").start();
            return;
        }
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        fileChooser.setAcceptAllFileFilterUsed(false);

        fileChooser.addChoosableFileFilter(new FileFilter() {
            @Override
            public boolean accept(File f) {
                return isImage(f) || f.isDirectory();
            }

            @Override
            public String getDescription() {
                return "Gallery files (*.png, *.jpg, *.jpeg)";
            }
        });

        showing = fileChooser;
        int result = fileChooser.showOpenDialog(null);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File selectedFile = fileChooser.getSelectedFile();
        if (selectedFile == null) {
            System.err.println("No image selected.");
            return;
        }
        final File image = selectedFile.getAbsoluteFile();
        new Thread(() -> setAppBackgroundImage(image), "gallery-image-load").start();
    }

    private static void setAppBackgroundImage(File imageFile) {
        try {
            // Go through the shared byte path so the background gets EXIF orientation
            // applied, exactly as it does on Android.
            byte[] bytes = Files.readAllBytes(imageFile.toPath());
            PeakNavUtils.setBytesAsBackgroundImage(bytes);
            PeakNavUtils.checkImageGpsAndPrompt(bytes);
        } catch (IOException e) {
            // Fall back to decoding straight from the file, without orientation/GPS.
            Pixmap pixmap = new Pixmap(new FileHandle(imageFile));
            MapViewerSingleton.getViewerInstance().backgroundPicManager.setBackgroundPixmap(pixmap);
        }
    }
}
