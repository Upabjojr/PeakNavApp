package com.peaknav.utils;

/**
 * Minimal, dependency-free reader for the bits of JPEG EXIF metadata the app needs:
 * the GPS coordinates a photo was taken at, its display orientation, and what the skyline
 * matcher wants to know about the camera - the focal length and, when a phone recorded
 * one, the compass direction it was pointing. Works from the raw image bytes so it can be
 * shared across all platforms (desktop / android / ios).
 *
 * Only the handful of tags required are parsed; anything unexpected or malformed makes
 * a reader return a safe default ({@code null} / orientation 1) rather than throw.
 */
public final class ExifReader {

    // JPEG markers
    private static final int MARKER_APP1 = 0xE1;
    private static final int MARKER_SOS = 0xDA;
    private static final int MARKER_EOI = 0xD9;

    // EXIF / TIFF tags
    private static final int TAG_ORIENTATION = 0x0112;
    private static final int TAG_GPS_IFD = 0x8825;
    private static final int TAG_GPS_LAT_REF = 0x0001;
    private static final int TAG_GPS_LAT = 0x0002;
    private static final int TAG_GPS_LON_REF = 0x0003;
    private static final int TAG_GPS_LON = 0x0004;
    private static final int TAG_GPS_IMG_DIRECTION_REF = 0x0010;
    private static final int TAG_GPS_IMG_DIRECTION = 0x0011;
    private static final int TAG_EXIF_IFD = 0x8769;
    private static final int TAG_FOCAL_LENGTH = 0x920A;
    private static final int TAG_FOCAL_LENGTH_35MM = 0xA405;

    /** The EXIF "normal" orientation: no rotation or flip needed. */
    public static final int ORIENTATION_NORMAL = 1;

    private ExifReader() {
    }

    /**
     * Extracts the location an image was taken at.
     *
     * @param jpeg the raw bytes of a JPEG file
     * @return {@code {latitude, longitude}} in decimal degrees, or {@code null}
     *         if the image carries no (parseable) GPS information
     */
    public static double[] extractLatLon(byte[] jpeg) {
        int tiff = tiffStartOf(jpeg);
        if (tiff < 0) {
            return null;
        }
        try {
            return parseTiff(jpeg, tiff);
        } catch (Throwable t) {
            // Any malformed/truncated metadata just means "no coordinates".
            return null;
        }
    }

    /**
     * Reads the EXIF orientation tag, which tells how the stored pixels must be rotated
     * or flipped to appear upright — phones usually store portrait photos as landscape
     * pixels plus this tag. STB (libGDX's decoder) ignores it, so callers must apply it.
     *
     * @return an EXIF orientation value 1..8; {@link #ORIENTATION_NORMAL} when absent or unreadable
     */
    public static int extractOrientation(byte[] jpeg) {
        int tiff = tiffStartOf(jpeg);
        if (tiff < 0) {
            return ORIENTATION_NORMAL;
        }
        try {
            Boolean little = endianness(jpeg, tiff);
            if (little == null) {
                return ORIENTATION_NORMAL;
            }
            int ifd0 = (int) read32(jpeg, tiff + 4, little);
            int value = findShortValue(jpeg, tiff + ifd0, TAG_ORIENTATION, little);
            return (value >= 1 && value <= 8) ? value : ORIENTATION_NORMAL;
        } catch (Throwable t) {
            return ORIENTATION_NORMAL;
        }
    }

    /**
     * What the EXIF block says about the camera. Every field is {@code NaN} when the tag is
     * absent or unreadable.
     */
    public static final class CameraInfo {
        /** Focal length in millimetres, as the lens reports it. */
        public final float focalLengthMm;
        /** Focal length converted to a 35 mm (36x24 mm frame) equivalent - the field of view. */
        public final float focalLength35mm;
        /**
         * Compass bearing the camera pointed at, degrees clockwise from north (magnetic
         * readings are taken as they are; the difference is a few degrees). Phones write it,
         * cameras rarely do.
         */
        public final float imageDirectionDeg;

        CameraInfo(float focalLengthMm, float focalLength35mm, float imageDirectionDeg) {
            this.focalLengthMm = focalLengthMm;
            this.focalLength35mm = focalLength35mm;
            this.imageDirectionDeg = imageDirectionDeg;
        }

