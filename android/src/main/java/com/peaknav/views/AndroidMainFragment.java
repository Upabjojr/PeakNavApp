package com.peaknav.views;

import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.badlogic.gdx.backends.android.AndroidFragmentApplication;
import com.peaknav.singleton.MapViewerAndroidSingleton;
import com.peaknav.viewer.MapApp;

public class AndroidMainFragment extends AndroidFragmentApplication {
    private MapApp mapApp;

    /**
     * Required by the framework. When the activity is recreated from saved state (the process was
     * killed in the background and the user taps the icon again), the FragmentManager
     * re-instantiates this fragment reflectively through the no-arg constructor — inside
     * {@code super.onCreate(savedInstanceState)}, before AndroidLauncher gets a chance to replace
     * it. Without this constructor that restore crashed with a Fragment.InstantiationException,
     * which is why the app sometimes closed immediately on the first tap and only started on the
     * second (the second launch has no saved state). The restored instance is replaced with a
     * fresh one right away; should its view ever be created anyway, the app instance is taken
     * from the singleton below.
     */
    public AndroidMainFragment() {
    }

    public AndroidMainFragment(MapApp mapApp) {
        this.mapApp = mapApp;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (mapApp == null) {
            mapApp = MapViewerAndroidSingleton.getAppInstance();
        }
        AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        config.useAccelerometer = true;
        config.useCompass = true;
        View view = initializeForView(mapApp, config);
        limitRefreshRateTo60();
        return view;
    }

    /**
     * Ask for a 60 Hz display mode on high-refresh panels. libGDX renders continuously, so on a
     * 120 Hz screen the GPU redraws an often-static scene 120 times a second — measured 44–99%
     * GPU load on a Galaxy A35 with the camera idle — for no visual gain. The desktop build is
     * likewise capped at 60 fps. On 60 Hz-only devices every branch below is a no-op.
     */
    private void limitRefreshRateTo60() {
        Window window = requireActivity().getWindow();
        WindowManager.LayoutParams attrs = window.getAttributes();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Display display = requireActivity().getWindowManager().getDefaultDisplay();
            Display.Mode current = display.getMode();
            Display.Mode slowest = null;
            for (Display.Mode mode : display.getSupportedModes()) {
                // Same resolution only: a mode switch that also rescaled the surface would
                // restart the GL context for a refresh-rate tweak.
                if (mode.getPhysicalWidth() == current.getPhysicalWidth()
                        && mode.getPhysicalHeight() == current.getPhysicalHeight()
                        && mode.getRefreshRate() >= 59f
                        && (slowest == null || mode.getRefreshRate() < slowest.getRefreshRate())) {
                    slowest = mode;
                }
            }
            if (slowest != null) {
                attrs.preferredDisplayModeId = slowest.getModeId();
            }
        } else {
            attrs.preferredRefreshRate = 60f;
        }
        window.setAttributes(attrs);
    }
}
