package com.peaknav.utils;

import java.io.File;
import java.io.IOException;

/**
 * The {@link FileMover} for platforms whose runtime has {@code java.nio.file}: desktop,
 * Android and the headless renderer.
 *
 * <p>{@code Files.move(REPLACE_EXISTING, ATOMIC_MOVE)} is exactly the contract, stated in the
 * type system, and it is what these platforms have always used.
 *
 * <p>This class lives in {@code core} because both desktop and Android need it and {@code core}
 * is their only shared module - but it must never be referenced from code reachable on iOS,
 * where the classes it names do not exist. Only the JVM platforms' {@code LoadFactory}
 * implementations construct it; iOS constructs {@link RenameFileMover} instead. (The RoboVM
 * bootclasspath audit in AGENTS.md flags this file's {@code java.nio.file} references - that
 * is expected, and this class is the only place they are allowed.)
 */
public class NioFileMover extends FileMover {

    @Override
    public void moveIntoPlace(File from, File to) throws IOException {
        java.nio.file.Files.move(from.toPath(), to.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