        /**
         * The vertical field of view of an image, degrees, from the 35 mm-equivalent focal
         * length (which is defined on the frame diagonal, 43.27 mm, so it does not depend on
         * the sensor's aspect ratio) - or {@code NaN} when there is no such focal length.
         */
        public float verticalFovDeg(int width, int height) {
            if (Float.isNaN(focalLength35mm) || focalLength35mm <= 0 || width <= 0 || height <= 0) {
                return Float.NaN;
            }
            double halfDiagonal = Math.atan(43.27 / 2 / focalLength35mm);
            return (float) Math.toDegrees(2 * Math.atan(Math.tan(halfDiagonal) * height
                    / Math.hypot(width, height)));
        }
    }

    /** Reads the camera tags; never throws, absent tags come back as {@code NaN}. */
    public static CameraInfo extractCameraInfo(byte[] jpeg) {
        float focal = Float.NaN, focal35 = Float.NaN, direction = Float.NaN;
        int tiff = tiffStartOf(jpeg);
        if (tiff >= 0) {
            try {
                Boolean little = endianness(jpeg, tiff);
                if (little != null) {
                    int ifd0 = tiff + (int) read32(jpeg, tiff + 4, little);
                    int exifOffset = findEntryValue(jpeg, ifd0, TAG_EXIF_IFD, little);
                    if (exifOffset > 0) {
                        int exif = tiff + exifOffset;
                        focal = (float) findRationalValue(jpeg, tiff, exif, TAG_FOCAL_LENGTH, little);
                        int f35 = findShortValue(jpeg, exif, TAG_FOCAL_LENGTH_35MM, little);
                        if (f35 > 0) {
                            focal35 = f35;
                        }
                    }
                    int gpsOffset = findEntryValue(jpeg, ifd0, TAG_GPS_IFD, little);
                    if (gpsOffset > 0) {
                        double dir = findRationalValue(jpeg, tiff, tiff + gpsOffset, TAG_GPS_IMG_DIRECTION, little);
                        if (!Double.isNaN(dir) && dir >= 0 && dir <= 360) {
                            direction = (float) (dir % 360.0);
                        }
                    }
                }
            } catch (Throwable t) {
                // Malformed metadata: keep whatever was read before the damage.
            }
        }
        return new CameraInfo(focal, focal35, direction);
    }

    /** The value of a single RATIONAL entry (stored by offset), or NaN if absent/degenerate. */
    private static double findRationalValue(byte[] d, int tiff, int ifd, int tag, boolean little) {
        int count = read16(d, ifd, little);
        for (int i = 0; i < count; i++) {
            int entry = ifd + 2 + i * 12;
            if (read16(d, entry, little) == tag) {
                int base = tiff + (int) read32(d, entry + 8, little);
                long num = read32(d, base, little);
                long den = read32(d, base + 4, little);
                return den == 0 ? Double.NaN : (double) num / den;
            }
        }
        return Double.NaN;
    }

