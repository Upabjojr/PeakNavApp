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
            getC().L.setCurrentTargetCoords(
                    getC().L.getCurrentLatitude(),
                    getC().L.getCurrentLongitude()
            );
        }
    }

    @Override
    public void shareSnapshot(Pixmap pixmap) {

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
