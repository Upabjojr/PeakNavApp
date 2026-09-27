package com.peaknav.viewer.widgets;

import com.badlogic.gdx.graphics.g2d.BitmapFont;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * A baked font's metrics: everything FreeType measured when it laid the glyphs out, written by
 * the build (com.peaknav.tools.FontBaker) and read back by {@link StyleSingleton}. The pictures
 * of the glyphs are PNG pages beside it, {@code <name>_<page>.png}.
 *
 * <p>Not BMFont's .fnt text format: that measures glyphs from other origins than libGDX keeps
 * them in memory, and converting one way and back is where fonts come out shifted by a pixel.
 * This file holds libGDX's own numbers, field for field, so a baked font is exactly the font
 * FreeType would have made on the device.
 */
public final class BakedFontFile {

    private BakedFontFile() {
    }

    /** Bumped whenever the layout of the file changes. */
    private static final int VERSION = 1;
    private static final int MAGIC = 0x504E4631;   // "PNF1"

    /** A font read back: its metrics, and how many atlas pages it has. */
    public static final class Loaded {
        public final BitmapFont.BitmapFontData data;
        public final int pages;

        Loaded(BitmapFont.BitmapFontData data, int pages) {
            this.data = data;
            this.pages = pages;
        }
    }

    public static void write(BitmapFont.BitmapFontData data, int pages, OutputStream stream) throws IOException {
        DataOutputStream out = new DataOutputStream(stream);
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(pages);
        out.writeFloat(data.padTop);
        out.writeFloat(data.padRight);
        out.writeFloat(data.padBottom);
        out.writeFloat(data.padLeft);
        out.writeFloat(data.lineHeight);
        out.writeFloat(data.capHeight);
        out.writeFloat(data.ascent);
        out.writeFloat(data.descent);
        out.writeFloat(data.down);
        out.writeFloat(data.blankLineScale);
        out.writeFloat(data.spaceXadvance);
        out.writeFloat(data.xHeight);
        out.writeFloat(data.cursorX);

        List<BitmapFont.Glyph> glyphs = new ArrayList<>();
        for (BitmapFont.Glyph[] page : data.glyphs) {
            if (page == null) {
                continue;
            }
            for (BitmapFont.Glyph glyph : page) {
                if (glyph != null) {
                    glyphs.add(glyph);
                }
            }
        }
        out.writeInt(glyphs.size());
        for (BitmapFont.Glyph g : glyphs) {
            writeGlyph(out, g);
        }
        // The box drawn for a character the font has no glyph for: FreeType keeps it apart.
        out.writeBoolean(data.missingGlyph != null);
        if (data.missingGlyph != null) {
            writeGlyph(out, data.missingGlyph);
        }
        // Kerning, the pairs that have any, from every glyph to every other.
        List<int[]> pairs = new ArrayList<>();
        for (BitmapFont.Glyph first : glyphs) {
            for (BitmapFont.Glyph second : glyphs) {
                int kerning = first.getKerning((char) second.id);
                if (kerning != 0) {
                    pairs.add(new int[]{first.id, second.id, kerning});
                }
            }
        }
        out.writeInt(pairs.size());
        for (int[] pair : pairs) {
            out.writeChar(pair[0]);
            out.writeChar(pair[1]);
            out.writeByte(pair[2]);
        }
        out.flush();
    }

    public static Loaded read(InputStream stream) throws IOException {
        DataInputStream in = new DataInputStream(stream);
        if (in.readInt() != MAGIC || in.readInt() != VERSION) {
            throw new IOException("not a baked font of this version");
        }
        int pages = in.readInt();
        BitmapFont.BitmapFontData data = new BitmapFont.BitmapFontData();
        data.imagePaths = new String[pages];
        for (int i = 0; i < pages; i++) {
            data.imagePaths[i] = "";
        }
        data.padTop = in.readFloat();
        data.padRight = in.readFloat();
        data.padBottom = in.readFloat();
        data.padLeft = in.readFloat();
        data.lineHeight = in.readFloat();
        data.capHeight = in.readFloat();
        data.ascent = in.readFloat();
        data.descent = in.readFloat();
        data.down = in.readFloat();
        data.blankLineScale = in.readFloat();
        data.spaceXadvance = in.readFloat();
        data.xHeight = in.readFloat();
        data.cursorX = in.readFloat();

        int count = in.readInt();
        for (int i = 0; i < count; i++) {
            BitmapFont.Glyph g = readGlyph(in);
            data.setGlyph(g.id, g);
        }
        if (in.readBoolean()) {
            data.missingGlyph = readGlyph(in);
        }
        int pairs = in.readInt();
        for (int i = 0; i < pairs; i++) {
            char first = in.readChar();
            char second = in.readChar();
            byte kerning = in.readByte();
            BitmapFont.Glyph glyph = data.getGlyph(first);
            if (glyph != null) {
                glyph.setKerning(second, kerning);
            }
        }
        return new Loaded(data, pages);
    }

    private static void writeGlyph(DataOutputStream out, BitmapFont.Glyph g) throws IOException {
        out.writeInt(g.id);
        out.writeInt(g.srcX);
        out.writeInt(g.srcY);
        out.writeInt(g.width);
        out.writeInt(g.height);
        out.writeInt(g.xoffset);
        out.writeInt(g.yoffset);
        out.writeInt(g.xadvance);
        out.writeInt(g.page);
        out.writeBoolean(g.fixedWidth);
    }

    private static BitmapFont.Glyph readGlyph(DataInputStream in) throws IOException {
        BitmapFont.Glyph g = new BitmapFont.Glyph();
        g.id = in.readInt();
        g.srcX = in.readInt();
        g.srcY = in.readInt();
        g.width = in.readInt();
        g.height = in.readInt();
        g.xoffset = in.readInt();
        g.yoffset = in.readInt();
        g.xadvance = in.readInt();
        g.page = in.readInt();
        g.fixedWidth = in.readBoolean();
        return g;
    }
}
