import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.utils.FileMover;
import com.peaknav.utils.NioFileMover;
import com.peaknav.utils.RenameFileMover;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;

/**
 * The one contract, held by both platform implementations.
 *
 * <p>{@link FileMover} exists because iOS's runtime has no {@code java.nio.file}, so the
 * platforms cannot share the system call - but they must share the behavior: the downloader
 * renames finished {@code .part} files into place and relies on the move replacing an
 * existing target in one step. Each case here therefore runs against BOTH movers; a
 * difference between them would be a platform behaving differently in the field, which is
 * exactly what the abstraction is there to prevent. (Only the desktop JVM runs these tests,
 * so the rename mover is exercised on POSIX semantics - the ones iOS has.)
 */
class TestFileMover {

    private static final FileMover[] MOVERS = { new NioFileMover(), new RenameFileMover() };

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("A move puts the file at its new name, contents intact, old name gone")
    void movesTheFile() throws Exception {
        for (FileMover mover : MOVERS) {
            File from = file("from-" + mover.getClass().getSimpleName(), "downloaded bytes");
            File to = new File(tempDir.toFile(), "to-" + mover.getClass().getSimpleName());

            mover.moveIntoPlace(from, to);

            assertFalse(from.exists(), mover.getClass().getSimpleName()
                    + " must not leave the source behind");
            assertArrayEquals("downloaded bytes".getBytes("UTF-8"), readAll(to));
        }
    }

    @Test
    @DisplayName("A move replaces an existing target - the downloader's re-download case")
    void replacesAnExistingTarget() throws Exception {
        for (FileMover mover : MOVERS) {
            String tag = mover.getClass().getSimpleName();
            File from = file("fresh-" + tag, "the fresh download");
            File to = file("stale-" + tag, "a stale half-written tile");

            mover.moveIntoPlace(from, to);

            assertArrayEquals("the fresh download".getBytes("UTF-8"), readAll(to),
                    tag + " must replace whatever the target held");
            assertFalse(from.exists());
        }
    }

    @Test
    @DisplayName("A missing source is an IOException from either mover")
    void missingSourceThrows() {
        for (FileMover mover : MOVERS) {
            File from = new File(tempDir.toFile(), "never-created");
            File to = new File(tempDir.toFile(), "target");
            assertThrows(IOException.class, () -> mover.moveIntoPlace(from, to),
                    mover.getClass().getSimpleName());
            assertTrue(!to.exists());
        }
    }

    // ---------------------------------------------------------------- helpers

    private File file(String name, String content) throws IOException {
        File file = new File(tempDir.toFile(), name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes("UTF-8"));
        }
        return file;
    }

    private static byte[] readAll(File file) throws IOException {
        byte[] content = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int at = 0;
            while (at < content.length) {
                int read = in.read(content, at, content.length - at);
                if (read < 0) {
                    throw new IOException("file shorter than its reported length");
                }
                at += read;
            }
        }
        return content;
    }
}
