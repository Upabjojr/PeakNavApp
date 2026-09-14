import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.utils.ExifReader;
import com.peaknav.utils.ExifWriter;
import com.peaknav.utils.SnapshotInfo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** A snapshot's pose written into a JPEG and a PNG, and read back by the app's own EXIF reader. */
class TestExifWriter {

    private static byte[] encoded(String format) throws IOException {
        BufferedImage image = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(javax.imageio.ImageIO.write(image, format, out));
        return out.toByteArray();
    }

    @Test
    @DisplayName("Position, heading and field of view survive the trip through a JPEG")
    void jpegRoundTrip() throws IOException {
        SnapshotInfo info = new SnapshotInfo(45.97640, 7.65860, 3130.5, 236.9, -1.8, 40.0, 1600, 1200, true);
        byte[] jpeg = ExifWriter.embedInJpeg(encoded("jpg"), info);
        double[] latLon = ExifReader.extractLatLon(jpeg);
        assertNotNull(latLon);
        assertEquals(45.97640, latLon[0], 1e-4);
        assertEquals(7.65860, latLon[1], 1e-4);
        ExifReader.CameraInfo camera = ExifReader.extractCameraInfo(jpeg);
        assertNotNull(camera);
        assertEquals(236.9, camera.imageDirectionDeg, 0.01);
        assertEquals(info.focalLength35mm(), camera.focalLength35mm, 0.5);
        assertEquals(40.0, camera.verticalFovDeg(1600, 1200), 0.6);
        assertEquals(ExifReader.ORIENTATION_NORMAL, ExifReader.extractOrientation(jpeg));
        // still a JPEG that decodes
        assertNotNull(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(jpeg)));
    }

    @Test
    @DisplayName("A southern, western position keeps its signs")
    void southWest() throws IOException {
        SnapshotInfo info = new SnapshotInfo(-33.4489, -70.6693, Double.NaN, 12.0, 3.0, 50.0, 800, 600, false);
        double[] latLon = ExifReader.extractLatLon(ExifWriter.embedInJpeg(encoded("jpg"), info));
        assertNotNull(latLon);
        assertEquals(-33.4489, latLon[0], 1e-4);
        assertEquals(-70.6693, latLon[1], 1e-4);
    }

    @Test
    @DisplayName("A PNG gets an eXIf chunk and still decodes")
    void pngChunk() throws IOException {
        SnapshotInfo info = new SnapshotInfo(46.0, 8.0, 1000, 90, 0, 45, 40, 30, false);
        byte[] png = ExifWriter.embedInPng(encoded("png"), info);
        assertEquals('e', png[8 + 4 + 4 + 13 + 4 + 4]);
        assertEquals('X', png[8 + 4 + 4 + 13 + 4 + 5]);
        assertNotNull(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png)));

        // Issue #21: ImageMagick and the tools built on it ignore eXIf and read the older
        // "Raw profile type exif" zTXt chunk. It must be there, and must hold the same EXIF.
        byte[] exifChunk = null;
        byte[] rawProfile = null;
        int i = 8;
        while (i + 8 <= png.length) {
            int length = ((png[i] & 0xFF) << 24) | ((png[i + 1] & 0xFF) << 16)
                    | ((png[i + 2] & 0xFF) << 8) | (png[i + 3] & 0xFF);
            String type = new String(png, i + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            byte[] data = java.util.Arrays.copyOfRange(png, i + 8, i + 8 + length);
            if (type.equals("eXIf")) {
                exifChunk = data;
            } else if (type.equals("zTXt")) {
                rawProfile = data;
            }
            i += 12 + length;
        }
        assertNotNull(exifChunk, "eXIf chunk");
        assertNotNull(rawProfile, "zTXt raw profile chunk");
        String keyword = "Raw profile type exif";
        assertEquals(keyword, new String(rawProfile, 0, keyword.length(),
                java.nio.charset.StandardCharsets.ISO_8859_1));
        assertEquals(0, rawProfile[keyword.length()]);       // keyword terminator
        assertEquals(0, rawProfile[keyword.length() + 1]);   // deflate
        java.util.zip.Inflater inflater = new java.util.zip.Inflater();
        inflater.setInput(rawProfile, keyword.length() + 2, rawProfile.length - keyword.length() - 2);
        java.io.ByteArrayOutputStream text = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && inflater.needsInput()) {
                    break;
                }
                text.write(buffer, 0, n);
            }
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException(e);
        }
        String[] lines = text.toString("ISO-8859-1").split("\n");
        assertEquals("exif", lines[1]);
        int declared = Integer.parseInt(lines[2].trim());
        StringBuilder hex = new StringBuilder();
        for (int k = 3; k < lines.length; k++) {
            hex.append(lines[k].trim());
        }
        assertEquals(declared * 2, hex.length());
        byte[] payload = new byte[declared];
        for (int k = 0; k < declared; k++) {
            payload[k] = (byte) Integer.parseInt(hex.substring(2 * k, 2 * k + 2), 16);
        }
        assertEquals("Exif", new String(payload, 0, 4, java.nio.charset.StandardCharsets.ISO_8859_1));
        assertEquals(0, payload[4]);
        assertEquals(0, payload[5]);
        assertTrue(java.util.Arrays.equals(exifChunk, java.util.Arrays.copyOfRange(payload, 6, declared)),
                "the raw profile carries exactly the eXIf chunk's TIFF");
    }
}
