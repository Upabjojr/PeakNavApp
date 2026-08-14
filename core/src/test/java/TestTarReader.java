import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.utils.TarReader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tar reader that replaced commons-compress on the download path.
 *
 * <p>It was written because that library cannot run on iOS at all, which means these entries
 * are now parsed by hand - and a tar bug does not announce itself. A misread size or a
 * mishandled header type desynchronises everything after it, and the visible symptom is an
 * app with no map, several layers away from the cause.
 *
 * <p>The case that matters most is the PAX one. The real archives are written by bsdtar and
 * put a {@code ././@PaxHeader} entry before every file; a reader that did not consume those
 * would return entries by that name and write the tiles nowhere useful. The headers here are
 * built byte by byte on purpose, so the test states the on-disk format rather than trusting
 * whatever a writing library happens to emit.
 */
class TestTarReader {

    private static final int BLOCK = 512;

    @Test
    @DisplayName("Files and directories are read back with their names and contents")
    void readsPlainEntries() throws Exception {
        Map<String, byte[]> written = new LinkedHashMap<>();
        written.put("dir/", null);
        written.put("dir/small.txt", "hello".getBytes("UTF-8"));
        written.put("dir/exact.bin", new byte[BLOCK]);          // exactly one block, no padding
        written.put("dir/spans.bin", bytes(BLOCK + 7));         // forces padding to be skipped

        Map<String, byte[]> read = extract(tar(written));

        assertEquals(3, read.size(), "the directory is not a content entry");
        assertArrayEquals("hello".getBytes("UTF-8"), read.get("dir/small.txt"));
        assertArrayEquals(new byte[BLOCK], read.get("dir/exact.bin"));
        assertArrayEquals(bytes(BLOCK + 7), read.get("dir/spans.bin"));
    }

    @Test
    @DisplayName("A PAX header supplies the name, and never appears as an entry itself")
    void paxHeaderNamesTheNextEntry() throws Exception {
        String realName = "elev_tiles/zoom_08/x_00132/y_00088/elev.z08.x00132.y00088.f000.jpg";
        byte[] content = bytes(1234);

        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        // Exactly the shape bsdtar produces: a PAX record block named ././@PaxHeader, then
        // the real entry. The ustar name is deliberately different, so a reader that ignored
        // the PAX record would be caught by this test rather than quietly using the wrong one.
        String record = paxRecord("path=" + realName);
        tar.write(header("././@PaxHeader", record.length(), 'x'));
        tar.write(padded(record.getBytes("UTF-8")));
        tar.write(header("wrong/name.jpg", content.length, '0'));
        tar.write(padded(content));
        tar.write(new byte[2 * BLOCK]);

        Map<String, byte[]> read = extract(tar.toByteArray());

        assertEquals(1, read.size(), "the PAX block must not surface as an entry");
        assertNotNull(read.get(realName), "the PAX path must win over the ustar name");
        assertArrayEquals(content, read.get(realName));
    }

    @Test
    @DisplayName("A ustar prefix is joined onto the name")
    void joinsUstarPrefix() throws Exception {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        byte[] content = "deep".getBytes("UTF-8");
        byte[] head = header("y_00088.png", content.length, '0');
        writeString(head, 345, 155, "elev_tiles/zoom_08/x_00132");   // the prefix field
        fixChecksum(head);
        tar.write(head);
        tar.write(padded(content));
        tar.write(new byte[2 * BLOCK]);

        Map<String, byte[]> read = extract(tar.toByteArray());

        assertArrayEquals(content, read.get("elev_tiles/zoom_08/x_00132/y_00088.png"));
    }

    @Test
    @DisplayName("A GNU long-name block supplies the name")
    void honoursGnuLongName() throws Exception {
        StringBuilder longName = new StringBuilder("elev_tiles/");
        while (longName.length() < 160) {
            longName.append("nested/");
        }
        longName.append("tile.png");
        byte[] content = "x".getBytes("UTF-8");

        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        byte[] nameBytes = longName.toString().getBytes("UTF-8");
        tar.write(header("././@LongLink", nameBytes.length, 'L'));
        tar.write(padded(nameBytes));
        tar.write(header("truncated", content.length, '0'));
        tar.write(padded(content));
        tar.write(new byte[2 * BLOCK]);

        Map<String, byte[]> read = extract(tar.toByteArray());

        assertArrayEquals(content, read.get(longName.toString()));
    }

