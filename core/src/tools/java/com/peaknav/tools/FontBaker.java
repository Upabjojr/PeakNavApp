package com.peaknav.tools;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.g2d.PixmapPacker;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.utils.GdxNativesLoader;
import com.peaknav.viewer.widgets.BakedFontFile;
import com.peaknav.viewer.widgets.FontSpecs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Bakes the app's fonts when the app is built, so the app does not make them when it starts:
 * with Greek and Cyrillic for the translations, FreeType took a second on a desktop to lay out
 * the seven fonts, several on a phone, every launch.
 *
 * <p>Each font of {@link FontSpecs} is laid out by FreeType - the same library and parameters
 * the app would use - at its size for {@link FontSpecs#BAKED_SHORT_SIDE}, into square pages of
 * {@link FontSpecs#PAGE_SIZE}. The pages are written as {@code <name>_<n>.png} and the metrics
 * as {@code <name>.bin} ({@link BakedFontFile}). Nothing here needs a GPU.
 *
 * <pre>./gradlew :core:bakeFonts</pre>
 * runs it into assets/fonts_baked/, and every app build does that first.
 */
public final class FontBaker {

    private FontBaker() {
    }

    public static void main(String[] args) throws IOException {
        File assets = new File(args.length > 0 ? args[0] : "assets");
        File out = new File(assets, FontSpecs.DIRECTORY);
        GdxNativesLoader.load();
        deleteRecursively(out);
        if (!out.mkdirs()) {
            throw new IOException("cannot create " + out);
        }
        FreeTypeFontGenerator generator = new FreeTypeFontGenerator(
                new FileHandle(new File(assets, FontSpecs.TTF)));
        try {
            for (FontSpecs.Spec spec : FontSpecs.ALL) {
                bake(generator, spec, out);
            }
        } finally {
            generator.dispose();
        }
    }

    private static void bake(FreeTypeFontGenerator generator, FontSpecs.Spec spec, File out) throws IOException {
        FreeTypeFontGenerator.FreeTypeFontParameter parameter =
                spec.parameter(spec.bakedSize(), FontSpecs.BAKED_SHORT_SIDE);
        PixmapPacker packer = new PixmapPacker(FontSpecs.PAGE_SIZE, FontSpecs.PAGE_SIZE,
                Pixmap.Format.RGBA8888, FontSpecs.PAGE_PADDING, false);
        // The empty page in the colour of the glyphs' outermost pixels, at no opacity - not the
        // packer's transparent black, which the mipmaps would average into a dark fringe round
        // white glyphs. That colour is the outline's, where the font has one.
        com.badlogic.gdx.graphics.Color edge = parameter.borderWidth > 0f ? parameter.borderColor : parameter.color;
        packer.setTransparentColor(new com.badlogic.gdx.graphics.Color(edge.r, edge.g, edge.b, 0f));
        parameter.packer = packer;
        FreeTypeFontGenerator.FreeTypeBitmapFontData data = generator.generateData(parameter);
        int pages = packer.getPages().size;
        for (int i = 0; i < pages; i++) {
            PixmapIO.writePNG(new FileHandle(new File(out, spec.name + "_" + i + ".png")),
                    packer.getPages().get(i).getPixmap());
        }
        try (OutputStream stream = new FileOutputStream(new File(out, spec.name + ".bin"))) {
            BakedFontFile.write(data, pages, stream);
        }
        packer.dispose();
        System.out.println("baked " + spec.name + ": " + spec.bakedSize() + " px, " + pages + " page(s)");
    }

    private static void deleteRecursively(File file) throws IOException {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (file.exists() && !file.delete()) {
            throw new IOException("cannot delete " + file);
        }
    }
}
