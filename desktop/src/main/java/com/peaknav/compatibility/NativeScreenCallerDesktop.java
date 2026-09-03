package com.peaknav.compatibility;

import static com.peaknav.compatibility.PeakNavAppState.getAppState;
import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.s;
import static com.peaknav.utils.PreferencesManager.P;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.peaknav.database.LuceneGeonameSearch;
import com.peaknav.database.MissingDataDownloader;
import com.peaknav.gesture.OrientationPointerListener;
import com.peaknav.network.NominatimResponse;
import com.peaknav.ui.ClickCallback;
import com.peaknav.ui.CurrentLocationCallback;
import com.peaknav.ui.CurrentLocationListener;
import com.peaknav.ui.TextFieldsCallback;
import com.peaknav.viewer.MapViewerSingleton;
import com.peaknav.viewer.desktop.GalleryPickDesktop;
import com.peaknav.viewer.desktop.MapViewerDesktopSingleton;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import java.awt.Component;
import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.JLabel;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

public class NativeScreenCallerDesktop extends NativeScreenCaller {

    private final List<LuceneGeonameSearch.GeonameResult> jGeonameResults = new ArrayList<>();

    @Override
    public void getCallOnUIThread(Runnable runnable) {
        runnable.run();
    }

    @Override
    public void openMapDataDownloadChooser(double lat, double lon, boolean goToAfterDownload) {
        getC().submitExecutorGeneric(() -> {

            MissingDataDownloader missingDataDownloader = getC().missingDataDownloader;
            missingDataDownloader.setCoords(lat, lon);

            // The started flag suppresses the missing-data prompt while a download runs
            // (CurrentLocation.shouldAskToDownloadMissingData). It MUST be cleared on every
            // exit path: left set, the prompt never appears again for the whole session.
            getAppState().setMapDataDownloadStarted(true);
            try {
                missingDataDownloader.doDownload(goToAfterDownload);
            } finally {
                getAppState().setMapDataDownloadStarted(false);
            }
            getAppState().setMapDataDownloaded(true);
        });
    }

    @Override
    public void openMapDataDownloadChooserWizard() {
        // This used to only setMapDataDownloaded(true): the intro screen's "download data"
        // button marked the data as present without fetching a single byte, which is why the
        // desktop app "could not download map data" - it never tried. Android opens a
        // region-chooser wizard here; the desktop has no such screen, so do the honest
        // minimum instead: actually download for the current target location. The intro
        // button sets the download consent before calling this, so the workers really fetch.
        getC().submitExecutorGeneric(() -> {
            if (!getC().L.isCurrentLocationNotSet()) {
                double lat = getC().L.getTargetLatitude();
                double lon = getC().L.getTargetLongitude();
                MissingDataDownloader missingDataDownloader = getC().missingDataDownloader;
                missingDataDownloader.setCoords(lat, lon);
                // Cleared in finally, or this suppresses the missing-data prompt for the
                // rest of the session - which is exactly the bug this once caused.
                getAppState().setMapDataDownloadStarted(true);
                try {
                    missingDataDownloader.doDownload(false);
                } finally {
                    getAppState().setMapDataDownloadStarted(false);
                }
            }
            // Lets the intro proceed either way; with no location set yet there is nothing
            // sensible to fetch, and the missing-data prompt takes over once one is chosen.
            getAppState().setMapDataDownloaded(true);
        });
    }

    /**
     * Distinguishes the latest search from earlier ones, so a slow online (Nominatim) response
     * arriving after the user has already searched again cannot interleave stale rows into the
     * list (clicking a row would then navigate to the wrong place). Only touched on the EDT.
     */
    private int searchGeneration = 0;

    /**
     * The search window while it is open, so a second click raises it instead of building
     * another one. Clicking the button twice used to leave two identical windows stacked,
     * each with its own result list, and typing into the one on top searched in a window
     * the user could no longer see. Only touched on the EDT.
     */
    private JFrame openSearchFrame;

