package com.peaknav.viewer;


import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.android.AndroidFragmentApplication;
import com.badlogic.gdx.backends.android.AndroidGraphics;
import com.peaknav.singleton.MapViewerAndroidSingleton;

public class MapScreenFragment extends AndroidFragmentApplication {

    private MapApp mapApp;

    public MapScreenFragment() {}

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState)
    {
        mapApp = MapViewerAndroidSingleton.getAppInstance();
        // TODO: restore "return initializeForView(mapApp);"
        View view = initializeForView(mapApp);
        Toast.makeText(getContext(), ("gr width: " + Gdx.graphics.getWidth()), Toast.LENGTH_LONG).show();
        Toast.makeText(getContext(), ("gr height: " + Gdx.graphics.getHeight()), Toast.LENGTH_LONG).show();
        return view;
    }

    public MapApp getMapApp() {
        return mapApp;
    }

}

