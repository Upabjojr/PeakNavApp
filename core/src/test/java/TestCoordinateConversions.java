import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.math.Vector3;
import com.peaknav.utils.Units;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The world frame and the conversions in and out of it.
 *
 * <p>The app draws in a frame where y is the latitude and x is the longitude scaled by a cosine
 * of latitude, so that a step east and a step north are the same length on screen. The scale
 * therefore belongs to <em>a</em> latitude, and a coordinate is only meaningful together with
 * the latitude it was scaled at.
 *
 * <p>Getting that wrong is not a rounding error. Clicking Mount Rainier from Seattle - 0.75
 * degrees of latitude apart - and recovering the longitude at the wrong latitude moved the
 * result by 1.7 degrees, some 130 km, and the app teleported there the moment the flight
 * animation ended. These tests pin the rule down.
 */
class TestCoordinateConversions {

    private static final double SEATTLE_LAT = 47.6062, SEATTLE_LON = -122.3321;
    private static final double RAINIER_LAT = 46.8523, RAINIER_LON = -121.7603;
    /** Great-circle Seattle to Mount Rainier, for reference. */
    private static final double SEATTLE_TO_RAINIER_KM = 94.5;

    @Test
    @DisplayName("a longitude survives a round trip at the latitude it was scaled at")
    void roundTripsAtTheSameLatitude() {
        for (double lat : new double[]{0, 12.5, 46.8523, -33.87, 69.65, 78.2}) {
            for (double lon : new double[]{0, 7.7491, -121.7603, 174.76, -179.9}) {
                float x = (float) Units.convertLonitsToLatits(lon, lat);
                double back = Units.convertLatitsToLonits(x, (float) lat);
                assertEquals(lon, back, 1e-3,
                        "longitude " + lon + " at latitude " + lat + " must survive the trip");
            }
        }
    }

    @Test
    @DisplayName("recovering a longitude at the wrong latitude is wrong by degrees, not decimals")
    void theWrongLatitudeIsCatastrophic() {
        // A point built in Seattle's frame - which is what marching a ray out from a camera
        // over Seattle produces, whatever latitude the ray lands at.
        float x = (float) Units.convertLonitsToLatits(RAINIER_LON, SEATTLE_LAT);

        double right = Units.convertLatitsToLonits(x, (float) SEATTLE_LAT);
        double wrong = Units.convertLatitsToLonits(x, (float) RAINIER_LAT);

        assertEquals(RAINIER_LON, right, 1e-3, "the frame's own latitude recovers the longitude");
        double errorDegrees = Math.abs(wrong - RAINIER_LON);
        assertTrue(errorDegrees > 1.0,
                "using the point's own latitude instead should be wrong by more than a degree, "
                        + "was " + errorDegrees + " - if this ever gets small, the frame changed "
                        + "and MoveCameraActionStep.end() should be revisited");
        // The exact size of the error, so the number in the bug report stays meaningful.
        assertEquals(1.72, errorDegrees, 0.05, "about 1.7 degrees, some 130 km east");
    }

    @Test
    @DisplayName("distance between two world points is measured in one frame")
    void distanceUsesASingleFrame() {
        // Both points as the app holds them: the camera over Seattle, and a destination
        // produced by marching a ray from it, so both carry Seattle's scale.
        Vector3 camera = new Vector3(
                (float) Units.convertLonitsToLatits(SEATTLE_LON, SEATTLE_LAT),
                (float) SEATTLE_LAT, 0f);
        Vector3 impact = new Vector3(
                (float) Units.convertLonitsToLatits(RAINIER_LON, SEATTLE_LAT),
                (float) RAINIER_LAT, 0f);

        int metres = Units.computeDistanceBetweenWorldVectors(impact, camera);
        assertEquals(SEATTLE_TO_RAINIER_KM, metres / 1000.0, 3.0,
                "Seattle to Mount Rainier is about 94.5 km; reading each point at its own "
                        + "latitude used to report 189 km, and the app showed that to the user");
    }

    @Test
    @DisplayName("the second point defines the frame, so the order carries meaning")
    void theSecondPointDefinesTheFrame() {
        Vector3 camera = new Vector3(
                (float) Units.convertLonitsToLatits(SEATTLE_LON, SEATTLE_LAT),
                (float) SEATTLE_LAT, 0f);
        Vector3 impact = new Vector3(
                (float) Units.convertLonitsToLatits(RAINIER_LON, SEATTLE_LAT),
                (float) RAINIER_LAT, 0f);

        // Deliberately not symmetric: an x is a longitude scaled at one latitude, and the
        // second argument says which. Callers pass the camera there, because that is the
        // frame a ray-marched point was produced in. Averaging the two latitudes instead
        // would be symmetric and wrong - it puts this longitude 0.9 degrees out.
        int fromCamera = Units.computeDistanceBetweenWorldVectors(impact, camera);
        assertEquals(SEATTLE_TO_RAINIER_KM, fromCamera / 1000.0, 3.0,
                "measured in the camera's frame, which is the supported order");

        int swapped = Units.computeDistanceBetweenWorldVectors(camera, impact);
        assertTrue(Math.abs(fromCamera - swapped) < 1_000,
                "the two readings differ, but only by the cosine spread over the latitude "
                        + "difference - hundreds of metres in 94 km, not tens of kilometres");
    }

    @Test
    @DisplayName("height converts between metres and world units both ways")
    void metresRoundTrip() {
        for (double metres : new double[]{0, 20, 600, 3715, 8849, 25000}) {
            float latits = Units.convertMetersToLatits(metres);
            assertEquals(metres, Units.convertLatitsToMeters(latits), 0.5,
                    metres + " m must survive the trip through world units");
        }
    }

    @Test
    @DisplayName("a degree of latitude is about 111 km, a degree of longitude shrinks with it")
    void scalesAreTheExpectedSize() {
        assertEquals(111_194, Units.convertLatitsToMeters(1f), 500,
                "one degree of latitude");
        // At 60 degrees north a degree of longitude is half what it is at the equator.
        assertEquals(0.5, Units.convertLonitsToLatits(1.0, 60.0), 1e-3,
                "a degree of longitude at 60 degrees north is half a degree of latitude");
        assertEquals(1.0, Units.convertLonitsToLatits(1.0, 0.0), 1e-6,
                "on the equator they are the same");
    }
}
