package com.peaknav.utils;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts the graphics resources the tile pipeline creates and frees.
 *
 * <p>Diagnostic scaffolding for a native-memory leak: a scripted render grew by tens of
 * megabytes a frame while the Java heap stayed flat, which is the signature of libGDX
 * resources - meshes, textures, pixmaps - being abandoned rather than disposed. The garbage
 * collector frees the small Java wrapper and leaves the megabytes behind it untouched, so
 * neither the heap nor a heap dump shows anything wrong.
 *
 * <p>Counting both ends of each resource's life turns that into something you can read off:
 * whichever "created" runs away from its "disposed" is the leak.
 */
public final class ResourceStats {

    private ResourceStats() {}

    public static final AtomicLong tilesCreated = new AtomicLong();
    public static final AtomicLong tilesDisposed = new AtomicLong();
    public static final AtomicLong meshesCreated = new AtomicLong();
    public static final AtomicLong meshesDisposed = new AtomicLong();
    public static final AtomicLong texturesCreated = new AtomicLong();
    public static final AtomicLong texturesDisposed = new AtomicLong();
    public static final AtomicLong pixmapsCreated = new AtomicLong();
    public static final AtomicLong pixmapsDisposed = new AtomicLong();
    public static final AtomicLong elevationImagesCreated = new AtomicLong();
    public static final AtomicLong elevationImagesDisposed = new AtomicLong();
    /** Full label-visibility passes run - what the video pipeline throttles to stop flicker. */
    public static final AtomicLong labelVisibilityRuns = new AtomicLong();
    /** Passes that ran to completion - a refresh frame waits for this, not for the start. */
    public static final AtomicLong labelVisibilityCompleted = new AtomicLong();

    /** live = created - disposed; a live count that climbs with every frame is the leak. */
    public static String summary() {
        return String.format(
                "tiles %d/%d live %d | meshes %d/%d live %d | textures %d/%d live %d "
                        + "| pixmaps %d/%d live %d | elev %d/%d live %d",
                tilesCreated.get(), tilesDisposed.get(),
                tilesCreated.get() - tilesDisposed.get(),
                meshesCreated.get(), meshesDisposed.get(),
                meshesCreated.get() - meshesDisposed.get(),
                texturesCreated.get(), texturesDisposed.get(),
                texturesCreated.get() - texturesDisposed.get(),
                pixmapsCreated.get(), pixmapsDisposed.get(),
                pixmapsCreated.get() - pixmapsDisposed.get(),
                elevationImagesCreated.get(), elevationImagesDisposed.get(),
                elevationImagesCreated.get() - elevationImagesDisposed.get());
    }
}