    @Override
    public void openScreenSearchLocation(ClickCallback callback) {
        // The whole window is built on the EDT (this method is called from the GL render thread;
        // constructing Swing UI there is undefined behaviour and deadlock-prone on macOS).
        SwingUtilities.invokeLater(() -> {
            if (openSearchFrame != null) {
                // One search window, and clicking the button again is a request to SEE it:
                // un-minimised, above the map, with the caret back in the search box.
                com.peaknav.viewer.desktop.WindowRaiser.bringToFront(openSearchFrame);
                return;
            }
            JFrame searchFrame = new JFrame();
            openSearchFrame = searchFrame;
            // Closing it - by the window button, by Escape, or by picking a result - must
            // release the slot above, or search would open once per session and never again.
            searchFrame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            searchFrame.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosed(java.awt.event.WindowEvent event) {
                    openSearchFrame = null;
                }
            });
            searchFrame.setLayout(null);
            searchFrame.setSize(800, 600);
            searchFrame.setTitle(s("Search_place_title"));
            JPanel panel = new JPanel();
            BoxLayout layout = new BoxLayout(panel, BoxLayout.PAGE_AXIS);
            panel.setLayout(layout);
            panel.setBounds(0, 0, 800, 600);
            panel.setBorder(new EmptyBorder(12, 12, 12, 12));
            searchFrame.add(panel);

            // The pane used to be a bare text field over an unlabeled list - nothing said
            // what to type or what the list was for. Each part now announces itself.
            JLabel promptLabel = new JLabel(s("Search_prompt"));
            promptLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(promptLabel);
            panel.add(Box.createVerticalStrut(6));

            JTextField textField = new JTextField("", 1);
            textField.setMaximumSize(new Dimension(300, 65));
            textField.setAlignmentX(Component.LEFT_ALIGNMENT);
            textField.setToolTipText(s("Search_prompt"));
            panel.add(textField, BorderLayout.CENTER);
            JButton searchButton = new JButton();
            searchButton.setText(s("Search"));
            searchButton.setSize(new Dimension(150, 50));
            searchButton.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(searchButton);
            panel.add(Box.createVerticalStrut(12));

            JLabel resultsLabel = new JLabel(s("Search_results_hint"));
            resultsLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(resultsLabel);
            panel.add(Box.createVerticalStrut(4));
            // No vertical glue here: it would expand between the label and the result list
            // below it; the scroll pane itself takes the remaining height.

            SwingUtilities.getRootPane(searchButton).setDefaultButton(searchButton);

            DefaultListModel<String> model = new DefaultListModel<>();

            JList<String> list = new JList<>(model);
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setVisibleRowCount(10);
            list.setFixedCellHeight(28);
            list.setBorder(new EmptyBorder(6, 6, 6, 6));

            list.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    int idx = list.locationToIndex(e.getPoint());
                    if (idx != -1) {
                        Rectangle cellBounds = list.getCellBounds(idx, idx);
                        if (cellBounds != null && cellBounds.contains(e.getPoint())
                                && idx < jGeonameResults.size()) {
                            LuceneGeonameSearch.GeonameResult result = jGeonameResults.get(idx);
                            // Core-state mutation (tile updates, missing-data checks) belongs on
                            // the GL thread, not the EDT.
                            Gdx.app.postRunnable(
                                    () -> getC().L.setCurrentTargetCoords(result.lat, result.lon));
                            searchFrame.dispose();
                        }
                    }
                }
            });

            JScrollPane resultsPane = new JScrollPane(list);
            resultsPane.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(resultsPane, BorderLayout.CENTER);

            searchButton.addActionListener(actionEvent -> {
                final int generation = ++searchGeneration;
                String searchText = textField.getText();
                List<LuceneGeonameSearch.GeonameResult> geonameResults = getC().luceneGeonameSearch.searchGeoName(searchText);
                model.clear();
                for (LuceneGeonameSearch.GeonameResult gr : geonameResults) {
                    model.addElement(gr.getFullName());
                }
                jGeonameResults.clear();
                jGeonameResults.addAll(geonameResults);

                // The callback arrives on a network thread: the list model and the shared result
                // list may only be touched on the EDT, and only if no newer search superseded us.
                getC().onlineSearch.parseDestinationText(searchText,
                        nominatimResponses -> SwingUtilities.invokeLater(() -> {
                    if (generation != searchGeneration) {
                        return; // stale response of an earlier search
                    }
                    for (NominatimResponse nominatimResponse : nominatimResponses) {
                        LuceneGeonameSearch.GeonameResult geonameResult = new LuceneGeonameSearch.GeonameResult(
                                nominatimResponse.displayName, nominatimResponse.displayName,
                                nominatimResponse.lat, nominatimResponse.lon, -1
                        );
                        model.addElement(geonameResult.getFullName());
                        jGeonameResults.add(geonameResult);
                    }
                }));
                MapViewerSingleton.getAppInstance().resume();
            });
            // Escape closes the panel, from anywhere inside it - WHEN_IN_FOCUSED_WINDOW, so
            // it works while the caret is in the search box, which is where it always is.
            // Bound to Escape alone: Delete has to keep deleting characters as one types.
            searchFrame.getRootPane().registerKeyboardAction(
                    closeEvent -> searchFrame.dispose(),
                    KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                    JComponent.WHEN_IN_FOCUSED_WINDOW);

            searchFrame.setVisible(true);
            textField.requestFocus();
        });
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
        SwingUtilities.invokeLater(() -> {
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
        });
    }

    @Override
    public void promptGoToImageLocation(double lat, double lon) {
        SwingUtilities.invokeLater(() -> {
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
        SwingUtilities.invokeLater(() -> {
            int dialogResult = JOptionPane.showConfirmDialog(
                    null, message, title, JOptionPane.YES_NO_OPTION);
            if (dialogResult == JOptionPane.YES_OPTION) {
                onYes.run();
            }
        });
    }

    @Override
    public void warnCannotReadImageLocation() {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                null,
                s("Image_location_missing"),
                s("Image_location_missing_title"),
                JOptionPane.WARNING_MESSAGE));
    }

    @Override
    public void openAppInfoScreen() {
        openBundledHtml("info/app_info.html");
    }

    @Override
    public void openAppTutorial() {
        // Just the tutorial slideshow; the keyboard-controls overlay is separate (raised
        // in core when an unbound key is pressed). Desktop has no WebView, so — like
        // openAppInfoScreen — the tutorial is handed to the system browser.
        //
        // The screenshots have to be named explicitly: the page references them with
        // relative URLs, and a FileHandle inside a jar cannot list its own directory,
        // so there is no way to discover them at runtime.
        openBundledHtml("info/app_tutorial.html",
                "imageBase.jpg", "imageOptions.jpg", "imageOptionsSat.jpg", "imageBaseSat.jpg");
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
        try {
            com.badlogic.gdx.Gdx.net.openURI(url);
        } catch (Exception e) {
            alertMessage(url);
        }
    }

    private final CurrentLocationListener currentLocationListener = new CurrentLocationListener() {
        @Override
        public void getCurrentLocation(CurrentLocationCallback currentLocationCallback) {

        }
    };

    @Override
    public CurrentLocationListener getCurrentLocationListener() {
        return currentLocationListener;
    }

    @Override
    public void chooseSkyTime() {
        SwingUtilities.invokeLater(() -> {
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
        SwingUtilities.invokeLater(() -> {
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
                this.openMapDataDownloadChooser();
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
    public void shareSnapshot(Pixmap pixmap) {
        if (pixmap == null) {
            return;
        }
        // Default file name carries a timestamp so successive shots don't collide.
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
        final String defaultName = "PeakNav_" + stamp + ".png";
        SwingUtilities.invokeLater(() -> {
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
                        savePixmapToFile(pixmap, target, asJpeg);
                        SwingUtilities.invokeLater(() -> javax.swing.JOptionPane.showMessageDialog(
                                null, s("Image_saved") + ":\n" + target.getAbsolutePath()));
                    } catch (Exception e) {
                        SwingUtilities.invokeLater(() -> javax.swing.JOptionPane.showMessageDialog(
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
        if (!javax.imageio.ImageIO.write(image, jpeg ? "jpg" : "png", file)) {
            throw new java.io.IOException("no ImageIO writer for " + (jpeg ? "JPEG" : "PNG"));
        }
    }

    /** The toast currently on screen, if any. Only touched on the EDT. */
    private javax.swing.JWindow currentToast;

    @Override
    public void makeToast(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        javax.swing.SwingUtilities.invokeLater(() -> {
            if (java.awt.GraphicsEnvironment.isHeadless()) {
                System.err.println("[Toast] " + message);
                return;
            }
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
        javax.swing.SwingUtilities.invokeLater(() -> javax.swing.JOptionPane.showMessageDialog(
                null, message, "PeakNav", javax.swing.JOptionPane.WARNING_MESSAGE));
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override
    public void promptForTextFields(
            String title, String message, String[] labels, String[] initialValues, TextFieldsCallback callback) {
        javax.swing.SwingUtilities.invokeLater(() -> {
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
