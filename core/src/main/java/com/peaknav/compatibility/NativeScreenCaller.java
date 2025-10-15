package com.peaknav.compatibility;

import static com.peaknav.utils.PeakNavUtils.getC;

import com.badlogic.gdx.graphics.Pixmap;
import com.peaknav.gesture.OrientationPointerListener;
import com.peaknav.ui.ClickCallback;
import com.peaknav.ui.CurrentLocationListener;

public abstract class NativeScreenCaller {

    public abstract void getCallOnUIThread(Runnable runnable);

    public void openMapDataDownloadChooser() {
        openMapDataDownloadChooser(
                getC().L.getTargetLatitude(),
                getC().L.getTargetLongitude(),
                false);
    }

    public abstract void openMapDataDownloadChooser(double lat, double lon, boolean goToAfterDownload);

    public abstract void openMapDataDownloadChooserWizard();

    // public abstract void openScreenMapLocationChoose(double lat, double lon, ClickCallback callback);

    public abstract void openScreenSearchLocation(ClickCallback callback);


    public abstract void openCameraPictureView();

    public abstract void openGalleryPick();

    public abstract void openAppInfoScreen();

    public abstract void openAppTutorial();

    public abstract OrientationPointerListener getOrientationPointerListener();

    public abstract CurrentLocationListener getCurrentLocationListener();

    public abstract void askForDownloadScreen(double lat, double lon);

    public abstract void shareSnapshot(Pixmap pixmap);

    public abstract void makeToast(String message);

    public abstract void ensureLocationPermissions();

    public abstract void comingSoon();
    public abstract void alertMessage(String message);

    public abstract long getTotalMemory();

    // public abstract void setUpBillings();
}
