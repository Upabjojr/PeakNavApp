package com.peaknav.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Files handed to PeakNav by other apps - shared, opened with it, picked from the gallery -
 * read with a limit, and pictures in formats the app cannot decode turned into ones it can.
 *
 * <p>libGDX decodes JPEG and PNG only. A HEIC photo (what iPhones and many Android cameras
 * save) or a WebP one shared to PeakNav was taken for a GPX file, because anything that was
 * not a JPEG or a PNG was, and megabytes of binary were parsed as XML; picked from the
 * gallery, it failed to decode with nothing said. Here Android decodes it, and it goes on as
 * a JPEG carrying the original's location, orientation and focal length, which the "go to
 * where it was taken" prompt and the skyline match read.
 */
public final class ImportedFiles {

    private ImportedFiles() {
    }

    /** The most bytes read from another app: a photo or a track is well under it. */
    public static final long MAX_BYTES = 64L * 1024 * 1024;

    /** A file larger than {@link #MAX_BYTES}. */
    public static final class TooLarge extends IOException {
        TooLarge(Uri uri) {
            super("more than " + MAX_BYTES + " bytes: " + uri);
        }
    }

    /**
     * Every byte behind a uri, up to {@link #MAX_BYTES}; null if the provider has no stream.
     * Off the main thread: a file in Drive or a mail attachment is fetched over the network
     * as it is read, and read in onCreate it could hold the app unresponsive.
     */
    public static byte[] read(ContentResolver resolver, Uri uri) throws IOException {
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            long total = 0;
            int n;
            while ((n = in.read(chunk, 0, chunk.length)) != -1) {
                total += n;
                if (total > MAX_BYTES) {
                    throw new TooLarge(uri);
                }
                buffer.write(chunk, 0, n);
            }
            return buffer.toByteArray();
        }
    }

    /** Whether the bytes are text that starts like XML: a GPX track, as far as can be told. */
    public static boolean looksLikeXml(byte[] data) {
        int i = 0;
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            i = 3;   // a byte-order mark
        }
        while (i < data.length && i < 4096 && (data[i] == ' ' || data[i] == '\t' || data[i] == '\r' || data[i] == '\n')) {
            i++;
        }
        return i < data.length && data[i] == '<';
    }

    /** The longest side a converted picture keeps: twice what the app shows, well within memory. */
    private static final int MAX_EDGE = 4096;

    /**
     * A picture the app can decode: the bytes themselves if they are a JPEG or a PNG, the
     * picture turned into a JPEG if Android can decode it, null if it is no picture at all.
     */
    public static byte[] asReadableImage(Context context, byte[] data) {
        if (PeakNavUtils.looksLikeImage(data)) {
            return data;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > MAX_EDGE) {
            options.inSampleSize *= 2;
        }
        Bitmap bitmap;
        try {
            bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } catch (OutOfMemoryError tooBig) {
            return null;
        }
        if (bitmap == null) {
            return null;
        }
        File jpeg = null;
        try {
            jpeg = File.createTempFile("imported", ".jpg", context.getCacheDir());
            try (FileOutputStream out = new FileOutputStream(jpeg)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out);
            }
            bitmap.recycle();
            copyExif(data, jpeg);
            byte[] bytes = new byte[(int) jpeg.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(jpeg)) {
                int off = 0;
                while (off < bytes.length) {
                    int n = in.read(bytes, off, bytes.length - off);
                    if (n < 0) {
                        break;
                    }
                    off += n;
                }
            }
            return bytes;
        } catch (IOException | RuntimeException failed) {
            failed.printStackTrace();
            return null;
        } finally {
            if (jpeg != null) {
                jpeg.delete();
            }
        }
    }

    private static final String[] KEPT_TAGS = {
            android.media.ExifInterface.TAG_GPS_LATITUDE,
            android.media.ExifInterface.TAG_GPS_LATITUDE_REF,
            android.media.ExifInterface.TAG_GPS_LONGITUDE,
            android.media.ExifInterface.TAG_GPS_LONGITUDE_REF,
            android.media.ExifInterface.TAG_GPS_ALTITUDE,
            android.media.ExifInterface.TAG_GPS_ALTITUDE_REF,
            android.media.ExifInterface.TAG_ORIENTATION,
            android.media.ExifInterface.TAG_FOCAL_LENGTH,
            android.media.ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            android.media.ExifInterface.TAG_DATETIME_ORIGINAL,
            android.media.ExifInterface.TAG_MAKE,
            android.media.ExifInterface.TAG_MODEL,
    };

    /**
     * The original's position, orientation and lens, onto the converted JPEG. The pixels were
     * decoded as stored, not turned upright, so the orientation tag still applies to them.
     */
    private static void copyExif(byte[] original, File jpeg) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
            return;   // ExifInterface reads a stream from Android 7 on
        }
        try {
            android.media.ExifInterface from = new android.media.ExifInterface(
                    new java.io.ByteArrayInputStream(original));
            android.media.ExifInterface to = new android.media.ExifInterface(jpeg.getAbsolutePath());
            boolean any = false;
            for (String tag : KEPT_TAGS) {
                String value = from.getAttribute(tag);
                if (value != null) {
                    to.setAttribute(tag, value);
                    any = true;
                }
            }
            if (any) {
                to.saveAttributes();
            }
        } catch (IOException | RuntimeException unreadable) {
            // A picture with no readable EXIF goes on without it: shown, but not placed.
        }
    }
}
