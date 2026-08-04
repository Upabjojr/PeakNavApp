package com.peaknav.headless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.database.MapSqlite;
import com.peaknav.database.MapSqliteDesktop;
import com.peaknav.pbf.PbfLayer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Timestamp;

/**
 * Asking for a download must be able to heal missing files: re-queueing a tile clears
 * its downloaded mark even though the database says it was done. The mark is
 * bookkeeping about the past; the request is a statement about the present - files can
 * be deleted, moved or corrupted while the mark stays true for ever.
 */
class DownloadRequeueTest {

    @Test
    @DisplayName("re-queueing a tile marked downloaded makes it pending again")
    void requeueClearsTheDownloadedMark(@TempDir Path dir) {
        MapSqliteDesktop db = new MapSqliteDesktop();
        db.openConnection(dir.resolve("map_database.sqlite").toFile());
        db.createTables();

        db.addToDownloadQueueMapData(133, 91, 8, PbfLayer.PBF_HIGHWAYS);
        assertEquals(1, db.getDownloadQueue().size(), "queued once");

        // The download completes; the row is marked done and leaves the pending queue.
        MapSqlite.QueuedTile tile = db.getDownloadQueue().get(0);
        db.updateDownloadQueueMapDataTimestamp(tile, new Timestamp(1_000_000L));
        assertEquals(0, db.getDownloadQueue().size(),
                "a downloaded tile is no longer pending");

        // The user asks again - perhaps because the files are gone. The mark must not
        // veto the request: the tile returns to the pending queue.
        db.addToDownloadQueueMapData(133, 91, 8, PbfLayer.PBF_HIGHWAYS);
        assertEquals(1, db.getDownloadQueue().size(),
                "re-queueing must clear the downloaded mark and make the tile pending");
        assertTrue(db.getDownloadQueue().get(0).downloadTime == null,
                "the stale mark is gone");
    }
}
