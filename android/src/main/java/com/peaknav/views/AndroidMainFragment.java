package com.peaknav.views;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

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
        return initializeForView(mapApp, config);
    }

}
