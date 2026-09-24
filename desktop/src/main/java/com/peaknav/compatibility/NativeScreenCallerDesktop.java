package com.peaknav.compatibility;

import com.peaknav.viewer.mapscreens.MapScreens;
import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.s;
import static com.peaknav.utils.PreferencesManager.P;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.peaknav.database.LuceneGeonameSearch;
import com.peaknav.gesture.OrientationPointerListener;
import com.peaknav.ui.ClickCallback;
import com.peaknav.ui.CurrentLocationCallback;
import com.peaknav.ui.CurrentLocationListener;
import com.peaknav.ui.TextFieldsCallback;
import com.peaknav.viewer.MapViewerSingleton;
import com.peaknav.viewer.desktop.DesktopSwing;
import com.peaknav.viewer.desktop.GalleryPickDesktop;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Rectangle;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

public class NativeScreenCallerDesktop extends NativeScreenCaller {

    private final List<LuceneGeonameSearch.GeonameResult> jGeonameResults = new ArrayList<>();

    @Override
    public void getCallOnUIThread(Runnable runnable) {
        runnable.run();
    }

    /**
     * The download chooser, the map screen the phones have: see {@link MapScreens}. The desktop
     * used to have no chooser at all and downloaded around the point it was handed.
     */
    @Override
    public void openMapDataDownloadChooser(double lat, double lon, boolean goToAfterDownload) {
        MapScreens.openDownloadChooser(lat, lon, goToAfterDownload, false);
    }

    /** The welcome screen's download button: the chooser, starting on the whole world. */
    @Override
    public void openMapDataDownloadChooserWizard() {
        boolean located = !getC().L.isCurrentLocationNotSet();
        MapScreens.openDownloadChooser(
                located ? getC().L.getTargetLatitude() : 0,
                located ? getC().L.getTargetLongitude() : 0,
                false, true);
    }

    /** The search screen with its map, drawn by libGDX; it replaced a Swing window. */
    @Override
    public void openScreenSearchLocation(ClickCallback callback) {
        MapScreens.openSearch();
    }

    /**
     * True while the headless renderer is drawing, and false in the app itself.
     *
     * <p>The desktop hides the camera and gyroscope buttons, having neither - but the
     * headless renderer runs on this same platform layer, and what it draws is the
     * interface the phones show: the tutorial's own screenshots come from it, and their
     * markers are placed on those two buttons. So it asks for them back.
     */
    private static volatile boolean deviceWidgetsShown = false;

    /** Called by the headless renderer; see {@link #deviceWidgetsShown}. */
    public static void setDeviceWidgetsShown(boolean shown) {
        deviceWidgetsShown = shown;
    }

    /** No camera a desktop app can use; the button is left out. */
    @Override
    public boolean hasCamera() {
        return deviceWidgetsShown;
    }

    /**
     * No accelerometer or gyroscope on a desktop or on all but a few laptops, and Java has
     * no way to read one anyway, so the view cannot follow how the machine is held.
     */
    @Override
    public boolean hasOrientationSensors() {
        return deviceWidgetsShown;
    }

    @Override
    public void openCameraPictureView() {

    }

    @Override
    public void openGalleryPick() {
        // Called from the render thread; the chooser puts itself on the EDT, and refuses
        // to open a second time while one is already up. Both matter: see GalleryPickDesktop.
        GalleryPickDesktop.open();
    }

    @Override
    public void pickGpxFile() {
        DesktopSwing.onEdt(() -> {
            javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
            chooser.setFileSelectionMode(javax.swing.JFileChooser.FILES_ONLY);
            chooser.setAcceptAllFileFilterUsed(false);
            chooser.addChoosableFileFilter(new javax.swing.filechooser.FileFilter() {
                @Override
                public boolean accept(java.io.File f) {
                    return f.isDirectory() || f.getName().toLowerCase().endsWith(".gpx");
                }

                @Override
                public String getDescription() {
                    return "GPX tracks (*.gpx)";
                }
            });
            if (chooser.showOpenDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) {
                return;
            }
            java.io.File file = chooser.getSelectedFile();
            if (file == null) {
                return;
            }
            loadGpxFile(file);
        });
    }

