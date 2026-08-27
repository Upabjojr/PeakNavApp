package com.peaknav.utils;

import java.io.File;
import java.io.IOException;

/**
 * Renames a finished file onto its final name, atomically, replacing any existing target.
 *
 * <p>Downloads and archive extraction both write to a private {@code .part} name and then
 * rename it into place. The rename has to be atomic and has to replace whatever is there:
 * two threads can be fetching the same region at once - both were told it was missing before
 * either finished - and a reader must never see a half-written tile.
 *
 * <p>This is platform-shaped in the same way as {@link UtilsOSDep}: the JVM platforms have
 * {@code java.nio.file}, which states the guarantee explicitly, while RoboVM's runtime does
 * not have the package at all - not {@code Files}, and not even {@code File.toPath()}, so a
 * class that so much as mentions them cannot be loaded on iOS. Each platform's
 * {@link com.peaknav.compatibility.LoadFactory#getFileMover()} therefore supplies the
 * implementation its runtime can carry: {@link NioFileMover} on desktop, Android and the
 * headless renderer, {@link RenameFileMover} on iOS. The contract - atomic, replacing - is
 * identical; only the system call spelling differs.
 */
public abstract class FileMover {

    /**
     * Renames {@code from} onto {@code to}, replacing any existing file, atomically.
     *
     * @throws IOException if the move could not be made
     */
    public abstract void moveIntoPlace(File from, File to) throws IOException;
}