    /** Validates the JPEG/Exif header and returns the TIFF start offset, or -1. */
    private static int tiffStartOf(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 4) {
            return -1;
        }
        try {
            if ((jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) { // SOI
                return -1;
            }
            return findExifTiffStart(jpeg);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Reads the TIFF byte-order mark: TRUE little-endian, FALSE big-endian, null if invalid. */
    private static Boolean endianness(byte[] d, int tiff) {
        int b0 = d[tiff] & 0xFF;
        int b1 = d[tiff + 1] & 0xFF;
        boolean little;
        if (b0 == 'I' && b1 == 'I') {
            little = true;
        } else if (b0 == 'M' && b1 == 'M') {
            little = false;
        } else {
            return null;
        }
        if (read16(d, tiff + 2, little) != 0x2A) {
            return null;
        }
        return little;
    }

    /** Locates the start of the TIFF header inside the APP1/Exif segment. */
    private static int findExifTiffStart(byte[] d) {
        int pos = 2; // skip SOI
        while (pos + 4 <= d.length) {
            if ((d[pos] & 0xFF) != 0xFF) {
                return -1; // not aligned on a marker: malformed
            }
            int marker = d[pos + 1] & 0xFF;
            if (marker == 0xFF) {
                pos++; // fill byte, keep scanning
                continue;
            }
            if (marker == MARKER_SOS || marker == MARKER_EOI) {
                return -1; // reached compressed image data, no Exif found
            }
            int len = ((d[pos + 2] & 0xFF) << 8) | (d[pos + 3] & 0xFF);
            if (len < 2) {
                return -1;
            }
            int segStart = pos + 4;
            int segEnd = pos + 2 + len;
            if (segEnd > d.length) {
                return -1;
            }
            if (marker == MARKER_APP1 && segStart + 6 <= d.length
                    && d[segStart] == 'E' && d[segStart + 1] == 'x'
                    && d[segStart + 2] == 'i' && d[segStart + 3] == 'f'
                    && d[segStart + 4] == 0 && d[segStart + 5] == 0) {
                return segStart + 6; // TIFF header follows "Exif\0\0"
            }
            pos = segEnd;
        }
        return -1;
    }

    private static double[] parseTiff(byte[] d, int tiff) {
        Boolean little = endianness(d, tiff);
        if (little == null) {
            return null;
        }
        int ifd0 = (int) read32(d, tiff + 4, little);
        int gpsOffset = findEntryValue(d, tiff + ifd0, TAG_GPS_IFD, little);
        if (gpsOffset <= 0) {
            return null;
        }
        return parseGpsIfd(d, tiff, tiff + gpsOffset, little);
    }

    /** Returns the inline LONG value of {@code tag} within the given IFD, or -1. */
    private static int findEntryValue(byte[] d, int ifd, int tag, boolean little) {
        int count = read16(d, ifd, little);
        for (int i = 0; i < count; i++) {
            int entry = ifd + 2 + i * 12;
            if (read16(d, entry, little) == tag) {
                return (int) read32(d, entry + 8, little);
            }
        }
        return -1;
    }

    /** Returns the inline SHORT value of {@code tag} within the given IFD, or -1. */
    private static int findShortValue(byte[] d, int ifd, int tag, boolean little) {
        int count = read16(d, ifd, little);
        for (int i = 0; i < count; i++) {
            int entry = ifd + 2 + i * 12;
            if (read16(d, entry, little) == tag) {
                return read16(d, entry + 8, little);
            }
        }
        return -1;
    }

    private static double[] parseGpsIfd(byte[] d, int tiff, int gps, boolean little) {
        int count = read16(d, gps, little);
        char latRef = 0, lonRef = 0;
        double[] lat = null, lon = null;
        for (int i = 0; i < count; i++) {
            int entry = gps + 2 + i * 12;
            int tag = read16(d, entry, little);
            switch (tag) {
                case TAG_GPS_LAT_REF:
                    latRef = (char) (d[entry + 8] & 0xFF);
                    break;
                case TAG_GPS_LON_REF:
                    lonRef = (char) (d[entry + 8] & 0xFF);
                    break;
                case TAG_GPS_LAT:
                    lat = readDmsRationals(d, tiff, entry, little);
                    break;
                case TAG_GPS_LON:
                    lon = readDmsRationals(d, tiff, entry, little);
                    break;
                default:
                    break;
            }
        }
        if (lat == null || lon == null) {
            return null;
        }
        double latitude = toDegrees(lat);
        double longitude = toDegrees(lon);
        if (latRef == 'S' || latRef == 's') {
            latitude = -latitude;
        }
        if (lonRef == 'W' || lonRef == 'w') {
            longitude = -longitude;
        }
        if (Double.isNaN(latitude) || Double.isNaN(longitude)
                || latitude < -90 || latitude > 90
                || longitude < -180 || longitude > 180) {
            return null;
        }
        return new double[]{latitude, longitude};
    }

    /** Reads the degree/minute/second rational triple stored (by offset) at an IFD entry. */
    private static double[] readDmsRationals(byte[] d, int tiff, int entry, boolean little) {
        int valueOffset = (int) read32(d, entry + 8, little);
        int base = tiff + valueOffset;
        double[] dms = new double[3];
        for (int j = 0; j < 3; j++) {
            long num = read32(d, base + j * 8, little);
            long den = read32(d, base + j * 8 + 4, little);
            dms[j] = (den == 0) ? 0 : (double) num / den;
        }
        return dms;
    }

    private static double toDegrees(double[] dms) {
        return dms[0] + dms[1] / 60.0 + dms[2] / 3600.0;
    }

    private static int read16(byte[] d, int off, boolean little) {
        int a = d[off] & 0xFF;
        int b = d[off + 1] & 0xFF;
        return little ? (b << 8) | a : (a << 8) | b;
    }

    private static long read32(byte[] d, int off, boolean little) {
        long a = d[off] & 0xFFL;
        long b = d[off + 1] & 0xFFL;
        long c = d[off + 2] & 0xFFL;
        long e = d[off + 3] & 0xFFL;
        return little
                ? (e << 24) | (c << 16) | (b << 8) | a
                : (a << 24) | (b << 16) | (c << 8) | e;
    }
}
