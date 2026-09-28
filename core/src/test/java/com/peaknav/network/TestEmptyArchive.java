package com.peaknav.network;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;

/**
 * An archive with nothing in it is refused, not taken for a tile downloaded: a mirror or proxy
 * answering 200 with an empty body, or with only a tar's closing blocks, left the tile stamped
 * downloaded with nothing on disk.
 */
class TestEmptyArchive {

    private static File file(Path dir, String name, int zeros) throws IOException {
        File f = dir.resolve(name).toFile();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(new byte[zeros]);
        }
        return f;
    }

    @Test
    @DisplayName("an empty file, and a tar of only its closing blocks, are refused")
    void emptyArchives(@TempDir Path dir) throws IOException {
        File out = dir.resolve("out").toFile();
        assertThrows(IOException.class,
                () -> PeakNavDownloadManager.unpackTarGz(file(dir, "empty.tar", 0), out));
        assertThrows(IOException.class,
                () -> PeakNavDownloadManager.unpackTarGz(file(dir, "blocks.tar", 1024), out));
    }
}
