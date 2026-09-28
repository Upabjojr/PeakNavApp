package com.peaknav.gesture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.Vector3;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The twist's limit measures the roll, whatever the pitch. */
class TestRollLimit {

    private static PerspectiveCamera looking(float pitchDeg, float rollDeg) {
        PerspectiveCamera camera = new PerspectiveCamera();
        double pitch = Math.toRadians(pitchDeg);
        camera.direction.set(0f, (float) Math.cos(pitch), (float) Math.sin(pitch));
        Vector3 right = camera.direction.cpy().crs(Vector3.Z).nor();
        camera.up.set(right.cpy().crs(camera.direction).nor());
        camera.rotateAround(Vector3.Zero, camera.direction, rollDeg);
        return camera;
    }

    @Test
    @DisplayName("a level camera has no roll at any pitch")
    void levelAtAnyPitch() {
        for (float pitch : new float[]{-60f, -20f, -6f, 0f, 6f, 20f, 60f}) {
            assertEquals(0f, MountainInputController.rollDegrees(looking(pitch, 0f)), 0.01f, "pitch " + pitch);
        }
    }

    @Test
    @DisplayName("the roll is measured as itself, looking up or down alike")
    void rollAtAnyPitch() {
        for (float pitch : new float[]{-45f, 0f, 30f}) {
            assertEquals(4f, MountainInputController.rollDegrees(looking(pitch, 4f)), 0.01f, "pitch " + pitch);
            assertEquals(10f, MountainInputController.rollDegrees(looking(pitch, -10f)), 0.01f, "pitch " + pitch);
        }
    }
}
