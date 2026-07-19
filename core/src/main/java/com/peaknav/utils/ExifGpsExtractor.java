package com.peaknav.utils;

/**
 * Minimal, dependency-free reader for the GPS coordinates stored in the EXIF
 * metadata of a JPEG image. Works from the raw image bytes so it can be shared
 * across all platforms (desktop / android / ios).
 *
 * Only the handful of tags needed to recover a latitude/longitude are parsed;
 * anything unexpected or malformed makes the extractor return {@code null}
 * rather than throw.
 */
public final class ExifGpsExtractor {

    // JPEG markers
    private static final int MARKER_APP1 = 0xE1;
    private static final int MARKER_SOS = 0xDA;
    private static final int MARKER_EOI = 0xD9;

    // EXIF / TIFF tags
    private static final int TAG_GPS_IFD = 0x8825;
    private static final int TAG_GPS_LAT_REF = 0x0001;
    private static final int TAG_GPS_LAT = 0x0002;
    private static final int TAG_GPS_LON_REF = 0x0003;
    private static final int TAG_GPS_LON = 0x0004;

    private ExifGpsExtractor() {
    }

    /**
     * Extracts the location an image was taken at.
     *
     * @param jpeg the raw bytes of a JPEG file
     * @return {@code {latitude, longitude}} in decimal degrees, or {@code null}
     *         if the image carries no (parseable) GPS information
     */
    public static double[] extractLatLon(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 4) {
            return null;
        }
        try {
            // SOI marker
            if ((jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
                return null;
            }
            int tiffStart = findExifTiffStart(jpeg);
            if (tiffStart < 0) {
                return null;
            }
            return parseTiff(jpeg, tiffStart);
        } catch (Throwable t) {
            // Any malformed/truncated metadata just means "no coordinates".
            return null;
        }
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
        int ifd0 = (int) read32(d, tiff + 4, little);
        int gpsOffset = findEntryValue(d, tiff, tiff + ifd0, TAG_GPS_IFD, little);
        if (gpsOffset <= 0) {
            return null;
        }
        return parseGpsIfd(d, tiff, tiff + gpsOffset, little);
    }

    /** Returns the inline LONG value of {@code tag} within the given IFD, or -1. */
    private static int findEntryValue(byte[] d, int tiff, int ifd, int tag, boolean little) {
        int count = read16(d, ifd, little);
        for (int i = 0; i < count; i++) {
            int entry = ifd + 2 + i * 12;
            if (read16(d, entry, little) == tag) {
                return (int) read32(d, entry + 8, little);
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
