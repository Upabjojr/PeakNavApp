import static org.junit.jupiter.api.Assertions.assertEquals;

import com.peaknav.utils.ExifReader;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

/**
 * Verifies the EXIF orientation tag is read back correctly from a minimal, hand-built
 * JPEG/Exif header, in both byte orders, and that malformed input falls back to normal.
 */
public class TestExifReader {

    /**
     * Builds the smallest valid JPEG whose IFD0 carries a single Orientation (0x0112) SHORT.
     * Layout: SOI, APP1("Exif\0\0" + TIFF{header, IFD0{orientation}}), EOI.
     */
    private static byte[] jpegWithOrientation(int orientation, boolean little) {
        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        // TIFF header
        if (little) {
            tiff.write('I'); tiff.write('I');
        } else {
            tiff.write('M'); tiff.write('M');
        }
        write16(tiff, 0x2A, little);
        write32(tiff, 8, little);          // IFD0 begins 8 bytes into the TIFF block
        // IFD0
        write16(tiff, 1, little);          // one entry
        write16(tiff, 0x0112, little);     // tag: Orientation
        write16(tiff, 3, little);          // type: SHORT
        write32(tiff, 1, little);          // count: 1
        // SHORT value, left-justified in the 4-byte value field
        if (little) {
            tiff.write(orientation & 0xFF); tiff.write((orientation >> 8) & 0xFF);
            tiff.write(0); tiff.write(0);
        } else {
            tiff.write((orientation >> 8) & 0xFF); tiff.write(orientation & 0xFF);
            tiff.write(0); tiff.write(0);
        }
        write32(tiff, 0, little);          // no next IFD
        byte[] tiffBytes = tiff.toByteArray();

        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        jpeg.write(0xFF); jpeg.write(0xD8);                       // SOI
        jpeg.write(0xFF); jpeg.write(0xE1);                       // APP1
        int app1Len = 2 + 6 + tiffBytes.length;                  // length field + "Exif\0\0" + TIFF
        jpeg.write((app1Len >> 8) & 0xFF); jpeg.write(app1Len & 0xFF);
        jpeg.write('E'); jpeg.write('x'); jpeg.write('i'); jpeg.write('f'); jpeg.write(0); jpeg.write(0);
        jpeg.write(tiffBytes, 0, tiffBytes.length);
        jpeg.write(0xFF); jpeg.write(0xD9);                       // EOI
        return jpeg.toByteArray();
    }

    private static void write16(ByteArrayOutputStream o, int v, boolean little) {
        if (little) {
            o.write(v & 0xFF); o.write((v >> 8) & 0xFF);
        } else {
            o.write((v >> 8) & 0xFF); o.write(v & 0xFF);
        }
    }

    private static void write32(ByteArrayOutputStream o, int v, boolean little) {
        if (little) {
            o.write(v & 0xFF); o.write((v >> 8) & 0xFF); o.write((v >> 16) & 0xFF); o.write((v >> 24) & 0xFF);
        } else {
            o.write((v >> 24) & 0xFF); o.write((v >> 16) & 0xFF); o.write((v >> 8) & 0xFF); o.write(v & 0xFF);
        }
    }

    @Test
    public void readsEveryOrientationBigEndian() {
        for (int o = 1; o <= 8; o++) {
            assertEquals(o, ExifReader.extractOrientation(jpegWithOrientation(o, false)),
                    "big-endian orientation " + o);
        }
    }

    @Test
    public void readsEveryOrientationLittleEndian() {
        for (int o = 1; o <= 8; o++) {
            assertEquals(o, ExifReader.extractOrientation(jpegWithOrientation(o, true)),
                    "little-endian orientation " + o);
        }
    }

    @Test
    public void fallsBackToNormalForNonExifOrGarbage() {
        assertEquals(ExifReader.ORIENTATION_NORMAL, ExifReader.extractOrientation(null));
        assertEquals(ExifReader.ORIENTATION_NORMAL, ExifReader.extractOrientation(new byte[]{1, 2, 3}));
        // A valid JPEG SOI but no Exif segment.
        assertEquals(ExifReader.ORIENTATION_NORMAL,
                ExifReader.extractOrientation(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9}));
        // An out-of-range orientation value is ignored.
        assertEquals(ExifReader.ORIENTATION_NORMAL, ExifReader.extractOrientation(jpegWithOrientation(42, false)));
    }
}
