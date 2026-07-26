package com.peaknav.compatibility;

import static com.peaknav.compatibility.PeakNavAppState.getAppState;
import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.s;

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
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.swing.Box;
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

            getAppState().setMapDataDownloadStarted(true);

            missingDataDownloader.setCoords(
                    lat,
                    lon
            );
            missingDataDownloader.doDownload(goToAfterDownload);

            getAppState().setMapDataDownloadStarted(false);
            getAppState().setMapDataDownloaded(true);
        });
    }

    @Override
    public void openMapDataDownloadChooserWizard() {
        getAppState().setMapDataDownloaded(true);
    }

    @Override
    public void openScreenSearchLocation(ClickCallback callback) {
        // mapApp.pause();
        JFrame searchFrame = new JFrame();
        searchFrame.setLayout(null);
        searchFrame.setSize(800, 600);
        searchFrame.setVisible(true);
        searchFrame.setTitle(s("Search"));
        JPanel panel = new JPanel();
        panel.setVisible(true);
        BoxLayout layout = new BoxLayout(panel, BoxLayout.PAGE_AXIS);
        panel.setLayout(layout);
        panel.setBounds(0, 0, 800, 600);
        searchFrame.add(panel);

        JTextField textField = new JTextField("", 1);
        textField.setMaximumSize(new Dimension(300, 65));
        panel.add(textField, BorderLayout.CENTER);
        JButton searchButton = new JButton();
        searchButton.setText(s("Search"));
        searchButton.setSize(new Dimension(150, 50));
        panel.add(searchButton);
        panel.add(Box.createVerticalGlue());

        textField.requestFocus();
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
                    if (cellBounds != null && cellBounds.contains(e.getPoint())) {
                        // String item = model.getElementAt(idx);
                        // System.err.println(item);
                        LuceneGeonameSearch.GeonameResult result = jGeonameResults.get(idx);
                        getC().L.setCurrentTargetCoords(result.lat, result.lon);
                        searchFrame.dispose();
                    }
                }
            }
        });

        panel.add(new JScrollPane(list), BorderLayout.CENTER);

        searchButton.addActionListener(actionEvent -> {
            String searchText = textField.getText();
            List<LuceneGeonameSearch.GeonameResult> geonameResults = getC().luceneGeonameSearch.searchGeoName(searchText);
            model.clear();
            for (LuceneGeonameSearch.GeonameResult gr : geonameResults) {
                model.addElement(gr.getFullName());
            }
            jGeonameResults.clear();
            jGeonameResults.addAll(geonameResults);

            getC().onlineSearch.parseDestinationText(searchText, nominatimResponses -> {
                for (NominatimResponse nominatimResponse : nominatimResponses) {
                    LuceneGeonameSearch.GeonameResult geonameResult = new LuceneGeonameSearch.GeonameResult(
                            nominatimResponse.displayName, nominatimResponse.displayName,
                            nominatimResponse.lat, nominatimResponse.lon, -1
                    );
                    model.addElement(geonameResult.getFullName());
                    jGeonameResults.add(geonameResult);
                }
            });
            MapViewerSingleton.getAppInstance().resume();
            // searchFrame.dispose();
        });
        searchFrame.show();
    }

    @Override
    public void openCameraPictureView() {

    }

    @Override
    public void openGalleryPick() {
        GalleryPickDesktop pick = new GalleryPickDesktop();
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
                getC().L.setCurrentTargetCoords(lat, lon);
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
        Desktop desktop = Desktop.getDesktop();
        try {
            desktop.open(Gdx.files.internal("info/app_info.html").file());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void openAppTutorial() {
        // Just the tutorial slideshow; the keyboard-controls overlay is separate (raised
        // in core when an unbound key is pressed). Desktop has no WebView, so — like
        // openAppInfoScreen — the tutorial is handed to the system browser.
        try {
            Desktop.getDesktop().open(Gdx.files.internal("info/app_tutorial.html").file());
        } catch (IOException e) {
            throw new RuntimeException(e);
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
    public void askForDownloadScreen(double lat, double lon) {
        // System.err.println("Download screen?");

        int dialogResult = JOptionPane.showConfirmDialog(
                null,
                s("Missing_data_prompt"), // message
                s("Missing_data_prompt"), // title
                JOptionPane.YES_NO_OPTION
        );

        if (dialogResult == JOptionPane.YES_OPTION) {
            this.openMapDataDownloadChooser();
        } else if (dialogResult == JOptionPane.NO_OPTION) {
            // Go back to where we were, without re-running the missing-data check: doing that
            // here would pop this very dialog straight back up when the old spot lacks data too.
            getC().L.setCurrentTargetCoords(
                    getC().L.getCurrentLatitude(),
                    getC().L.getCurrentLongitude(),
                    false
            );
        }
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
    private static void savePixmapToFile(Pixmap pixmap, java.io.File file, boolean jpeg)
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
                int a = rgba & 0xFF;
                image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b); // RGB type ignores alpha
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

    @Override
    public void makeToast(String message) {

    }

    @Override
    public void ensureLocationPermissions() {

    }

    @Override
    public void comingSoon() {

    }

    @Override
    public void alertMessage(String message) {

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

    @Override
    public long getTotalMemory() {
        return 3L*1024L*1024L*1024L;
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
