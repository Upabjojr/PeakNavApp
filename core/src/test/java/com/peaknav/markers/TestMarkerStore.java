package com.peaknav.markers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.files.FileHandle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;

public class TestMarkerStore {

    @TempDir
    File folder;

    @Test
    public void markersOutliveTheStoreThatSavedThem() {
        FileHandle file = new FileHandle(new File(folder, MarkerStore.FILE_NAME));
        MarkerStore store = new MarkerStore(file);
        store.add(new Marker("Car <Findeln> & co", 46.0207, 7.7491, 1608.4, 1_790_000_000_000L));
        store.add(new Marker("Spring", 46.0, 7.73, Double.NaN, 0));
        assertTrue(file.exists());
        assertFalse(file.sibling(file.name() + ".tmp").exists(), "the temporary file is left behind");

        List<Marker> read = new MarkerStore(file).getMarkers();
        assertEquals(2, read.size());
        Marker car = read.get(0);
        assertEquals("Car <Findeln> & co", car.name);
        assertEquals(46.0207, car.latitude, 1e-7);
        assertEquals(7.7491, car.longitude, 1e-7);
        assertEquals(1608.4, car.elevation, 0.05);
        assertEquals(1_790_000_000_000L, car.created);
        assertTrue(Double.isNaN(read.get(1).elevation));
        assertEquals(0, read.get(1).created);
        // Any GPX reader sees waypoints.
        String gpx = file.readString("UTF-8");
        assertTrue(gpx.contains("<wpt lat=\"46.0207000\" lon=\"7.7491000\">"), gpx);
        assertTrue(gpx.contains("<name>Car &lt;Findeln&gt; &amp; co</name>"), gpx);
    }

    @Test
    public void aMarkerOnTheSameSpotReplacesTheOldOne() {
        MarkerStore store = new MarkerStore(new FileHandle(new File(folder, MarkerStore.FILE_NAME)));
        store.add(new Marker("Old", 46.0, 7.7, 1000, 0));
        int before = store.getVersion();
        store.add(new Marker("New", 46.0, 7.7, 1000, 0));
        assertTrue(store.getVersion() > before);
        assertEquals(1, store.getMarkers().size());
        assertEquals("New", store.getMarkers().get(0).name);
    }

    @Test
    public void removingAndNaming() {
        MarkerStore store = new MarkerStore(new FileHandle(new File(folder, MarkerStore.FILE_NAME)));
        assertEquals("Marker 1", store.nextDefaultName("Marker"));
        store.add(new Marker("Marker 1", 46.0, 7.7, 1000, 0));
        store.add(new Marker("Marker 3", 46.1, 7.7, 1000, 0));
        assertEquals("Marker 2", store.nextDefaultName("Marker"));
        assertTrue(store.remove(46.0, 7.7));
        assertFalse(store.remove(46.0, 7.7), "removed twice");
        assertEquals(1, new MarkerStore(new FileHandle(new File(folder, MarkerStore.FILE_NAME))).getMarkers().size());
    }

    @Test
    public void aDamagedWaypointCostsOnlyItself() {
        List<Marker> read = MarkerStore.parse("<gpx><wpt lat=\"x\" lon=\"7\"/><wpt lat=\"46\" lon=\"7\"><name>Ok</name></wpt></gpx>");
        assertEquals(1, read.size());
        assertEquals("Ok", read.get(0).name);
    }
}
