package com.peaknav.utils;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.peaknav.compatibility.LoadFactory;
import com.peaknav.compatibility.NativeScreenCaller;
import com.peaknav.viewer.MapViewerSingleton;
import com.peaknav.viewer.controller.MapController;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class PeakNavUtils {
    final static int BUFFER_SIZE = 8192;
    // private static Map<FileHandle, ReentrantLock> fileHandleSet = new HashMap<>();
    // TODO: this is not a proper cache, it's just a hash-map:
    private static Cache<FileHandle, Pixmap> cachedImages = null;
    private static PeakNavLogger logger;
    private static PeakNavCaches caches;
    private static MapController C;
    private static LoadFactory loadFactory;
    private static ConcurrentHashMap<Pixmap, AtomicInteger> pixmapReferenceCounter = new ConcurrentHashMap<>();
    private static BlockingQueue<Pixmap> disposalQueue = new LinkedBlockingQueue<>();

    public static synchronized void initializeCache() {
        if (cachedImages == null) {
            cachedImages = CacheBuilder.newBuilder()
                    .maximumSize(100)
                    .removalListener(
                            (RemovalListener<FileHandle, Pixmap>) notification -> disposalQueue.add(notification.getValue()))
                    .concurrencyLevel(4)
                    .build();
        }
    }

    public static Pixmap readImage(File imageFile) {
        FileHandle imageFileHandle = new FileHandle(imageFile);
        return new Pixmap(imageFileHandle);
    }

    public static Pixmap readImage(FileHandle imageFileHandle) {
        return new Pixmap(imageFileHandle);
    }

    /**
     * Read Pixmap from FileHandle, transform it to greyscale (Format.Alpha)
     * if it is of Format.RGB888
     */
    public static Pixmap readImageToGreyscale(FileHandle imageFileHandle) {
        Pixmap pixmap = readImage(imageFileHandle);
        if (pixmap.getFormat() == Pixmap.Format.RGB888) {
            Pixmap pixmapGrey = new Pixmap(pixmap.getWidth(), pixmap.getHeight(), Pixmap.Format.Alpha);
            for (int y = 0; y < pixmap.getHeight(); y++) {
                for (int x = 0; x < pixmap.getWidth(); x++) {
                    pixmapGrey.drawPixel(x, y, pixmap.getPixel(x, y) >>> 24);
                }
            }
            pixmap.dispose();
            return pixmapGrey;
        } else {
            return pixmap;
        }
    }

    public static Pixmap readImageCached(File imageFile) {
        return readImageCached(new FileHandle(imageFile));
    }

    public static Pixmap readImageCached(FileHandle imageFileHandle) {
        try {
            return cachedImages.get(imageFileHandle, () -> {
                if (!imageFileHandle.exists()) {
                    return null;
                }
                Pixmap pixmap;

                pixmap = readImage(imageFileHandle.file());
                pixmapReferenceCounter.put(pixmap, new AtomicInteger(1));
                freePixmapCache();
                return pixmap;
            });
        } catch (ExecutionException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static short convertImageBytesToElevationMeters(byte byteJpeg, byte bytePng) {
        short val = (short) (byteJpeg & 0xFF);
        short val2 = (short) (bytePng - 128);
        if (Math.abs(val2 % 2) == 1) {
            val = (short) (255 - val);
        }
        val *= 4;
        val += 1024*val2;
        return val;
    }

    public static void setLogger(PeakNavLogger logger) {
        PeakNavUtils.logger = logger;
    }

    public static PeakNavLogger getLogger() {
        return logger;
    }

    public static void setCaches(PeakNavCaches caches) {
        PeakNavUtils.caches = caches;
    }

    public static PeakNavCaches getCaches() {
        return caches;
    }

    public static String getCacheDir() {
        return getCaches().getCacheDir().getAbsolutePath();
    }

    public static List<File> getAllFilesInSubdir(File dir) {
        List<File> cumFiles = new LinkedList<>();
        if (dir.isDirectory()) {
            File[] dirFiles = dir.listFiles();
            if (dirFiles != null) {
                for (File file : dirFiles) {
                    if (file.isFile()) {
                        cumFiles.add(file);
                    } else if (file.isDirectory()) {
                        cumFiles.addAll(getAllFilesInSubdir(file));
                    }
                }
            }
        }
        return cumFiles;
    }

    public static MapController getC() {
        return PeakNavUtils.C;
    }

    public static void setC(MapController c) {
        PeakNavUtils.C = c;
    }

    public static LoadFactory getLoadFactory() {
        return loadFactory;
    }

    public static NativeScreenCaller getNativeScreenCaller() {
        return MapViewerSingleton.getAppInstance().nativeScreenCaller;
    }

    public static void setLoadFactory(LoadFactory loadFactory) {
        PeakNavUtils.loadFactory = loadFactory;
    }

    public static long copyFile(InputStream source, File destination) throws IOException {
        FileOutputStream outputStream = new FileOutputStream(destination);
        return copyFile(source, outputStream);
    }

    public static long copyFile(InputStream source, OutputStream sink)
            throws IOException
    {
        long nread = 0L;
        byte[] buf = new byte[BUFFER_SIZE];
        int n;
        while ((n = source.read(buf)) > 0) {
            sink.write(buf, 0, n);
            nread += n;
        }
        return nread;
    }

    public static float[] convertBufferedImageToArray(Pixmap bufferedImage) {
        int width = bufferedImage.getWidth();
        int height = bufferedImage.getHeight();

        float[] imageHeights = new float[width*height];

        for (int y = 0; y < bufferedImage.getHeight(); ++y) {
            for (int x = 0; x < bufferedImage.getWidth(); ++x) {
                imageHeights[(height - 1 - y)*width + x] = (float)(bufferedImage.getPixel(x, y));
            }
        }

        return imageHeights;
    }

    private final static ConcurrentMap<String, Lock> blockedImages = new ConcurrentHashMap<>();

    public static void decrementReferenceCounter(Pixmap pixmap) {
        pixmapReferenceCounter.get(pixmap).decrementAndGet();
    }

    public static synchronized void freePixmapCache() {
        Queue<Pixmap> requeue = new LinkedList<>();
        while (!disposalQueue.isEmpty()) {
            Pixmap pixmap = disposalQueue.poll();
            if (pixmapReferenceCounter.get(pixmap).get() == 0) {
                pixmap.dispose();
                pixmapReferenceCounter.remove(pixmap);
            } else {
                requeue.add(pixmap);
            }
        }
        for (Pixmap pixmap : requeue) {
            disposalQueue.add(pixmap);
        }
    }

    public static class PixmapLock {
        public final Pixmap pixmap;
        public final Lock lock;
        public PixmapLock(Pixmap pixmap, Lock lock) {
            this.pixmap = pixmap;
            this.lock = lock;
        }
    }
    public static PixmapLock readImageBlocking(File tileTexture) {
        String tilePath = tileTexture.getAbsolutePath();
        Lock lock = blockedImages.get(tilePath);
        if (lock == null) {
            synchronized (blockedImages) {
                blockedImages.put(tilePath, new ReentrantLock());
            }
            lock = blockedImages.get(tilePath);
        }
        lock.lock();
        return new PixmapLock(readImage(tileTexture), lock);
    }

    public static void setBytesAsBackgroundImage(byte[] bytesJpeg) {
        Pixmap pixmap = new Pixmap(bytesJpeg, 0, bytesJpeg.length);
        MapViewerSingleton.getViewerInstance().backgroundPicManager.setBackgroundPixmap(pixmap);
    }

    public static String s(String key) {
        if (getC().i18n == null)
            return key.replace("_", " ");
        return getC().i18n.s(key);
    }

    /*
    public static String dumpToJson(Object object) {
        Gson gson = new Gson();
        String json = gson.toJson(object);
        try {
            File tempFile = File.createTempFile("json-dump", ".json");
            FileOutputStream fos = new FileOutputStream(tempFile);
            fos.write(json.getBytes(StandardCharsets.UTF_8));
            return tempFile.getAbsolutePath();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    */

    public static boolean containsNonLatinCharacters(String str) {
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            /*
            !(c >= '\u0000' && c <= '\u007F') &&     // Basic Latin
                !(c >= '\u0080' && c <= '\u00FF') &&     // Latin-1 Supplement
                !(c >= '\u0100' && c <= '\u017F') &&     // Latin Extended-A
                !(c >= '\u0180' && c <= '\u024F'))      // Latin Extended-B
             */
            if (
                    !(c <= '\u024F')
            ) {
                return true; // Non-Latin character found
            }
        }
        return false; // No non-Latin characters found
    }

}