    /** Reads a .gpx on a worker and adds its paths; from the chooser or a drop on the window. */
    public static void loadGpxFile(java.io.File file) {
        getC().submitExecutorGeneric(() -> {
            try {
                String xml = new String(
                        java.nio.file.Files.readAllBytes(file.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
                getC().gpxManager.loadFromXml(xml);
            } catch (java.io.IOException e) {
                System.err.println("[GPX] could not read " + file + ": " + e.getMessage());
            }
        });
    }

    @Override
    public void promptGoToImageLocation(double lat, double lon) {
        DesktopSwing.onEdt(() -> {
            int dialogResult = JOptionPane.showConfirmDialog(
                    null,
                    s("Go_to_image_location_prompt"), // message
                    s("Image_location_found"),        // title
                    JOptionPane.YES_NO_OPTION
            );
            if (dialogResult == JOptionPane.YES_OPTION) {
                // Runs on the EDT; hop to the GL thread for the core-state mutation.
                Gdx.app.postRunnable(() -> getC().L.setCurrentTargetCoords(lat, lon));
            }
        });
    }

    @Override
    public void promptYesNo(String title, String message, Runnable onYes) {
        DesktopSwing.onEdt(() -> {
            int dialogResult = JOptionPane.showConfirmDialog(
                    null, message, title, JOptionPane.YES_NO_OPTION);
            if (dialogResult == JOptionPane.YES_OPTION) {
                onYes.run();
            }
        });
    }

    @Override
    public void warnCannotReadImageLocation() {
        DesktopSwing.onEdt(() -> JOptionPane.showMessageDialog(
                null,
                s("Image_location_missing"),
                s("Image_location_missing_title"),
                JOptionPane.WARNING_MESSAGE));
    }

    @Override
    public void openAppInfoScreen() {
        openBundledHtml("info/app_info.html");
    }


    /**
     * Opens a bundled HTML page in the system browser, together with any files it references
     * relatively.
     *
     * <p>Everything is extracted into one temp directory and opened from there, in every build.
     * Running from a jar it has to be — {@code Gdx.files.internal(...).file()} is not a real
     * file then — and doing the same when the assets happen to be on disk means the packaged
     * app behaves like the one being developed against, rather than only breaking once shipped.
     *
     * <p>That is what went wrong before: the page alone was copied to a temp file, so the
     * tutorial's four screenshots resolved next to it and were not there. In the IDE the assets
     * folder was real and the page opened in place, looking perfect; every installed build
     * showed a blank page.
     *
     * <p>Failures surface as an alert instead of a RuntimeException on the GL thread (which
     * used to kill the action silently).
     */
    private void openBundledHtml(String internalPath, String... relatedFiles) {
        try {
            java.io.File dir = java.nio.file.Files.createTempDirectory("peaknav-help").toFile();
            dir.deleteOnExit();

            String pageName = internalPath.substring(internalPath.lastIndexOf('/') + 1);
            String parent = internalPath.substring(0, internalPath.lastIndexOf('/') + 1);

            java.io.File page = new java.io.File(dir, pageName);
            page.deleteOnExit();
            Gdx.files.internal(internalPath).copyTo(new com.badlogic.gdx.files.FileHandle(page));

            for (String related : relatedFiles) {
                java.io.File target = new java.io.File(dir, related);
                target.deleteOnExit();
                Gdx.files.internal(parent + related)
                        .copyTo(new com.badlogic.gdx.files.FileHandle(target));
            }
            if (!DesktopSwing.requireWindows()) {
                return; // no AWT: no browser to open it in, and one message for every button
            }
            Desktop.getDesktop().open(page);
        } catch (Exception e) {
            alertMessage(internalPath + ": " + e.getMessage());
        }
    }

    /**
     * Opens the coordinate in the browser, on Wikipedia's GeoHack page.
     *
     * <p>GeoHack is the page Wikipedia's coordinate links lead to: it takes a point and lists
     * the services that can show it - OpenStreetMap, Google, Bing, topographic maps, aerial
     * imagery, national mapping agencies for that country. That is a better answer than
     * picking one provider on the user's behalf, and it is the desktop equivalent of handing
     * the point to Android and letting the system offer the choice.
     */
    @Override
    public void openCoordinate(double latitude, double longitude) {
        // params takes decimal degrees separated by a semicolon; the language follows the
        // app's own, so the page comes up in the same language as the interface.
        String url = com.peaknav.utils.CoordinateLinks.geoHackUrl(
                latitude, longitude, java.util.Locale.getDefault().getLanguage());
        if (!DesktopSwing.requireWindows()) {
            return; // opening a browser goes through java.awt.Desktop, which needs AWT too
        }
        try {
            com.badlogic.gdx.Gdx.net.openURI(url);
        } catch (Exception e) {
            alertMessage(url);
        }
    }

    /**
     * "Go to my position" on a machine with no GPS: the position is estimated from the
     * internet connection (see {@link com.peaknav.viewer.desktop.IpLocationDesktop}), which
     * is the only source a desktop has. It used to do nothing at all - the button was there,
     * and pressing it produced neither a move nor a word.
     *
     * <p>The estimate leaves the machine's address with an online service, so it is asked
     * for once and the answer remembered; and it is an estimate, so the toast names the
     * place it landed on rather than quietly moving the map somewhere odd.
     */
    private final CurrentLocationListener currentLocationListener = new CurrentLocationListener() {
        @Override
        public void getCurrentLocation(CurrentLocationCallback currentLocationCallback) {
            if (P.isIpLocationConsent()) {
                estimatePositionFromNetwork(currentLocationCallback);
                return;
            }
            DesktopSwing.onEdt(() -> {
                // Wrapped: a paragraph handed to JOptionPane as plain text is laid out on one
                // line, and this one came out a metre and a half wide.
                int answer = JOptionPane.showConfirmDialog(
                        null,
                        "<html><body style='width:380px'>"
                                + escapeHtml(s("Ip_location_consent")) + "</body></html>",
                        s("Ip_location_title"),
                        JOptionPane.YES_NO_OPTION);
                if (answer != JOptionPane.YES_OPTION) {
                    return;
                }
                // Off the EDT: writing a preference flushes it to disk.
                getC().submitExecutorGeneric(() -> P.setIpLocationConsent(true));
                estimatePositionFromNetwork(currentLocationCallback);
            });
        }
    };

    /** Asks the network where this machine is, and hands the answer to the map. */
    private void estimatePositionFromNetwork(CurrentLocationCallback callback) {
        makeToast(s("Ip_location_searching"));
        com.peaknav.viewer.desktop.IpLocationDesktop.locate(
                new com.peaknav.viewer.desktop.IpLocationDesktop.Listener() {
                    @Override
                    public void located(double latitude, double longitude, String placeName) {
                        // The callback moves the camera, which belongs on the render thread;
                        // the HTTP answer arrives on a network one.
                        Gdx.app.postRunnable(() -> callback.setCurrentLocation(
                                (float) longitude, (float) latitude));
                        String where = placeName.isEmpty()
                                ? String.format(java.util.Locale.ROOT, "%.3f, %.3f", latitude, longitude)
                                : placeName;
                        makeToast(s("Ip_location_estimated") + " " + where);
                    }

                    @Override
                    public void failed() {
                        makeToast(s("Ip_location_failed"));
                    }
                });
    }

    @Override
    public CurrentLocationListener getCurrentLocationListener() {
        return currentLocationListener;
    }

    @Override
    public void chooseSkyTime() {
        DesktopSwing.onEdt(() -> {
            com.peaknav.sky.SkyModel sky = getC().skyModel;
            long init = sky.currentTimeMillis();
            javax.swing.JSpinner spinner = new javax.swing.JSpinner(new javax.swing.SpinnerDateModel(
                    new java.util.Date(init), null, null, java.util.Calendar.MINUTE));
            spinner.setEditor(new javax.swing.JSpinner.DateEditor(spinner, "yyyy-MM-dd HH:mm"));
            Object[] options = { s("OK"), s("Sky_time_device_clock"), s("Cancel") };
            int result = JOptionPane.showOptionDialog(null, spinner, s("Sky_time"),
                    JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
            if (result == 0) {
                java.util.Date d = (java.util.Date) spinner.getValue();
                sky.setCustomTimeMillis(d.getTime());
            } else if (result == 1) {
                sky.clearCustomTime();
            }
        });
    }

    @Override
    public void askForDownloadScreen(double lat, double lon) {
        // Marshalled to the EDT: this is reached from inside MapViewerScreen.render (a camera fly
        // ending over missing data), and a synchronous JOptionPane there both violates Swing
        // threading and freezes the whole render loop until the user answers.
        DesktopSwing.onEdt(() -> {
            int dialogResult = JOptionPane.showConfirmDialog(
                    null,
                    s("Missing_data_prompt"), // message
                    s("Missing_data_prompt"), // title
                    JOptionPane.YES_NO_OPTION
            );

            if (dialogResult == JOptionPane.YES_OPTION) {
                // The download workers honour the privacy consent for downloading files
                // (PeakNavDownloadManager checks P.isCollectDownloadInfo() and skips every
                // fetch without it). Android asks for that consent in its download chooser;
                // the desktop never did, so a user who reached this prompt without having
                // pressed the intro screen's download button got a download that queued
                // everything, showed progress - and fetched nothing. Ask here, like Android.
                if (!P.isCollectDownloadInfo()) {
                    int consent = JOptionPane.showConfirmDialog(
                            null,
                            s("Missing_download_info_consent"),
                            s("Missing_data_download"),
                            JOptionPane.YES_NO_OPTION);
                    if (consent != JOptionPane.YES_OPTION) {
                        return; // no consent, no download - and no silent pretend-download
                    }
                    getC().submitExecutorGeneric(() -> P.setCollectDownloadInfo(true));
                }
                // The place the prompt is about, not the current target: from the search
                // screen's Go To the prompt comes before the flight, with the target still on
                // the old spot, and the chooser opened there. goToAfterDownload as on Android.
                openMapDataDownloadChooser(lat, lon, true);
            } else if (dialogResult == JOptionPane.NO_OPTION) {
                // Go back to where we were, without re-running the missing-data check: doing that
                // here would pop this very dialog straight back up when the old spot lacks data
                // too. Target mutation belongs on the GL thread, not the EDT.
                Gdx.app.postRunnable(() -> getC().L.setCurrentTargetCoords(
                        getC().L.getCurrentLatitude(),
                        getC().L.getCurrentLongitude(),
                        false
                ));
            }
        });
    }

    @Override
    public void shareSnapshot(Pixmap pixmap, com.peaknav.utils.SnapshotInfo info) {
        if (pixmap == null) {
            return;
        }
        // Default file name carries a timestamp so successive shots don't collide.
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
        final String defaultName = "PeakNav_" + stamp + ".png";
        DesktopSwing.onEdt(() -> {
            try {
                javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
                chooser.setDialogTitle(s("Save_image"));
                chooser.setFileSelectionMode(javax.swing.JFileChooser.FILES_ONLY);
                chooser.setSelectedFile(new java.io.File(defaultName));
                javax.swing.filechooser.FileNameExtensionFilter pngFilter =
                        new javax.swing.filechooser.FileNameExtensionFilter(s("Save_image_png"), "png");
                javax.swing.filechooser.FileNameExtensionFilter jpgFilter =
                        new javax.swing.filechooser.FileNameExtensionFilter(s("Save_image_jpeg"), "jpg", "jpeg");
                chooser.setAcceptAllFileFilterUsed(false);
                chooser.addChoosableFileFilter(pngFilter);
                chooser.addChoosableFileFilter(jpgFilter);
                chooser.setFileFilter(pngFilter);

                if (chooser.showSaveDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) {
                    pixmap.dispose();
                    return;
                }
                java.io.File file = chooser.getSelectedFile();
                if (file == null) {
                    pixmap.dispose();
                    return;
                }
                // Pick the format from the file extension; JPEG for .jpg/.jpeg, PNG otherwise. When
                // no known extension is typed, fall back to the selected filter and append it.
                String lower = file.getName().toLowerCase(java.util.Locale.ROOT);
                boolean jpeg;
                if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
                    jpeg = true;
                } else if (lower.endsWith(".png")) {
                    jpeg = false;
                } else {
                    jpeg = chooser.getFileFilter() == jpgFilter;
                    file = new java.io.File(file.getParentFile(), file.getName() + (jpeg ? ".jpg" : ".png"));
                }
                if (file.exists()) {
                    int overwrite = javax.swing.JOptionPane.showConfirmDialog(null,
                            s("Overwrite_prompt"), s("File_exists"),
                            javax.swing.JOptionPane.YES_NO_OPTION);
                    if (overwrite != javax.swing.JOptionPane.YES_OPTION) {
                        pixmap.dispose();
                        return;
                    }
                }
                final java.io.File target = file;
                final boolean asJpeg = jpeg;
                Thread saver = new Thread(() -> {
                    try {
                        savePixmapToFile(pixmap, target, asJpeg, info);
                        DesktopSwing.onEdt(() -> javax.swing.JOptionPane.showMessageDialog(
                                null, s("Image_saved") + ":\n" + target.getAbsolutePath()));
                    } catch (Exception e) {
                        DesktopSwing.onEdt(() -> javax.swing.JOptionPane.showMessageDialog(
                                null, s("Save_failed_msg") + "\n" + e.getMessage(),
                                s("Save_failed"), javax.swing.JOptionPane.ERROR_MESSAGE));
                    } finally {
                        pixmap.dispose();
                    }
                }, "snapshot-save");
                saver.setDaemon(true);
                saver.start();
            } catch (Throwable t) {
                pixmap.dispose();
            }
        });
    }

    /**
     * Writes a libGDX {@link Pixmap} to disk as PNG or JPEG via ImageIO. The pixmap comes straight
     * from {@code glReadPixels} and is therefore bottom-up, so rows are flipped here (PNG output on
     * other platforms relies on PixmapIO's own flip). JPEG has no alpha channel, so it is written as
     * opaque RGB.
     */
    // protected, not private: the headless renderer saves snapshots through this very method
    // so that a scripted capture and the share button produce byte-identical images.
    protected static void savePixmapToFile(Pixmap pixmap, java.io.File file, boolean jpeg)
            throws java.io.IOException {
        savePixmapToFile(pixmap, file, jpeg, null);
    }

    /** As above, with the view's position and pose written into the file's EXIF block when given. */
    protected static void savePixmapToFile(Pixmap pixmap, java.io.File file, boolean jpeg,
                                           com.peaknav.utils.SnapshotInfo info)
            throws java.io.IOException {
        int w = pixmap.getWidth(), h = pixmap.getHeight();
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                w, h, jpeg ? java.awt.image.BufferedImage.TYPE_INT_RGB
                           : java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            int srcY = h - 1 - y; // flip: glReadPixels rows go bottom-to-top
            for (int x = 0; x < w; x++) {
                int rgba = pixmap.getPixel(x, srcY); // 0xRRGGBBAA
                int r = (rgba >>> 24) & 0xFF;
                int g = (rgba >>> 16) & 0xFF;
                int b = (rgba >>> 8) & 0xFF;
                // Alpha is forced opaque rather than copied. GL blending writes the *destination*
                // alpha as well as the colour, so anywhere a translucent thing was drawn - a label
                // plate above all - the framebuffer ends up with alpha < 255. The RGB there is
                // already correct: the mountains behind have been blended in. Copying that alpha
                // into the PNG made those areas translucent, so the label looked like a hole
                // showing nothing rather than the terrain it had just been blended over.
                image.setRGB(x, y, 0xFF000000 | (r << 16) | (g << 8) | b);
            }
        }
        java.io.File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        java.io.ByteArrayOutputStream encoded = new java.io.ByteArrayOutputStream(w * h);
        if (!javax.imageio.ImageIO.write(image, jpeg ? "jpg" : "png", encoded)) {
            throw new java.io.IOException("no ImageIO writer for " + (jpeg ? "JPEG" : "PNG"));
        }
        byte[] bytes = encoded.toByteArray();
        if (info != null) {
            bytes = jpeg ? com.peaknav.utils.ExifWriter.embedInJpeg(bytes, info)
                    : com.peaknav.utils.ExifWriter.embedInPng(bytes, info);
        }
        java.io.FileOutputStream out = new java.io.FileOutputStream(file);
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
    }

    /** Saves a GPX track where the user chooses, the way a snapshot is saved. */
    @Override
    public void shareGpx(final String fileName, final String xml) {
        if (xml == null) {
            return;
        }
        DesktopSwing.onEdt(() -> {
            javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
            chooser.setDialogTitle(s("Save_gpx"));
            chooser.setFileSelectionMode(javax.swing.JFileChooser.FILES_ONLY);
            chooser.setAcceptAllFileFilterUsed(false);
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("GPX (*.gpx)", "gpx"));
            chooser.setSelectedFile(new java.io.File(fileName));
            if (chooser.showSaveDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION
                    || chooser.getSelectedFile() == null) {
                return;
            }
            java.io.File file = chooser.getSelectedFile();
            if (!file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".gpx")) {
                file = new java.io.File(file.getParentFile(), file.getName() + ".gpx");
            }
            if (file.exists() && javax.swing.JOptionPane.showConfirmDialog(null, s("Overwrite_prompt"),
                    s("File_exists"), javax.swing.JOptionPane.YES_NO_OPTION) != javax.swing.JOptionPane.YES_OPTION) {
                return;
            }
            try {
                java.io.OutputStream out = new java.io.FileOutputStream(file);
                try {
                    out.write(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } finally {
                    out.close();
                }
                makeToast(s("Gpx_saved") + ": " + file.getAbsolutePath());
            } catch (java.io.IOException e) {
                javax.swing.JOptionPane.showMessageDialog(null, s("Save_failed_msg") + "\n" + e.getMessage(),
                        s("Save_failed"), javax.swing.JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    /** The toast currently on screen, if any. Only touched on the EDT. */
    private javax.swing.JWindow currentToast;

    @Override
    public void makeToast(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        if (!com.peaknav.viewer.desktop.DesktopSwing.isAvailable()) {
            // No AWT to float a window over the map: the map has a toast of its own, and
            // saying so on the console costs nothing. Deliberately not DesktopSwing.onEdt:
            // a toast is not worth explaining the runtime for, once per toast.
            System.err.println("[Toast] " + message);
            com.badlogic.gdx.Gdx.app.postRunnable(
                    () -> com.peaknav.viewer.MapViewerSingleton.getViewerInstance().toast(message));
            return;
        }
        javax.swing.SwingUtilities.invokeLater(() -> {
            // A burst of toasts (e.g. several provider errors) replaces the previous one instead
            // of stacking identical always-on-top windows on the same spot.
            if (currentToast != null) {
                currentToast.dispose();
                currentToast = null;
            }
            javax.swing.JWindow toast = new javax.swing.JWindow();
            currentToast = toast;
            toast.setAlwaysOnTop(true);
            javax.swing.JLabel label = new javax.swing.JLabel(
                    "<html><body style='width:360px'>" + escapeHtml(message) + "</body></html>");
            label.setForeground(java.awt.Color.WHITE);
            label.setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 16, 12, 16));
            javax.swing.JPanel panel = new javax.swing.JPanel(new java.awt.BorderLayout());
            panel.setBackground(new java.awt.Color(32, 32, 32));
            panel.add(label, java.awt.BorderLayout.CENTER);
            toast.setContentPane(panel);
            toast.pack();

            java.awt.Rectangle screen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
            int x = screen.x + (screen.width - toast.getWidth()) / 2;
            int y = screen.y + screen.height - toast.getHeight() - 80;
            toast.setLocation(x, y);
            toast.setVisible(true);

            // Auto-dismiss so it behaves like an Android toast rather than a dialog to click away.
            javax.swing.Timer timer = new javax.swing.Timer(3500, e -> {
                toast.setVisible(false);
                toast.dispose();
                if (currentToast == toast) {
                    currentToast = null;
                }
            });
            timer.setRepeats(false);
            timer.start();
        });
    }

    @Override
    public void ensureLocationPermissions() {

    }

    @Override
    public void comingSoon() {

    }

    @Override
    public void alertMessage(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        com.peaknav.viewer.desktop.DesktopSwing.onEdt(() -> javax.swing.JOptionPane.showMessageDialog(
                null, message, "PeakNav", javax.swing.JOptionPane.WARNING_MESSAGE));
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override
    public void promptForTextFields(
            String title, String message, String[] labels, String[] initialValues, TextFieldsCallback callback) {
        com.peaknav.viewer.desktop.DesktopSwing.onEdt(() -> {
            javax.swing.JPanel fieldsPanel = new javax.swing.JPanel(
                    new java.awt.GridLayout(labels.length * 2, 1, 0, 2));
            javax.swing.JTextField[] fields = new javax.swing.JTextField[labels.length];
            for (int i = 0; i < labels.length; i++) {
                String initial = (initialValues != null && i < initialValues.length
                        && initialValues[i] != null) ? initialValues[i] : "";
                fields[i] = new javax.swing.JTextField(initial, 40);
                fieldsPanel.add(new javax.swing.JLabel(labels[i]));
                fieldsPanel.add(fields[i]);
            }

            javax.swing.JPanel panel = new javax.swing.JPanel(new java.awt.BorderLayout(0, 8));
            if (message != null && !message.isEmpty()) {
                // A read-only, wrapped area so the (multi-line) token help sits above the fields.
                javax.swing.JTextArea help = new javax.swing.JTextArea(message);
                help.setEditable(false);
                help.setOpaque(false);
                help.setLineWrap(true);
                help.setWrapStyleWord(true);
                help.setBorder(null);
                panel.add(help, java.awt.BorderLayout.NORTH);
            }
            panel.add(fieldsPanel, java.awt.BorderLayout.CENTER);

            int result = javax.swing.JOptionPane.showConfirmDialog(
                    null, panel, title,
                    javax.swing.JOptionPane.OK_CANCEL_OPTION,
                    javax.swing.JOptionPane.PLAIN_MESSAGE);

            if (result != javax.swing.JOptionPane.OK_OPTION) {
                callback.onCancelled();
                return;
            }
            String[] values = new String[fields.length];
            for (int i = 0; i < fields.length; i++) {
                values[i] = fields[i].getText();
            }
            callback.onEntered(values);
        });
    }

    /**
     * The machine's physical memory, which decides how much terrain detail is loaded.
     *
     * <p>This used to answer a flat 3 GB. The detail tiers are chosen with {@code > 3.0 GB}, so
     * every desktop — however much memory it had — landed in the tier meant for the smallest
     * phones and got the coarsest tiles. Ask the OS instead, and fall back to a figure that at
     * least reads as a desktop rather than a low-end handset.
     */
    @Override
    public long getTotalMemory() {
        // Reflection because the accessor was renamed: getTotalPhysicalMemorySize on older JDKs,
        // getTotalMemorySize from 14 on. Neither is on the portable interface.
        java.lang.management.OperatingSystemMXBean bean =
                java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        for (String method : new String[]{"getTotalMemorySize", "getTotalPhysicalMemorySize"}) {
            try {
                java.lang.reflect.Method m = bean.getClass().getMethod(method);
                m.setAccessible(true);
                Object value = m.invoke(bean);
                if (value instanceof Number) {
                    long total = ((Number) value).longValue();
                    if (total > 0) {
                        return total;
                    }
                }
            } catch (Throwable ignored) {
                // Try the next name, then fall back below.
            }
        }
        return 8L * 1024L * 1024L * 1024L;
    }

    private final OrientationPointerListener orientationPointerListener = new OrientationPointerListener() {
        @Override
        public void start() {

        }

        @Override
        public void stop() {

        }
    };

    @Override
    public OrientationPointerListener getOrientationPointerListener() {
        return orientationPointerListener;
    }

}
