import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.math.Vector3;
import com.peaknav.viewer.SunLight;

import org.junit.jupiter.api.Test;

/**
 * The sun direction handed to the terrain shader. Terrain space is x east, y north, z up, and the
 * vector points towards the sun.
 */
public class TestSunLight {

    private static final float EPS = 1e-5f;

    @Test
    public void defaultsToTheNorthWestReliefConvention() {
        Vector3 sun = new SunLight().getDirection();
        // 315 degrees means the light comes from the north west: west is negative x, north is
        // positive y. Getting this backwards makes people read ridges as valleys.
        assertTrue(sun.x < 0f, "light should come from the west, so x points west");
        assertTrue(sun.y > 0f, "light should come from the north, so y points north");
        assertTrue(sun.z > 0f, "the sun is above the horizon");
        assertEquals(1f, sun.len(), EPS, "the shader expects a unit vector");
    }

    @Test
    public void azimuthIsACompassBearing() {
        SunLight light = new SunLight();

        light.setFromAzimuthAltitude(0f, 0f);      // due north, on the horizon
        assertEquals(0f, light.getDirection().x, EPS);
        assertEquals(1f, light.getDirection().y, EPS);
        assertEquals(0f, light.getDirection().z, EPS);

        light.setFromAzimuthAltitude(90f, 0f);     // due east
        assertEquals(1f, light.getDirection().x, EPS);
        assertEquals(0f, light.getDirection().y, EPS);

        light.setFromAzimuthAltitude(180f, 0f);    // due south
        assertEquals(-1f, light.getDirection().y, EPS);

        light.setFromAzimuthAltitude(270f, 0f);    // due west
        assertEquals(-1f, light.getDirection().x, EPS);
    }

    @Test
    public void altitudeRaisesTheSun() {
        SunLight light = new SunLight();

        light.setFromAzimuthAltitude(0f, 90f);     // straight overhead
        assertEquals(1f, light.getDirection().z, EPS);
        assertEquals(0f, light.getDirection().x, EPS);
        assertEquals(0f, light.getDirection().y, EPS);

        light.setFromAzimuthAltitude(0f, 45f);
        assertEquals(Math.sqrt(0.5), light.getDirection().z, EPS);
    }

    @Test
    public void directionIsAlwaysNormalised() {
        SunLight light = new SunLight();
        light.setDirection(0f, 0f, 10f);
        assertEquals(1f, light.getDirection().len(), EPS);

        light.setDirection(new Vector3(3f, 4f, 12f));
        assertEquals(1f, light.getDirection().len(), EPS);

        for (float azimuth = 0f; azimuth < 360f; azimuth += 17f) {
            for (float altitude = -80f; altitude <= 80f; altitude += 23f) {
                light.setFromAzimuthAltitude(azimuth, altitude);
                assertEquals(1f, light.getDirection().len(), EPS,
                        "az=" + azimuth + " alt=" + altitude);
            }
        }
    }

    @Test
    public void degenerateInputIsIgnored() {
        SunLight light = new SunLight();
        Vector3 before = new Vector3(light.getDirection());

        light.setDirection(0f, 0f, 0f);
        assertEquals(before, light.getDirection(), "a zero vector would black out the terrain");

        light.setDirection(null);
        assertEquals(before, light.getDirection());
    }
}
