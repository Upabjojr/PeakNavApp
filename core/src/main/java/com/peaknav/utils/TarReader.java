package com.peaknav.utils;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads the entries of a tar archive. Just enough tar to unpack the map data, and nothing else.
 *
 * <p>This exists because commons-compress cannot be used on iOS. Three separate walls, each
 * only visible after the previous one was cleared: RoboVM's bytecode converter cannot read
 * TarArchiveEntry as compiled since 1.26; releases from 1.21 reach {@code java.nio.file} and
 * {@code java.util.function} from the static initialisers of {@code GzipUtils} and
 * {@code IOUtils}, neither of which exists on that runtime; and 1.20, the last release free of
 * both, narrowed {@code getNextEntry()}'s return type in a later version, so a {@code core}
 * compiled against the current one calls a descriptor 1.20 does not have.
 *
 * <p>Rather than pin a fourth version, the app stopped needing the library for this. Gzip
 * comes from {@code java.util.zip}, which every platform has including RoboVM, and tar is a
 * format of 512-byte headers that fits in one class. The dependency stays in the build for
 * whatever else uses it, but this path no longer risks it.
 *
 * <h2>What it handles</h2>
 *
 * <p>The archives this reads are written by bsdtar and are <b>ustar with PAX extended
 * headers</b> - every file is preceded by a {@code ././@PaxHeader} entry carrying its
 * timestamps. A reader that did not know about those would hand back entries literally named
 * {@code ././@PaxHeader}, which is exactly the sort of thing that silently produces an empty
 * map. So: PAX headers ({@code x}/{@code g}) are consumed and their {@code path=} applied when
 * present, GNU long names ({@code L}) are honoured, the ustar {@code prefix} field is joined
 * onto the name, and entry types that are neither file nor directory are skipped whole.
 *
 * <p>Not handled, because these archives contain none of it and guessing would be worse than
 * failing: sparse files, and hard or symbolic links (skipped rather than recreated).
 */
public final class TarReader implements Closeable {

    /** Tar's fixed block size. Every header is one, and every entry's data is padded to it. */
    private static final int BLOCK = 512;

    private static final int NAME_OFFSET = 0;
    private static final int NAME_LENGTH = 100;
    private static final int SIZE_OFFSET = 124;
    private static final int SIZE_LENGTH = 12;
    private static final int TYPE_OFFSET = 156;
    private static final int MAGIC_OFFSET = 257;
    private static final int PREFIX_OFFSET = 345;
    private static final int PREFIX_LENGTH = 155;

    /** One entry's metadata. The bytes come from {@link TarReader#read}. */
    public static final class Entry {
        private final String name;
        private final long size;
        private final boolean directory;

        Entry(String name, long size, boolean directory) {
            this.name = name;
            this.size = size;
            this.directory = directory;
        }

        public String getName() {
            return name;
        }

        public long getSize() {
            return size;
        }

        public boolean isDirectory() {
            return directory;
        }
    }

    private final InputStream in;
    private final byte[] header = new byte[BLOCK];

    /** Bytes of the current entry not yet read. */
    private long remaining = 0;

    /** Bytes of padding after the current entry, to reach the next block boundary. */
    private long padding = 0;

    private boolean finished = false;

    public TarReader(InputStream in) {
        this.in = in;
    }

    /**
     * Advances to the next entry, or returns null at the end of the archive.
     *
     * <p>Any part of the previous entry left unread is skipped, so a caller may ignore an
     * entry's contents entirely.
     */
    public Entry next() throws IOException {
        if (finished) {
            return null;
        }
        skipFully(remaining + padding);
        remaining = 0;
        padding = 0;

        String longName = null;
        String paxPath = null;

        while (true) {
            if (!readFully(header, BLOCK)) {
                // Truncated rather than terminated. Treat as the end: a short read here means
                // the download was cut off, and the caller's checks will notice the gap.
                finished = true;
                return null;
            }
            if (isAllZeros(header)) {
                // Two zero blocks end an archive; one is enough to stop reading entries.
                finished = true;
                return null;
            }

            long size = parseSize();
            byte type = header[TYPE_OFFSET];

            if (type == 'x' || type == 'g') {
                // PAX extended header: its *content* is the metadata, as "len key=value\n"
                // records. Only path is of any interest here.
                byte[] pax = new byte[(int) Math.min(size, 1 << 20)];
                if (!readFully(pax, pax.length)) {
                    finished = true;
                    return null;
                }
                skipFully(size - pax.length + paddingFor(size));
                String path = paxPath(pax);
                if (path != null) {
                    paxPath = path;
                }
                continue;
            }

            if (type == 'L') {
                // GNU long name: content is the next entry's name.
                byte[] nameBytes = new byte[(int) Math.min(size, 1 << 16)];
                if (!readFully(nameBytes, nameBytes.length)) {
                    finished = true;
                    return null;
                }
                skipFully(size - nameBytes.length + paddingFor(size));
                longName = trimToNul(nameBytes, 0, nameBytes.length);
                continue;
            }

            if (type == 'K') {
                // GNU long *link* name - irrelevant here, but its content must be consumed
                // or every following header would be read from the wrong offset.
                skipFully(size + paddingFor(size));
                continue;
            }

            boolean directory = type == '5';
            String name = paxPath != null ? paxPath
                    : longName != null ? longName
                    : headerName();

            if (!directory && type != '0' && type != 0) {
                // Links and devices: skip the entry whole rather than create something wrong.
                skipFully(size + paddingFor(size));
                longName = null;
                paxPath = null;
                continue;
            }

            // A directory can also be spelled as a zero-length entry whose name ends in "/".
            if (!directory && size == 0 && name.endsWith("/")) {
                directory = true;
            }

            remaining = directory ? 0 : size;
            padding = directory ? 0 : paddingFor(size);
            return new Entry(name, directory ? 0 : size, directory);
        }
    }

