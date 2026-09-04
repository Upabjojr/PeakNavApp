package com.peaknav.utils;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;

/**
 * Puts a {@link SnapshotInfo} into an encoded picture as EXIF metadata: the GPS block
 * (latitude, longitude, altitude, the direction the picture looks in), the 35 mm-equivalent
 * focal length for its field of view, the picture size, and a one-line description with
 * the pose in words. For a JPEG that is an APP1 segment after the start of the file; for
 * a PNG, an {@code eXIf} chunk after the header (PNG 1.5, read by exiftool, macOS, Android
 * and most photo tools). Pure Java, written from scratch: a few dozen bytes of TIFF
 * structure, nothing a library is needed for.
 *
 * <p>What is written is what {@link ExifReader} reads, so a picture shared from the app and
 * loaded back into it comes with its position, heading and field of view.
 */
public final class ExifWriter {

    private static final int TYPE_BYTE = 1, TYPE_ASCII = 2, TYPE_SHORT = 3, TYPE_LONG = 4,
            TYPE_RATIONAL = 5, TYPE_UNDEFINED = 7;

    private ExifWriter() {
    }

    /** The JPEG with an EXIF APP1 segment inserted (after a leading JFIF APP0 when there is one). */
    public static byte[] embedInJpeg(byte[] jpeg, SnapshotInfo info) {
        if (jpeg == null || jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
            return jpeg;
        }
        byte[] tiff = tiff(info);
        byte[] payload = new byte[6 + tiff.length];
        payload[0] = 'E';
        payload[1] = 'x';
        payload[2] = 'i';
        payload[3] = 'f';
        System.arraycopy(tiff, 0, payload, 6, tiff.length);
        int segmentLength = payload.length + 2;
        if (segmentLength > 0xFFFF) {
            return jpeg;
        }
        int insertAt = 2;
        // JFIF's APP0 wants to be first; EXIF goes right after it
        if (jpeg.length > 4 && (jpeg[2] & 0xFF) == 0xFF && (jpeg[3] & 0xFF) == 0xE0) {
            int len = ((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF);
            insertAt = Math.min(jpeg.length, 4 + len);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length + segmentLength + 2);
        out.write(jpeg, 0, insertAt);
        out.write(0xFF);
        out.write(0xE1);
        out.write((segmentLength >> 8) & 0xFF);
        out.write(segmentLength & 0xFF);
        out.write(payload, 0, payload.length);
        out.write(jpeg, insertAt, jpeg.length - insertAt);
        return out.toByteArray();
    }

    /** The PNG with an {@code eXIf} chunk inserted right after {@code IHDR}. */
    public static byte[] embedInPng(byte[] png, SnapshotInfo info) {
        if (png == null || png.length < 33 || (png[0] & 0xFF) != 0x89 || png[1] != 'P') {
            return png;
        }
        // signature (8) + IHDR chunk: length (4) + type (4) + 13 data + CRC (4)
        int insertAt = 8 + 4 + 4 + 13 + 4;
        byte[] tiff = tiff(info);
        byte[] type = {'e', 'X', 'I', 'f'};
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(tiff);
        ByteArrayOutputStream out = new ByteArrayOutputStream(png.length + tiff.length + 12);
        out.write(png, 0, insertAt);
        writeInt(out, tiff.length);
        out.write(type, 0, 4);
        out.write(tiff, 0, tiff.length);
        writeInt(out, (int) crc.getValue());
        out.write(png, insertAt, png.length - insertAt);
        return out.toByteArray();
    }

    /** The TIFF structure (big-endian) that both containers carry. */
    static byte[] tiff(SnapshotInfo info) {
        List<Entry> ifd0 = new ArrayList<Entry>();
        List<Entry> exif = new ArrayList<Entry>();
        List<Entry> gps = new ArrayList<Entry>();

        ifd0.add(Entry.ascii(0x010E, info.description()));
        ifd0.add(Entry.ascii(0x0131, "PeakNav"));
        ifd0.add(Entry.ascii(0x0132, new java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.ENGLISH)
                .format(new java.util.Date())));

        exif.add(new Entry(0x9000, TYPE_UNDEFINED, new byte[]{'0', '2', '3', '2'}, 4));
        exif.add(Entry.longValue(0xA002, info.width));
        exif.add(Entry.longValue(0xA003, info.height));
        int f35 = info.focalLength35mm();
        if (f35 > 0 && f35 < 65536) {
            exif.add(Entry.shortValue(0xA405, f35));
        }

        gps.add(new Entry(0x0000, TYPE_BYTE, new byte[]{2, 3, 0, 0}, 4));
        gps.add(Entry.ascii(0x0001, info.latitude >= 0 ? "N" : "S"));
        gps.add(Entry.degrees(0x0002, Math.abs(info.latitude)));
        gps.add(Entry.ascii(0x0003, info.longitude >= 0 ? "E" : "W"));
        gps.add(Entry.degrees(0x0004, Math.abs(info.longitude)));
        if (!Double.isNaN(info.altitudeMeters)) {
            gps.add(new Entry(0x0005, TYPE_BYTE, new byte[]{(byte) (info.altitudeMeters >= 0 ? 0 : 1)}, 1));
            gps.add(Entry.rational(0x0006, Math.abs(info.altitudeMeters), 100));
        }
        gps.add(Entry.ascii(0x0010, "T"));
        gps.add(Entry.rational(0x0011, info.bearingDeg, 100));

        // pointers to the sub-IFDs, whose places follow from the sizes
        int ifd0Size = size(ifd0.size() + 2);
        int exifOffset = 8 + ifd0Size;
        int gpsOffset = exifOffset + size(exif.size());
        ifd0.add(Entry.longValue(0x8769, exifOffset));
        ifd0.add(Entry.longValue(0x8825, gpsOffset));
        int dataStart = gpsOffset + size(gps.size());

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        body.write('M');
        body.write('M');
        body.write(0);
        body.write(42);
        writeInt(body, 8);
        writeIfd(body, ifd0, data, dataStart);
        writeIfd(body, exif, data, dataStart);
        writeIfd(body, gps, data, dataStart);
        byte[] d = data.toByteArray();
        body.write(d, 0, d.length);
        return body.toByteArray();
    }

