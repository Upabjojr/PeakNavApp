import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.utils.ExifReader;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

/**
 * The camera tags the skyline matcher relies on - focal length, its 35 mm equivalent and
 * the GPS image direction - read back from a hand-built Exif block with an Exif sub-IFD
 * and a GPS IFD, in both byte orders; and the field of view derived from them.
 */
public class TestExifCameraInfo {

    /**
     * TIFF layout: header, IFD0 {ExifIFD pointer, GPSIFD pointer}, Exif IFD {FocalLength
     * RATIONAL, FocalLengthIn35mmFilm SHORT}, GPS IFD {GPSImgDirectionRef, GPSImgDirection
     * RATIONAL}, then the two rational values.
     */
    private static byte[] jpeg(boolean little, int focalNum, int focalDen, int focal35,
                               int dirNum, int dirDen) {
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        t.write(little ? 'I' : 'M');
        t.write(little ? 'I' : 'M');
        w16(t, 0x2A, little);
        w32(t, 8, little);
        // IFD0 at 8: 2 entries (2 + 2*12 + 4 = 30 bytes) -> Exif IFD at 38
        final int exifIfd = 38;
        final int exifLen = 2 + 2 * 12 + 4;             // 30 -> GPS IFD at 68
        final int gpsIfd = exifIfd + exifLen;
        final int gpsLen = 2 + 2 * 12 + 4;              // 30 -> values at 98
        final int focalValue = gpsIfd + gpsLen;
        final int dirValue = focalValue + 8;
        w16(t, 2, little);
        entryLong(t, 0x8769, exifIfd, little);
        entryLong(t, 0x8825, gpsIfd, little);
        w32(t, 0, little);
        // Exif IFD
        w16(t, 2, little);
        entryOffset(t, 0x920A, 5, focalValue, little);   // FocalLength RATIONAL
        entryShort(t, 0xA405, focal35, little);          // FocalLengthIn35mmFilm
        w32(t, 0, little);
        // GPS IFD
        w16(t, 2, little);
        w16(t, 0x0010, little); w16(t, 2, little); w32(t, 2, little); // ASCII "T\0"
        t.write('T'); t.write(0); t.write(0); t.write(0);
        entryOffset(t, 0x0011, 5, dirValue, little);     // GPSImgDirection RATIONAL
        w32(t, 0, little);
        // values
        w32(t, focalNum, little); w32(t, focalDen, little);
        w32(t, dirNum, little); w32(t, dirDen, little);
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

    private static void entryLong(ByteArrayOutputStream o, int tag, int value, boolean little) {
        w16(o, tag, little); w16(o, 4, little); w32(o, 1, little); w32(o, value, little);
    }

    private static void entryOffset(ByteArrayOutputStream o, int tag, int type, int offset, boolean little) {
        w16(o, tag, little); w16(o, type, little); w32(o, 1, little); w32(o, offset, little);
    }

    private static void entryShort(ByteArrayOutputStream o, int tag, int value, boolean little) {
        w16(o, tag, little); w16(o, 3, little); w32(o, 1, little);
        if (little) {
            o.write(value & 0xFF); o.write((value >> 8) & 0xFF); o.write(0); o.write(0);
        } else {
            o.write((value >> 8) & 0xFF); o.write(value & 0xFF); o.write(0); o.write(0);
        }
    }

    private static void w16(ByteArrayOutputStream o, int v, boolean little) {
        if (little) {
            o.write(v & 0xFF); o.write((v >> 8) & 0xFF);
        } else {
            o.write((v >> 8) & 0xFF); o.write(v & 0xFF);
        }
    }

    private static void w32(ByteArrayOutputStream o, int v, boolean little) {
        if (little) {
            o.write(v & 0xFF); o.write((v >> 8) & 0xFF); o.write((v >> 16) & 0xFF); o.write((v >> 24) & 0xFF);
        } else {
            o.write((v >> 24) & 0xFF); o.write((v >> 16) & 0xFF); o.write((v >> 8) & 0xFF); o.write(v & 0xFF);
        }
    }

    @Test
    public void readsFocalLengthsAndDirectionInBothByteOrders() {
        for (boolean little : new boolean[]{false, true}) {
            ExifReader.CameraInfo info = ExifReader.extractCameraInfo(jpeg(little, 425, 100, 28, 12345, 100));
            assertEquals(4.25f, info.focalLengthMm, 1e-6, "focal length, little=" + little);
            assertEquals(28f, info.focalLength35mm, 1e-6, "35 mm equivalent, little=" + little);
            assertEquals(123.45f, info.imageDirectionDeg, 1e-3, "image direction, little=" + little);
        }
    }

    @Test
    public void verticalFieldOfViewFollowsTheDiagonalDefinition() {
        ExifReader.CameraInfo info = ExifReader.extractCameraInfo(jpeg(true, 425, 100, 28, 0, 1));
        // A 28 mm lens on a 3:2 frame: 65.5 degrees across the 36 mm side, 46.4 down the
        // 24 mm side - the classic tables.
        assertEquals(46.4f, info.verticalFovDeg(3000, 2000), 0.2f);
        // Portrait orientation swaps the roles: the long side is now vertical.
        assertEquals(65.5f, info.verticalFovDeg(2000, 3000), 0.2f);
    }

    @Test
    public void absentTagsReadAsNaN() {
        ExifReader.CameraInfo info = ExifReader.extractCameraInfo(new byte[]{(byte) 0xFF, (byte) 0xD8, 0, 0});
        assertTrue(Float.isNaN(info.focalLengthMm));
        assertTrue(Float.isNaN(info.focalLength35mm));
        assertTrue(Float.isNaN(info.imageDirectionDeg));
        assertTrue(Float.isNaN(info.verticalFovDeg(100, 100)));
    }
}
