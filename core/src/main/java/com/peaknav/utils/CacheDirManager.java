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

    private void scanCacheDir() {
        List<File> found = getAllFilesInSubdir(cacheDir);
        long totalSize = 0;
        for (File file : found) {
            long lastModified = file.lastModified();

            if (file.exists()) {
                long fileSize = file.length();
                totalSize += fileSize;
                cacheFiles.add(new CachedFiles(file, lastModified));
            }
        }
        Collections.sort(cacheFiles, (c1, c2) -> Long.compare(c1.timestamp, c2.timestamp));
        this.totalSize = totalSize;
    }

    public void removeOldCacheFiles() {
        scanCacheDir();
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
