package com.peaknav.viewer;

import com.badlogic.gdx.math.Vector3;

/**
 * Direction the terrain is lit from, handed to the terrain shader as the {@code u_sunDirection}
 * uniform.
 *
 * <p>The vector points <em>towards</em> the sun, in the same space as the terrain normals:
 * {@code x} east, {@code y} north, {@code z} up. It is always stored normalised, so the shader can
 * use it directly.
 *
 * <p>The default is the one used for relief maps by convention: light from the north west, 45
 * degrees above the horizon. Lighting terrain from the north west is what makes ridges and valleys
 * read correctly — lighting from the south east tends to make people perceive the relief inverted.
 */
public class SunLight {

    /** Compass bearing the light comes from, in degrees: 0 north, 90 east. */
    public static final float DEFAULT_AZIMUTH_DEGREES = 315f;
    /** Height of the sun above the horizon, in degrees. */
    public static final float DEFAULT_ALTITUDE_DEGREES = 45f;

    private final Vector3 direction = new Vector3();

    public SunLight() {
        setFromAzimuthAltitude(DEFAULT_AZIMUTH_DEGREES, DEFAULT_ALTITUDE_DEGREES);
    }

    /**
     * Sets the direction towards the sun. The vector is normalised; a zero vector is ignored so a
     * bad value cannot black out the terrain.
     */
    public void setDirection(float x, float y, float z) {
        if (x == 0f && y == 0f && z == 0f) {
            return;
        }
        direction.set(x, y, z).nor();
    }

    public void setDirection(Vector3 newDirection) {
        if (newDirection == null) {
            return;
        }
        setDirection(newDirection.x, newDirection.y, newDirection.z);
    }

    /**
     * Sets the direction from a compass bearing and a height above the horizon, which is usually
     * an easier way to think about it than a raw vector.
     *
     * @param azimuthDegrees  bearing the light comes from: 0 north, 90 east, 180 south, 270 west
     * @param altitudeDegrees height above the horizon: 0 at the horizon, 90 straight overhead
     */
    public void setFromAzimuthAltitude(float azimuthDegrees, float altitudeDegrees) {
        double azimuth = Math.toRadians(azimuthDegrees);
        double altitude = Math.toRadians(altitudeDegrees);
        double horizontal = Math.cos(altitude);
        setDirection(
                (float) (horizontal * Math.sin(azimuth)),
                (float) (horizontal * Math.cos(azimuth)),
                (float) Math.sin(altitude));
    }

    /** The normalised direction towards the sun. Do not modify the returned instance. */
    public Vector3 getDirection() {
        return direction;
    }
}
