package com.peaknav.utils;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
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
    private static LruCache<FileHandle, Pixmap> cachedImages = null;
    private static PeakNavLogger logger;
    private static PeakNavCaches caches;
    private static MapController C;
    private static LoadFactory loadFactory;
    private static ConcurrentHashMap<Pixmap, AtomicInteger> pixmapReferenceCounter = new ConcurrentHashMap<>();
    private static BlockingQueue<Pixmap> disposalQueue = new LinkedBlockingQueue<>();

    public static synchronized void initializeCache() {
        if (cachedImages == null) {
            // Evicted pixmaps go to the disposal queue rather than being freed here: one may
            // still be being drawn from, so freePixmapCache disposes it only once its
            // reference count says nobody is left.
            cachedImages = new LruCache<>(100, pixmap -> disposalQueue.add(pixmap));
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

    /**
     * Returns the cached pixmap for the file, or null when the file is missing or unreadable.
     * Every successful call takes one reference: the caller must hand it back with
     * {@link #decrementReferenceCounter(Pixmap)} once done drawing from it, so a pixmap evicted
     * from the cache is only natively disposed when nobody is using it any more.
     */
    public static Pixmap readImageCached(FileHandle imageFileHandle) {
        try {
            Pixmap pixmap = cachedImages.get(imageFileHandle, () -> {
                if (!imageFileHandle.exists()) {
                    // The cache refuses a null value - it would look like a miss on the next
                    // call and be reloaded forever - so a missing file is signalled by
                    // throwing, which arrives below as an ExecutionException.
                    throw new IOException("missing image file: " + imageFileHandle);
                }
                Pixmap loaded = readImage(imageFileHandle.file());
                pixmapReferenceCounter.put(loaded, new AtomicInteger(0));
                freePixmapCache();
                return loaded;
            });
            AtomicInteger counter = pixmapReferenceCounter.get(pixmap);
            if (counter != null) {
                counter.incrementAndGet();
            }
            return pixmap;
        } catch (ExecutionException | RuntimeException e) {
            // ExecutionException: the loader threw - a missing file, or a decode failure on a
            // truncated download. RuntimeException: the same thrown straight out of libGDX.
            // Both mean "no image".
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

    /**
     * Copies the stream into the file, closing BOTH streams even on failure — the download paths
     * call this once per tile, so anything left open here leaks a socket and a file descriptor.
     */
    public static long copyFile(InputStream source, File destination) throws IOException {
        try (InputStream in = source;
             FileOutputStream outputStream = new FileOutputStream(destination)) {
            return copyFile(in, outputStream);
        }
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

    /** Hands back a reference taken by {@link #readImageCached}; null-safe. */
    public static void decrementReferenceCounter(Pixmap pixmap) {
        if (pixmap == null) {
            return;
        }
        AtomicInteger counter = pixmapReferenceCounter.get(pixmap);
        if (counter != null) {
            counter.decrementAndGet();
        }
        // An evicted pixmap whose last user just finished can be disposed right away instead of
        // waiting for the next cache load.
        freePixmapCache();
    }

    public static synchronized void freePixmapCache() {
        Queue<Pixmap> requeue = new LinkedList<>();
        while (!disposalQueue.isEmpty()) {
            Pixmap pixmap = disposalQueue.poll();
            AtomicInteger counter = pixmapReferenceCounter.get(pixmap);
            if (counter == null || counter.get() <= 0) {
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
        // The map's "Loading..." screen while the picture is decoded and turned upright;
        // BackgroundPicManager takes it down once the texture is on screen.
        MapViewerSingleton.getViewerInstance().setPhotoLoading(true);
        Pixmap pixmap;
        try {
            pixmap = new Pixmap(bytesJpeg, 0, bytesJpeg.length);
            // libGDX's decoder ignores the EXIF orientation tag, so a portrait photo (stored
            // as landscape pixels + a rotate tag) would come out sideways. Apply it here.
            pixmap = applyExifOrientation(pixmap, ExifReader.extractOrientation(bytesJpeg));
        } catch (RuntimeException e) {
            // an unreadable file: no picture, and no "Loading..." left on screen
            MapViewerSingleton.getViewerInstance().setPhotoLoading(false);
            throw e;
        }
        MapViewerSingleton.getViewerInstance().backgroundPicManager.setBackgroundPixmap(pixmap);
        // Keep a reduced copy for the skyline match, before anything can dispose the pixmap.
        com.peaknav.viewer.PhotoSkylineAligner.onPhotoLoaded(pixmap, bytesJpeg);
    }

    /**
     * Returns a pixmap with the EXIF orientation baked in, disposing the source when a new
     * one is produced. Orientations 5..8 swap width and height (a quarter turn).
     */
    static Pixmap applyExifOrientation(Pixmap src, int orientation) {
        if (orientation <= ExifReader.ORIENTATION_NORMAL || orientation > 8) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        boolean quarterTurn = orientation >= 5; // 5,6,7,8 transpose the axes
        Pixmap dst = new Pixmap(quarterTurn ? h : w, quarterTurn ? w : h, src.getFormat());
        dst.setBlending(Pixmap.Blending.None);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int color = src.getPixel(x, y);
                int nx;
                int ny;
                switch (orientation) {
                    case 2: nx = w - 1 - x; ny = y;             break; // flip horizontal
                    case 3: nx = w - 1 - x; ny = h - 1 - y;     break; // rotate 180
                    case 4: nx = x;         ny = h - 1 - y;     break; // flip vertical
                    case 5: nx = y;         ny = x;             break; // transpose
                    case 6: nx = h - 1 - y; ny = x;             break; // rotate 90 CW
                    case 7: nx = h - 1 - y; ny = w - 1 - x;     break; // transverse
                    case 8: nx = y;         ny = w - 1 - x;     break; // rotate 90 CCW
                    default: nx = x;        ny = y;             break;
                }
                dst.drawPixel(nx, ny, color);
            }
        }
        src.dispose();
        return dst;
    }

    /**
     * If the given image carries EXIF GPS coordinates, ask the user (through the
     * native screen caller) whether to navigate to the place it was taken.
     */
    public static void checkImageGpsAndPrompt(byte[] imageBytes) {
        NativeScreenCaller nativeScreenCaller = getNativeScreenCaller();
        if (nativeScreenCaller == null) {
            return;
        }
        double[] latLon = ExifReader.extractLatLon(imageBytes);
        if (latLon != null && !isNullIsland(latLon[0], latLon[1])) {
            nativeScreenCaller.promptGoToImageLocation(latLon[0], latLon[1]);
        } else {
            // No usable coordinates: the photo has none, its location EXIF was stripped (Android,
            // without ACCESS_MEDIA_LOCATION), or it is tagged at Null Island (see below).
            nativeScreenCaller.warnCannotReadImageLocation();
        }
    }

    /**
     * (0,0) in the Gulf of Guinea — "Null Island" — is the placeholder cameras and apps write when
     * they have no real fix, so an image tagged there was almost never actually taken there. Treat
     * anything within ~100 m of it as having no location, rather than flying the map to the ocean.
     */
    private static boolean isNullIsland(double lat, double lon) {
        return Math.abs(lat) < 0.001 && Math.abs(lon) < 0.001;
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

    /**
     * Whether the name would draw as missing-glyph boxes and should be replaced by its
     * {@code name:en} / {@code name:latn} variant.
     *
     * <p>This asks {@link FontCharacters} what the generated fonts actually contain rather than
     * assuming a code-point range: the old fixed cut-off at U+024F claimed Latin Extended-A/B
     * were fine when the atlas in fact stopped at U+00FF, so Croatian and other Central European
     * names were kept and then rendered as boxes. Tying the two together means the check can
     * never drift from the font again.
     */
    public static boolean containsUnrenderableCharacters(String str) {
        return FontCharacters.containsUnrenderable(str);
    }

}
