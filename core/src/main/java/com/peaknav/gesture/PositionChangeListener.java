package com.peaknav.gesture;

import com.badlogic.gdx.math.Vector3;

public interface PositionChangeListener {

    void onCameraPositionChanged(Vector3 position);

    void onZoomChanged(float fieldOfView);

    void onCameraDirectionChanged(Vector3 direction, Vector3 up);

}