    private static int size(int entries) {
        return 2 + 12 * entries + 4;
    }

    private static void writeIfd(ByteArrayOutputStream out, List<Entry> entries, ByteArrayOutputStream data, int dataStart) {
        Collections.sort(entries, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return a.tag - b.tag;
            }
        });
        out.write((entries.size() >> 8) & 0xFF);
        out.write(entries.size() & 0xFF);
        for (Entry e : entries) {
            out.write((e.tag >> 8) & 0xFF);
            out.write(e.tag & 0xFF);
            out.write((e.type >> 8) & 0xFF);
            out.write(e.type & 0xFF);
            writeInt(out, e.count);
            if (e.value.length <= 4) {
                out.write(e.value, 0, e.value.length);
                for (int k = e.value.length; k < 4; k++) {
                    out.write(0);
                }
            } else {
                if (data.size() % 2 == 1) {
                    data.write(0);   // values start on even offsets
                }
                writeInt(out, dataStart + data.size());
                data.write(e.value, 0, e.value.length);
            }
        }
        writeInt(out, 0);   // no next IFD
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static final class Entry {
        final int tag;
        final int type;
        final byte[] value;
        final int count;

        Entry(int tag, int type, byte[] value, int count) {
            this.tag = tag;
            this.type = type;
            this.value = value;
            this.count = count;
        }

        static Entry ascii(int tag, String text) {
            byte[] chars;
            try {
                chars = text.getBytes("US-ASCII");
            } catch (java.io.UnsupportedEncodingException e) {
                chars = text.getBytes();
            }
            byte[] value = new byte[chars.length + 1];
            System.arraycopy(chars, 0, value, 0, chars.length);
            return new Entry(tag, TYPE_ASCII, value, value.length);
        }

        static Entry shortValue(int tag, int v) {
            return new Entry(tag, TYPE_SHORT, new byte[]{(byte) ((v >> 8) & 0xFF), (byte) (v & 0xFF)}, 1);
        }

        static Entry longValue(int tag, int v) {
            return new Entry(tag, TYPE_LONG, new byte[]{
                    (byte) ((v >>> 24) & 0xFF), (byte) ((v >>> 16) & 0xFF), (byte) ((v >>> 8) & 0xFF), (byte) (v & 0xFF)}, 1);
        }

        /** One rational: {@code value} to {@code 1/denominator}. */
        static Entry rational(int tag, double value, int denominator) {
            long num = Math.round(value * denominator);
            return new Entry(tag, TYPE_RATIONAL, rationalBytes(new long[]{num, denominator}), 1);
        }

        /** Degrees, minutes and seconds (to 1/100 s) as three rationals. */
        static Entry degrees(int tag, double degrees) {
            int d = (int) degrees;
            double rest = (degrees - d) * 60;
            int m = (int) rest;
            long s100 = Math.round((rest - m) * 60 * 100);
            if (s100 >= 6000) {
                s100 -= 6000;
                m++;
            }
            if (m >= 60) {
                m -= 60;
                d++;
            }
            return new Entry(tag, TYPE_RATIONAL, rationalBytes(new long[]{d, 1, m, 1, s100, 100}), 3);
        }

        private static byte[] rationalBytes(long[] pairs) {
            byte[] out = new byte[pairs.length * 4];
            for (int i = 0; i < pairs.length; i++) {
                long v = pairs[i];
                out[i * 4] = (byte) ((v >>> 24) & 0xFF);
                out[i * 4 + 1] = (byte) ((v >>> 16) & 0xFF);
                out[i * 4 + 2] = (byte) ((v >>> 8) & 0xFF);
                out[i * 4 + 3] = (byte) (v & 0xFF);
            }
            return out;
        }
    }
}
