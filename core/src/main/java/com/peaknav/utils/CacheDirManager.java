package com.peaknav.utils;

import static com.peaknav.utils.PeakNavUtils.getAllFilesInSubdir;
import static com.peaknav.utils.PeakNavUtils.getCacheDir;

import java.io.File;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class CacheDirManager {

    private final File cacheDir;
    private Long totalSize = null;
    private final static long CACHE_SIZE_LIMIT = 500000000;

    private static class CachedFiles {
        final File file;
        final long timestamp;

        CachedFiles(File file, long timestamp) {
            this.file = file;
            this.timestamp = timestamp;
        }
    }

    private List<CachedFiles> cacheFiles = new LinkedList<>();

    public CacheDirManager() {
        cacheDir = new File(getCacheDir());
    }

    /**
     * Rebuilds the list of cached files, newest last.
     *
     * <p>Into a LOCAL list, published at the end. It used to append to the shared field and
     * sort that, which broke twice over: the field was never cleared, so every scan piled
     * another copy of the whole cache onto the last one; and two satellite threads run this
     * at once ({@code tileRendererExecutorSat} has two, and each satellite pass submits a
     * clean-up), so they interleaved appends into one LinkedList and left null links in it.
     * The sort then dereferenced one and the app died - observed on a device, twice within
     * 40 ms, once per worker thread:
     * {@code NullPointerException ... CachedFiles.timestamp ... CacheDirManager.lambda$scanCacheDir$0}.
     */
    private List<CachedFiles> scanCacheDir() {
        List<File> found = getAllFilesInSubdir(cacheDir);
        List<CachedFiles> scanned = new java.util.ArrayList<>();
        long totalSize = 0;
        for (File file : found) {
            long lastModified = file.lastModified();

            if (file.exists()) {
                long fileSize = file.length();
                totalSize += fileSize;
                scanned.add(new CachedFiles(file, lastModified));
            }
        }
        Collections.sort(scanned, (c1, c2) -> Long.compare(c1.timestamp, c2.timestamp));
        this.totalSize = totalSize;
        this.cacheFiles = scanned;
        return scanned;
    }

    /**
     * Synchronized: two satellite workers asking to tidy the cache at the same moment would
     * otherwise both walk the directory and both delete from it, racing over which files
     * still exist.
     */
    public synchronized void removeOldCacheFiles() {
        List<CachedFiles> cacheFiles = scanCacheDir();
        long TIMESTAMP_30_DAY_AGO = System.currentTimeMillis() - 30L*24*3600*1000;
        if (totalSize > CACHE_SIZE_LIMIT) {
            for (CachedFiles cachedFile: cacheFiles) {
                if (cachedFile.timestamp < TIMESTAMP_30_DAY_AGO) {
                    if (cachedFile.file.exists()) {
                        cachedFile.file.delete();
                    }
                }
            }
        }
    }

}
