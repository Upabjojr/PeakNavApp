package com.peaknav.utils;

import java.io.File;
import java.io.IOException;

/**
 * The {@link FileMover} for iOS, where {@code java.nio.file} does not exist.
 *
 * <p>{@link File#renameTo(File)} is not a downgrade there: on POSIX it is {@code rename(2)},
 * which is atomic and replaces the destination - exactly the guarantee {@link FileMover}
 * asks for. It is only on Windows that {@code renameTo} refuses an existing target, and
 * Windows runs the desktop build, which uses {@link NioFileMover}.
 *
 * <p>The delete-then-rename second attempt is a belt for a filesystem that surprises anyway;
 * on the platforms that select this class it should never be reached.
 */
public class RenameFileMover extends FileMover {

    @Override
    public void moveIntoPlace(File from, File to) throws IOException {
        if (from.renameTo(to)) {
            return;
        }
        if (to.delete() && from.renameTo(to)) {
            return;
        }
        throw new IOException("could not move " + from + " onto " + to);
    }
}