    @Test
    @DisplayName("Entries that are neither file nor directory are skipped whole")
    void skipsOtherEntryTypes() throws Exception {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        byte[] linkPayload = bytes(600);   // a payload long enough to span two blocks
        tar.write(header("some.link", linkPayload.length, '2'));   // symlink
        tar.write(padded(linkPayload));
        byte[] content = "after".getBytes("UTF-8");
        tar.write(header("after.txt", content.length, '0'));
        tar.write(padded(content));
        tar.write(new byte[2 * BLOCK]);

        Map<String, byte[]> read = extract(tar.toByteArray());

        // The point is not merely that the link is absent: if its payload were not skipped by
        // exactly the right number of blocks, "after.txt" would be unreadable too.
        assertEquals(1, read.size());
        assertArrayEquals(content, read.get("after.txt"));
    }

    @Test
    @DisplayName("An entry whose contents are ignored does not disturb the next one")
    void skippingContentKeepsAlignment() throws Exception {
        Map<String, byte[]> written = new LinkedHashMap<>();
        written.put("a.bin", bytes(1000));
        written.put("b.bin", bytes(20));

        List<String> names = new ArrayList<>();
        TarReader reader = new TarReader(new ByteArrayInputStream(tar(written)));
        TarReader.Entry entry;
        while ((entry = reader.next()) != null) {
            names.add(entry.getName());     // deliberately never reading the bytes
        }

        assertEquals(2, names.size());
        assertEquals("a.bin", names.get(0));
        assertEquals("b.bin", names.get(1));
    }

    /**
     * Truncation must throw, never read as a shorter archive.
     *
     * <p>This is the contract the whole download path leans on: unpacking runs inside a
     * catch whose recovery is "discard the file and fetch it again", and commons-compress
     * (which the other platforms shipped with for years) threw on every cut-off archive.
     * A reader that returned null instead would mark a half-unpacked region as downloaded,
     * permanently - the missing tiles would never be refetched.
     */
    @Test
    @DisplayName("An archive cut off mid-header throws instead of ending quietly")
    void truncatedHeaderThrows() throws Exception {
        Map<String, byte[]> written = new LinkedHashMap<>();
        written.put("a.bin", bytes(700));
        byte[] whole = tar(written);
        // The cut lands inside the very first header block.
        byte[] cut = new byte[BLOCK / 2];
        System.arraycopy(whole, 0, cut, 0, cut.length);

        TarReader reader = new TarReader(new ByteArrayInputStream(cut));
        assertThrows(IOException.class, reader::next);
    }

    @Test
    @DisplayName("An entry whose data is cut off throws from read")
    void truncatedDataThrows() throws Exception {
        Map<String, byte[]> written = new LinkedHashMap<>();
        written.put("a.bin", bytes(1000));
        byte[] whole = tar(written);
        byte[] cut = new byte[BLOCK + 200];     // header + 200 of the 1000 promised bytes
        System.arraycopy(whole, 0, cut, 0, cut.length);

        TarReader reader = new TarReader(new ByteArrayInputStream(cut));
        TarReader.Entry entry = reader.next();
        assertEquals("a.bin", entry.getName());
        byte[] buffer = new byte[512];
        assertThrows(EOFException.class, () -> {
            while (reader.read(buffer, 0, buffer.length) != -1) {
                // draining; the missing tail must surface as a throw, not a quiet -1
            }
        });
    }

    @Test
    @DisplayName("A truncated entry throws even when the caller skips its contents")
    void truncatedSkipThrows() throws Exception {
        Map<String, byte[]> written = new LinkedHashMap<>();
        written.put("a.bin", bytes(1000));
        written.put("b.bin", bytes(20));
        byte[] whole = tar(written);
        byte[] cut = new byte[BLOCK + 200];
        System.arraycopy(whole, 0, cut, 0, cut.length);

        TarReader reader = new TarReader(new ByteArrayInputStream(cut));
        assertEquals("a.bin", reader.next().getName());
        // Never reading a.bin's bytes: the skip to the next header crosses the cut, and that
        // must throw - this is exactly the path that would otherwise fake a clean end.
        assertThrows(EOFException.class, reader::next);
    }

