import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.elevation.ElevationUtils;
import com.peaknav.utils.Units;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Earth-curvature correction that makes distant terrain sink below the flat world's
 * plane - checked against independent geometry, not against its own output.
 *
 * <p>The correction was written by hand and never verified; the errors such a formula
 * can hide are a factor of two (radius for diameter), a unit slip (latits, metres,
 * radians and degrees all appear within three lines), a sign, or the wrong horizontal
 * metric. Each would move mountains - a factor of two at 100 km is the height of a
 * telecom tower, at 400 km it is half a Matterhorn.
 */
class TestRoundEarthCurvature {

    /** One latit in metres: one degree of latitude. */
    private static final double LATIT_M = 2 * Math.PI * 6371000.0 / 360.0;

    /** The drop in metres for a point d metres due north of the reference. */
    private static double dropMetersNorth(double dMeters, float refLat) {
        float dLat = (float) (dMeters / LATIT_M);
        return Units.convertLatitsToMeters(
                ElevationUtils.roundEarthDropLatits(dLat, 0f, refLat));
    }

    @Test
    @DisplayName("the textbook figures: ~8 m at 10 km, ~785 m at 100 km, ~3.1 km at 200 km")
    void matchesTheSurveyorsNumbers() {
        // d²/2R, the figure every surveying handbook quotes. A factor-of-two or a
        // wrong-unit error lands hundreds of metres away from these bands.
        assertEquals(7.85, dropMetersNorth(10_000, 46f), 0.05);
        assertEquals(196.2, dropMetersNorth(50_000, 46f), 0.6);
        assertEquals(785.1, dropMetersNorth(100_000, 46f), 1.5);
        assertEquals(3141.6, dropMetersNorth(200_000, 46f), 6.0);
        assertEquals(0.0, dropMetersNorth(0, 46f), 1e-6, "no drop at the reference");
    }

    @Test
    @DisplayName("agrees with the exact spherical drop, R(1-cos θ), to fourth order")
    void agreesWithIndependentSphereGeometry() {
        // Derived independently: a surface point at angular distance θ lies
        // R(1-cos θ) below the tangent plane at the reference. The code uses
        // R(sec θ - 1); the two must agree to θ⁴ - tightly at label distances,
        // loosely at the far plane. Sign errors, swapped trig, or a doubled
        // radius fail this at every distance.
        for (double km : new double[]{10, 50, 100, 200, 400}) {
            double theta = km * 1000 / 6371000.0;
            double exact = 6371000.0 * (1 - Math.cos(theta));
            double code = dropMetersNorth(km * 1000, 0f);
            double tolerance = Math.max(0.5, exact * theta * theta);
            assertEquals(exact, code, tolerance, "drop at " + km + " km");
        }
    }

    @Test
    @DisplayName("grows with the square of distance, as curvature must")
    void quadraticInDistance() {
        double d100 = dropMetersNorth(100_000, 30f);
        double d200 = dropMetersNorth(200_000, 30f);
        double d400 = dropMetersNorth(400_000, 30f);
        assertEquals(4.0, d200 / d100, 0.01, "doubling the distance quadruples the drop");
        assertEquals(4.0, d400 / d200, 0.02);
    }

    @Test
    @DisplayName("north, south, east and west of the reference all sink alike")
    void symmetricAroundTheReference() {
        float d = 1.5f; // degrees
        float north = ElevationUtils.roundEarthDropLatits(d, 0, 0f);
        float south = ElevationUtils.roundEarthDropLatits(-d, 0, 0f);
        float east = ElevationUtils.roundEarthDropLatits(0, d, 0f);
        float west = ElevationUtils.roundEarthDropLatits(0, -d, 0f);
        assertEquals(north, south, 1e-7);
        // At the equator a degree of longitude is a degree of latitude.
        assertEquals(north, east, north * 1e-4f);
        assertEquals(east, west, 1e-7);
    }

    @Test
    @DisplayName("east-west uses the same shrunken metric the renderer places vertices with")
    void eastWestMatchesTheWorldsOwnProjection() {
        // The flat world puts a vertex at x = lon·cos(refLat). Two points at the same
        // WORLD distance - one north, one east - must sink equally, or a label would
        // part company with the mesh under it. At 60° north, cos = 0.5: two degrees of
        // longitude is one world unit, like one degree of latitude.
        float refLat = 60f;
        float north = ElevationUtils.roundEarthDropLatits(1.0f, 0, refLat);
        float east = ElevationUtils.roundEarthDropLatits(0, 2.0f, refLat);
        assertEquals(north, east, north * 2e-3f,
                "equal world-plane distances must produce equal drops");
    }

    @Test
    @DisplayName("the sign and scale are what the callers assume when they subtract it")
    void positiveDownAndInLatits() {
        // Both call sites do `elevation - dz`: the value must be positive away from
        // the reference, and in latits - at 100 km the drop is ~785 m, which is
        // 0.00706 latits. A value near 785 here would mean the function already
        // returned metres and every mountain would be buried 111 thousand times
        // too deep.
        float dz = ElevationUtils.roundEarthDropLatits(0.8993f, 0, 46f);
        assertTrue(dz > 0, "the correction is a drop, subtracted by its callers");
        assertEquals(0.00706f, dz, 0.0001f, "and it is in latits, not metres");
    }
}