    /**
     * Reads the current entry's bytes, stopping at its end.
     *
     * @return the number of bytes read, or -1 at the end of this entry
     */
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (remaining <= 0) {
            return -1;
        }
        int wanted = (int) Math.min(length, remaining);
        int read = in.read(buffer, offset, wanted);
        if (read < 0) {
            // The archive promised more than it holds.
            throw new EOFException("tar entry truncated, " + remaining + " bytes missing");
        }
        remaining -= read;
        return read;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    // ---------------------------------------------------------------- header fields

    /** The ustar name, joined onto its prefix when one is present. */
    private String headerName() {
        String name = trimToNul(header, NAME_OFFSET, NAME_LENGTH);
        if (isUstar()) {
            String prefix = trimToNul(header, PREFIX_OFFSET, PREFIX_LENGTH);
            if (!prefix.isEmpty()) {
                return prefix + "/" + name;
            }
        }
        return name;
    }

    private boolean isUstar() {
        return header[MAGIC_OFFSET] == 'u' && header[MAGIC_OFFSET + 1] == 's'
                && header[MAGIC_OFFSET + 2] == 't' && header[MAGIC_OFFSET + 3] == 'a'
                && header[MAGIC_OFFSET + 4] == 'r';
    }

    /**
     * The entry size: octal digits, or GNU's base-256 form when the top bit of the first byte
     * is set (which is how sizes too large for eleven octal digits are written).
     */
    private long parseSize() throws IOException {
        if ((header[SIZE_OFFSET] & 0x80) != 0) {
            long value = header[SIZE_OFFSET] & 0x7F;
            for (int i = 1; i < SIZE_LENGTH; i++) {
                value = (value << 8) | (header[SIZE_OFFSET + i] & 0xFF);
            }
            return value;
        }
        long value = 0;
        boolean any = false;
        for (int i = 0; i < SIZE_LENGTH; i++) {
            byte b = header[SIZE_OFFSET + i];
            if (b == 0 || b == ' ') {
                if (any) {
                    break;      // trailing padding
                }
                continue;       // leading padding
            }
            if (b < '0' || b > '7') {
                throw new IOException("tar header has a non-octal size digit: " + (char) b);
            }
            value = value * 8 + (b - '0');
            any = true;
        }
        return value;
    }

    /** The {@code path=} of a PAX header block, or null when it carries none. */
    private static String paxPath(byte[] pax) {
        // Records are "<length> <key>=<value>\n", the length counting the whole record.
        int at = 0;
        while (at < pax.length) {
            int space = at;
            while (space < pax.length && pax[space] != ' ') {
                space++;
            }
            if (space >= pax.length) {
                return null;
            }
            int length;
            try {
                length = Integer.parseInt(new String(pax, at, space - at, "UTF-8").trim());
            } catch (Exception malformed) {
                return null;
            }
            if (length <= 0 || at + length > pax.length) {
                return null;
            }
            String record;
            try {
                record = new String(pax, space + 1, at + length - (space + 1), "UTF-8");
            } catch (Exception badEncoding) {
                return null;
            }
            if (record.startsWith("path=")) {
                String value = record.substring("path=".length());
                // The record ends with the newline that terminated it.
                return value.endsWith("\n") ? value.substring(0, value.length() - 1) : value;
            }
            at += length;
        }
        return null;
    }

    // ---------------------------------------------------------------- stream helpers

    private static long paddingFor(long size) {
        long overhang = size % BLOCK;
        return overhang == 0 ? 0 : BLOCK - overhang;
    }

    private static boolean isAllZeros(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String trimToNul(byte[] bytes, int offset, int length) {
        int end = offset;
        int limit = offset + length;
        while (end < limit && bytes[end] != 0) {
            end++;
        }
        try {
            return new String(bytes, offset, end - offset, "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            // UTF-8 is required of every JVM.
            throw new IllegalStateException(impossible);
        }
    }

    /** Fills {@code length} bytes, or reports that the stream ended first. */
    private boolean readFully(byte[] buffer, int length) throws IOException {
        int at = 0;
        while (at < length) {
            int read = in.read(buffer, at, length - at);
            if (read < 0) {
                return false;
            }
            at += read;
        }
        return true;
    }

    /**
     * Skips exactly {@code count} bytes.
     *
     * <p>Reads rather than trusting {@link InputStream#skip} alone: on a
     * {@code GZIPInputStream} skip is implemented by reading anyway, and a short skip that
     * went unnoticed would desynchronise every header after it.
     */
    private void skipFully(long count) throws IOException {
        long left = count;
        byte[] scratch = null;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped > 0) {
                left -= skipped;
                continue;
            }
            if (scratch == null) {
                scratch = new byte[(int) Math.min(left, 8192)];
            }
            int read = in.read(scratch, 0, (int) Math.min(left, scratch.length));
            if (read < 0) {
                return;     // truncated; next() will see the short header and stop
            }
            left -= read;
        }
    }
}
