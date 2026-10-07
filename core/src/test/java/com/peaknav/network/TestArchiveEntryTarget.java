package com.peaknav.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** An archive's entries land inside the data folder, or the archive is refused. */
class TestArchiveEntryTarget {

    @Test
    @DisplayName("the entries the archives carry land where they always did")
    void ordinaryNames(@TempDir Path dir) throws IOException {
        File base = dir.toFile();
        assertEquals(new File(base, "elev_tiles/zoom_08/x_00135/y_00061/elev.z08.x00135.y00061.f000.jpg"),
                PeakNavDownloadManager.entryTarget(base, "elev_tiles/zoom_08/x_00135/y_00061/elev.z08.x00135.y00061.f000.jpg"));
        assertEquals(new File(base, "map_folder/PBF_POI/zoom_09"),
                PeakNavDownloadManager.entryTarget(base, "map_folder/PBF_POI/zoom_09/"));
        assertEquals(new File(base, "map_folder/a.osm.pbf"),
                PeakNavDownloadManager.entryTarget(base, "./map_folder/x/../a.osm.pbf"));
    }

    @Test
    @DisplayName("names that lead out of the folder are refused")
    void namesLeadingOut(@TempDir Path dir) {
        File base = dir.resolve("data").toFile();
        base.mkdirs();
        for (String name : new String[]{"../evil", "map_folder/../../evil", "/etc/evil", "\\\\evil",
                "..\\\\evil", "C:/evil", "", "a\u0000b", ".."}) {
            assertThrows(IOException.class, () -> PeakNavDownloadManager.entryTarget(base, name), name);
        }
        // A sibling whose name begins with the folder's is still outside it.
        assertThrows(IOException.class, () -> PeakNavDownloadManager.entryTarget(base, "../data2/evil"));
    }

    @Test
    @DisplayName("a link the user made is followed, as before: the archive cannot make one")
    void linkOfTheUser(@TempDir Path dir, @TempDir Path elsewhere) throws IOException {
        File base = dir.toFile();
        try {
            Files.createSymbolicLink(dir.resolve("map_folder"), elsewhere);
        } catch (UnsupportedOperationException | IOException noLinksHere) {
            return;
        }
        assertEquals(new File(base, "map_folder/PBF_POI/a.osm.pbf"),
                PeakNavDownloadManager.entryTarget(base, "map_folder/PBF_POI/a.osm.pbf"));
    }
}