    @Test
    @DisplayName("EOF exactly at a block boundary, with no zero blocks, is a clean end")
    void missingTerminatorIsAcceptedAtABoundary() throws Exception {
        // Parity with commons-compress: some writers omit the two terminating zero blocks,
        // and the other platforms have always read such archives as complete.
        Map<String, byte[]> written = new LinkedHashMap<>();
        written.put("a.bin", bytes(700));
        byte[] whole = tar(written);
        byte[] unterminated = new byte[whole.length - 2 * BLOCK];
        System.arraycopy(whole, 0, unterminated, 0, unterminated.length);

        Map<String, byte[]> read = extract(unterminated);
        assertArrayEquals(bytes(700), read.get("a.bin"));
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, byte[]> extract(byte[] archive) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        TarReader reader = new TarReader(new ByteArrayInputStream(archive));
        TarReader.Entry entry;
        while ((entry = reader.next()) != null) {
            if (entry.isDirectory()) {
                continue;
            }
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            byte[] buffer = new byte[64];
            int read;
            while ((read = reader.read(buffer, 0, buffer.length)) != -1) {
                content.write(buffer, 0, read);
            }
            assertEquals(entry.getSize(), content.size(),
                    "the entry gave up a different number of bytes than its header promised");
            out.put(entry.getName(), content.toByteArray());
        }
        return out;
    }

    /** A ustar archive of the given entries; a null value means a directory. */
    private static byte[] tar(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            boolean directory = e.getValue() == null;
            out.write(header(e.getKey(), directory ? 0 : e.getValue().length,
                    directory ? '5' : '0'));
            if (!directory) {
                out.write(padded(e.getValue()));
            }
        }
        out.write(new byte[2 * BLOCK]);
        return out.toByteArray();
    }

    private static byte[] header(String name, long size, char type) {
        byte[] head = new byte[BLOCK];
        writeString(head, 0, 100, name);
        writeString(head, 100, 8, "0000644");           // mode
        writeString(head, 108, 8, "0000000");           // uid
        writeString(head, 116, 8, "0000000");           // gid
        writeString(head, 124, 12, String.format("%011o", size));
        writeString(head, 136, 12, "00000000000");      // mtime
        head[156] = (byte) type;
        writeString(head, 257, 6, "ustar");
        head[263] = '0';
        head[264] = '0';
        fixChecksum(head);
        return head;
    }

    /** The header checksum, computed with the checksum field itself read as spaces. */
    private static void fixChecksum(byte[] head) {
        for (int i = 148; i < 156; i++) {
            head[i] = ' ';
        }
        int sum = 0;
        for (byte b : head) {
            sum += b & 0xFF;
        }
        writeString(head, 148, 7, String.format("%06o", sum));
        head[154] = 0;
        head[155] = ' ';
    }

    private static void writeString(byte[] target, int offset, int length, String value) {
        byte[] raw;
        try {
            raw = value.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
        int n = Math.min(raw.length, length - 1);
        System.arraycopy(raw, 0, target, offset, n);
        for (int i = offset + n; i < offset + length; i++) {
            target[i] = 0;
        }
    }

    /** Content followed by zero padding to the next block boundary. */
    private static byte[] padded(byte[] content) {
        int total = ((content.length + BLOCK - 1) / BLOCK) * BLOCK;
        byte[] out = new byte[total];
        System.arraycopy(content, 0, out, 0, content.length);
        return out;
    }

    /** One PAX record: "<total length> <text>\n", the length counting itself. */
    private static String paxRecord(String keyValue) {
        int length = keyValue.length() + 2;         // space + newline
        int total = length + String.valueOf(length).length();
        // The digit count can grow when the length is written in; settle it.
        while (String.valueOf(total).length() + length != total) {
            total = String.valueOf(total).length() + length;
        }
        return total + " " + keyValue + "\n";
    }

    private static byte[] bytes(int count) {
        byte[] out = new byte[count];
        for (int i = 0; i < count; i++) {
            out[i] = (byte) (i % 251);
        }
        return out;
    }
}
