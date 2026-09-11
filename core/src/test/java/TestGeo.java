import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.geo.BoundingBox;
import com.peaknav.geo.LatLong;
import com.peaknav.geo.MercatorProjection;
import com.peaknav.geo.Tile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

/** The map's own geometry: coordinates, boxes, and the web Mercator tile grid. */
class TestGeo {

    @Test
    @DisplayName("the map ends where the Mercator world is square, about 85.0511 degrees")
    void mapEdge() {
        assertEquals(85.05112877980659, MercatorProjection.LATITUDE_MAX, 1e-12);
        BoundingBox world = new Tile(0, 0, (byte) 0, 256).getBoundingBox();
        assertEquals(-180.0, world.minLongitude, 0.0);
        assertEquals(180.0, world.maxLongitude, 0.0);
        assertEquals(MercatorProjection.LATITUDE_MAX, world.maxLatitude, 1e-12);
        assertEquals(-MercatorProjection.LATITUDE_MAX, world.minLatitude, 1e-12);
        BoundingBox southEast = new Tile(1, 1, (byte) 1, 256).getBoundingBox();
        assertEquals(0.0, southEast.maxLatitude, 1e-12, "zoom 1 splits the world at the equator");
        assertEquals(0.0, southEast.minLongitude, 0.0, "and at Greenwich");
    }

    @Test
    @DisplayName("every point on the map lies in the tile it is said to be in, at every zoom level")
    void pointsFallInTheirTiles() {
        Random r = new Random(7);
        for (int i = 0; i < 20000; i++) {
            double lat = (r.nextDouble() * 2 - 1) * 85.0;
            double lon = r.nextDouble() * 360 - 180;
            byte z = (byte) r.nextInt(21);
            Tile t = new Tile(MercatorProjection.longitudeToTileX(lon, z),
                    MercatorProjection.latitudeToTileY(lat, z), z, 256);
            assertTrue(t.getBoundingBox().contains(lat, lon), lat + "," + lon + " not in " + t);
        }
    }

    @Test
    @DisplayName("positions off the map give the nearest tile, the poles and 180 degrees included")
    void clamping() {
        byte z = 3;
        assertEquals(7, MercatorProjection.longitudeToTileX(180, z));
        assertEquals(0, MercatorProjection.longitudeToTileX(-180, z));
        assertEquals(7, MercatorProjection.longitudeToTileX(200, z));
        assertEquals(0, MercatorProjection.latitudeToTileY(90, z));
        assertEquals(7, MercatorProjection.latitudeToTileY(-90, z));
        assertEquals(0, MercatorProjection.latitudeToTileY(85.2, z));
        // Zermatt, the tile the road tests use.
        assertEquals(534, MercatorProjection.longitudeToTileX(7.75, (byte) 10));
        assertEquals(364, MercatorProjection.latitudeToTileY(46.02, (byte) 10));
    }

    @Test
    @DisplayName("parents and neighbours: zoom 0 has no parent, and the grid wraps round")
    void family() {
        assertNull(new Tile(0, 0, (byte) 0, 256).getParent());
        assertEquals(new Tile(2, 3, (byte) 3, 256), new Tile(5, 6, (byte) 4, 256).getParent());
        assertEquals(new Tile(7, 3, (byte) 3, 256), new Tile(0, 3, (byte) 3, 256).getLeft());
        assertEquals(new Tile(0, 3, (byte) 3, 256), new Tile(7, 3, (byte) 3, 256).getRight());
        assertEquals(new Tile(3, 7, (byte) 3, 256), new Tile(3, 0, (byte) 3, 256).getAbove());
        assertEquals(new Tile(3, 0, (byte) 3, 256), new Tile(3, 7, (byte) 3, 256).getBelow());
        assertEquals(1, new Tile(5, 6, (byte) 4, 1).getParent().tileSize, "the size is inherited");
    }

    @Test
    @DisplayName("a tile's identity includes its size; its name is x, y and z")
    void identity() {
        Tile a = new Tile(1, 2, (byte) 3, 256);
        assertEquals(a, new Tile(1, 2, (byte) 3, 256));
        assertEquals(a.hashCode(), new Tile(1, 2, (byte) 3, 256).hashCode());
        assertNotEquals(a, new Tile(1, 2, (byte) 3, 1));
        assertEquals("x=1, y=2, z=3", a.toString());
        assertEquals(a.getBoundingBox(), new Tile(1, 2, (byte) 3, 1).getBoundingBox(),
                "the ground covered does not depend on the size");
    }

    @Test
    @DisplayName("tiles, points and boxes off the map are refused")
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> new Tile(8, 0, (byte) 3, 256));
        assertThrows(IllegalArgumentException.class, () -> new Tile(0, -1, (byte) 3, 256));
        assertThrows(IllegalArgumentException.class, () -> new Tile(0, 0, (byte) -1, 256));
        assertThrows(IllegalArgumentException.class, () -> new LatLong(91, 0));
        assertThrows(IllegalArgumentException.class, () -> new LatLong(0, -180.5));
        assertThrows(IllegalArgumentException.class, () -> new LatLong(Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> new BoundingBox(1, 2, 0, 3));
        assertThrows(IllegalArgumentException.class, () -> new BoundingBox(0, 3, 1, 2));
    }

    @Test
    @DisplayName("distances: in flat degrees, and in metres along the Earth")
    void distances() {
        assertEquals(5.0, new LatLong(1, 1).distance(new LatLong(4, 5)), 1e-12);
        // A degree of longitude on the equator, on a sphere of the WGS 84 equatorial radius.
        assertEquals(111319.49079327357, new LatLong(0, 0).sphericalDistance(new LatLong(0, 1)), 1e-6);
        assertEquals(Math.PI * LatLong.EARTH_RADIUS_METERS,
                new LatLong(0, 0).sphericalDistance(new LatLong(0, 180)), 1e-6, "half the equator");
        assertEquals(0.0, new LatLong(46, 7).sphericalDistance(new LatLong(46, 7)), 0.0);
    }

    @Test
    @DisplayName("boxes: edges are inside, touching boxes meet, and two boxes extend to their union")
    void boxes() {
        BoundingBox b = new BoundingBox(1, 2, 3, 4);
        assertTrue(b.contains(new LatLong(3, 4)));
        assertTrue(b.contains(1, 2));
        assertFalse(b.contains(3.0000001, 3));
        assertEquals(new LatLong(2, 3), b.getCenterPoint());
        assertTrue(b.intersects(new BoundingBox(3, 4, 5, 6)));
        assertFalse(b.intersects(new BoundingBox(3.1, 4, 5, 6)));
        assertEquals(new BoundingBox(0, 2, 3, 6), b.extendBoundingBox(new BoundingBox(0, 3, 2, 6)));
    }
}
