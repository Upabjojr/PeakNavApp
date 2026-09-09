import static org.junit.jupiter.api.Assertions.assertEquals;

import com.peaknav.utils.ExifReader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/**
 * The EXIF time stamp reader on hand-built EXIF blocks: the GPS date and time (UTC) when
 * present, else DateTimeOriginal in the zone the OffsetTimeOriginal tag names.
 */
class TestExifTimestamp {

    /**
     * A JPEG shell around a TIFF block laid out by hand (big-endian):
     * <pre>
     *   8   IFD0     Exif pointer [, GPS pointer]
     *  38   Exif IFD DateTimeOriginal, OffsetTimeOriginal
     *  68   GPS IFD  GPSTimeStamp, GPSDateStamp          (only with gps)
     *  98   data     the ASCII strings and the three rationals
     * </pre>
     */
    private static byte[] jpeg(String dateTimeOriginal, String offset, boolean gps, String gpsDate, int[] gpsHms) {
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        t.write('M'); t.write('M');
        w16(t, 0x2A);
        w32(t, 8);
        // IFD0 at 8: always two entries, the GPS pointer replaced by a harmless one without gps
        w16(t, 2);
        entry(t, 0x8769, 4, 1, 38);                       // Exif IFD
        if (gps) {
            entry(t, 0x8825, 4, 1, 68);                   // GPS IFD
        } else {
            entry(t, 0x0112, 3, 1, 1 << 16);              // Orientation = 1
        }
        w32(t, 0);
        // Exif IFD at 38
        w16(t, 2);
        entry(t, 0x9003, 2, 20, 98);                      // DateTimeOriginal, by offset
        entry(t, 0x9011, 2, 7, 118);                      // OffsetTimeOriginal, by offset
        w32(t, 0);
        // GPS IFD at 68
        w16(t, 2);
        entry(t, 0x0007, 5, 3, 138);                      // GPSTimeStamp, three rationals
        entry(t, 0x001D, 2, 11, 126);                     // GPSDateStamp
        w32(t, 0);
        // data at 98
        ascii(t, dateTimeOriginal, 20);
        ascii(t, offset, 7);
        t.write(0);                                       // pad to 126
        ascii(t, gpsDate, 11);
        t.write(0);                                       // pad to 138
        for (int v : gpsHms) {
            w32(t, v);
            w32(t, 1);
        }
        byte[] tiff = t.toByteArray();

        ByteArrayOutputStream j = new ByteArrayOutputStream();
        j.write(0xFF); j.write(0xD8);
        j.write(0xFF); j.write(0xE1);
        int len = 2 + 6 + tiff.length;
        j.write((len >> 8) & 0xFF); j.write(len & 0xFF);
        j.write('E'); j.write('x'); j.write('i'); j.write('f'); j.write(0); j.write(0);
        j.write(tiff, 0, tiff.length);
        j.write(0xFF); j.write(0xD9);
        return j.toByteArray();
    }

    private static void entry(ByteArrayOutputStream o, int tag, int type, int count, int value) {
        w16(o, tag);
        w16(o, type);
        w32(o, count);
        w32(o, value);
    }

    private static void ascii(ByteArrayOutputStream o, String s, int length) {
        for (int i = 0; i < length; i++) {
            o.write(i < s.length() ? s.charAt(i) : 0);
        }
    }

    private static void w16(ByteArrayOutputStream o, int v) {
        o.write((v >> 8) & 0xFF); o.write(v & 0xFF);
    }

    private static void w32(ByteArrayOutputStream o, int v) {
        o.write((v >>> 24) & 0xFF); o.write((v >> 16) & 0xFF); o.write((v >> 8) & 0xFF); o.write(v & 0xFF);
    }

    private static long utc(int y, int mo, int d, int h, int mi, int s) {
        GregorianCalendar c = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, mo - 1, d, h, mi, s);
        return c.getTimeInMillis();
    }

    @Test
    @DisplayName("The GPS stamps are taken first: they are UTC")
    void gpsStamps() {
        byte[] j = jpeg("2026:09:07 23:45:10", "+02:00", true, "2026:09:07", new int[]{21, 45, 3});
        assertEquals(utc(2026, 9, 7, 21, 45, 3), ExifReader.extractTimestampMillis(j));
    }

    @Test
    @DisplayName("DateTimeOriginal is shifted by its OffsetTimeOriginal")
    void originalWithOffset() {
        byte[] j = jpeg("2026:09:07 23:45:10", "+02:00", false, "", new int[]{0, 0, 0});
        assertEquals(utc(2026, 9, 7, 21, 45, 10), ExifReader.extractTimestampMillis(j));
        j = jpeg("2026:01:01 00:30:00", "-05:00", false, "", new int[]{0, 0, 0});
        assertEquals(utc(2026, 1, 1, 5, 30, 0), ExifReader.extractTimestampMillis(j));
    }

    @Test
    @DisplayName("Without any offset the device's zone is assumed")
    void originalInDefaultZone() {
        byte[] j = jpeg("2026:09:07 23:45:10", "", false, "", new int[]{0, 0, 0});
        GregorianCalendar c = new GregorianCalendar(TimeZone.getDefault());
        c.clear();
        c.set(2026, 8, 7, 23, 45, 10);
        assertEquals(c.getTimeInMillis(), ExifReader.extractTimestampMillis(j));
    }

    @Test
    @DisplayName("No EXIF, no time")
    void none() {
        assertEquals(ExifReader.NO_TIMESTAMP, ExifReader.extractTimestampMillis(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9}));
        assertEquals(ExifReader.NO_TIMESTAMP, ExifReader.extractTimestampMillis(null));
    }
}
