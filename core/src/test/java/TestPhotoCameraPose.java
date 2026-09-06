import static org.junit.jupiter.api.Assertions.assertEquals;

import com.badlogic.gdx.math.Vector3;
import com.peaknav.viewer.PhotoSkylineAligner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The camera vectors a matched pose turns into: direction from bearing and pitch, up from
 * the roll. What matters is that the roll is applied in the matcher's convention, so the
 * terrain rendered with that camera lands where the matcher predicted it on the photo.
 */
class TestPhotoCameraPose {

    @Test
    @DisplayName("bearing and pitch give the east-north-up direction")
    void direction() {
        Vector3 north = PhotoSkylineAligner.cameraDirection(0f, 0f);
        assertEquals(0f, north.x, 1e-6f);
        assertEquals(1f, north.y, 1e-6f);
        assertEquals(0f, north.z, 1e-6f);
        Vector3 eastUp = PhotoSkylineAligner.cameraDirection(90f, 30f);
        assertEquals(Math.cos(Math.toRadians(30)), eastUp.x, 1e-6);
        assertEquals(0f, eastUp.y, 1e-6f);
        assertEquals(Math.sin(Math.toRadians(30)), eastUp.z, 1e-6, "positive pitch looks up");
    }

    @Test
    @DisplayName("no roll keeps the camera upright at any pitch")
    void levelUp() {
        for (float pitch : new float[]{-40f, 0f, 25f}) {
            Vector3 d = PhotoSkylineAligner.cameraDirection(210f, pitch);
            Vector3 up = PhotoSkylineAligner.cameraUp(d, 0f);
            assertEquals(0f, up.dot(d), 1e-6f, "up is perpendicular to the direction");
            assertEquals(0f, up.dot(d.cpy().crs(0f, 0f, 1f).nor()), 1e-6f, "up has no sideways component");
            assertEquals(1f, up.len(), 1e-6f);
            assertEquals(0f, PhotoSkylineAligner.cameraRollDeg(d, up), 1e-4f);
        }
    }

    @Test
    @DisplayName("a positive roll tilts the horizon clockwise in the picture, as the matcher means it")
    void rollSign() {
        // Looking north and level: right is east, up is skyward. Rolling the camera so the
        // horizon appears to tilt clockwise means the camera itself turns anticlockwise
        // (seen from behind): its right vector rises, its up vector leans west.
        Vector3 d = PhotoSkylineAligner.cameraDirection(0f, 0f);
        Vector3 up = PhotoSkylineAligner.cameraUp(d, 10f);
        assertEquals(-Math.sin(Math.toRadians(10)), up.x, 1e-6, "up leans west");
        assertEquals(Math.cos(Math.toRadians(10)), up.z, 1e-6);
        Vector3 right = d.cpy().crs(up).nor();
        assertEquals(Math.sin(Math.toRadians(10)), right.z, 1e-6, "right rises");
        // The same construction as SkylineMatcher.projectHorizon: right' = c r + s u.
    }

    @Test
    @DisplayName("the roll read back from the camera is the roll applied")
    void rollRoundTrip() {
        float[][] poses = {{0f, 0f, 7f}, {123f, 15f, -12f}, {270f, -30f, 3.5f}, {45f, 55f, -14f}};
        for (float[] pose : poses) {
            Vector3 d = PhotoSkylineAligner.cameraDirection(pose[0], pose[1]);
            Vector3 up = PhotoSkylineAligner.cameraUp(d, pose[2]);
            assertEquals(0f, up.dot(d), 1e-5f, "up stays perpendicular to the direction");
            assertEquals(pose[2], PhotoSkylineAligner.cameraRollDeg(d, up), 1e-3f,
                    "roll for bearing " + pose[0] + " pitch " + pose[1]);
        }
    }
}
