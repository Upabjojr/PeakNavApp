package com.peaknav.utils;

import java.io.File;
import java.io.IOException;

/**
 * Moves a finished file onto its final name, atomically, on every platform this app runs on.
 *
 * <p>Downloads and archive extraction both write to a private {@code .part} name and then
 * rename it into place. The rename has to be atomic and has to replace whatever is there:
 * two threads can be fetching the same region at once - both were told it was missing before
 * either finished - and a reader must never see a half-written tile.
 *
 * <p>{@code Files.move(REPLACE_EXISTING, ATOMIC_MOVE)} does that, and is what the desktop and
 * Android use. It cannot be used unconditionally, because <b>RoboVM has no
 * {@code java.nio.file} package at all</b> - not {@code Files}, and not even
 * {@code File.toPath()}, so an iOS build fails on the method reference before it reaches the
 * move. Every tile would download in full and then die on the last step, which is a
 * particularly annoying way to have no map.
 *
 * <p>So nio is tried and, if the runtime does not have it, this falls back to
 * {@link File#renameTo(File)}. That is not a downgrade on iOS: on POSIX {@code renameTo} is
 * {@code rename(2)}, which is atomic and replaces the destination - exactly the guarantee
 * being asked for. It is only on Windows that {@code renameTo} refuses an existing target,
 * and Windows has {@code java.nio.file}, so it never reaches the fallback.
 */
public final class AtomicFileMove {

    /**
     * Whether {@code java.nio.file} is present. Checked by use rather than by reflection, and
     * remembered: the failure is a linking error, and paying one per downloaded tile would be
     * wasteful. Volatile because the download workers are several threads; a benign race here
     * costs one extra failed attempt, never correctness.
     */
    private static volatile boolean nioFilesAvailable = true;

    private AtomicFileMove() {
    }

    /**
     * Renames {@code from} onto {@code to}, replacing any existing file, atomically.
     *
     * @throws IOException if the move could not be made
     */
    public static void moveIntoPlace(File from, File to) throws IOException {
        if (nioFilesAvailable) {
            try {
                java.nio.file.Files.move(from.toPath(), to.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (LinkageError notOnThisRuntime) {
                // NoClassDefFoundError for java.nio.file.Files, or NoSuchMethodError for
                // File.toPath - RoboVM is missing both. Fall through, once and for all.
                nioFilesAvailable = false;
            } catch (UnsupportedOperationException atomicNotSupported) {
                // A filesystem that cannot promise atomicity. The fallback is no worse.
                nioFilesAvailable = false;
            }
        }
        if (from.renameTo(to)) {
            return;
        }
        // Only reachable where renameTo will not replace an existing target. Not atomic, but
        // this platform has already said it cannot offer that.
        if (to.delete() && from.renameTo(to)) {
            return;
        }
        throw new IOException("could not move " + from + " onto " + to);
    }
}
