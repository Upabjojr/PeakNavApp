package com.peaknav.utils;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;


import javax.imageio.ImageIO;

public class UtilsOSDesktop extends UtilsOSDep {

    private BufferedImage getBufferedImage(Pixmap pixmap) {
        final int imageType;
        final Pixmap.Format format = pixmap.getFormat();
        if (format == Pixmap.Format.Alpha)
            imageType = BufferedImage.TYPE_BYTE_GRAY;
        else if (format == Pixmap.Format.RGB888)
            imageType = BufferedImage.TYPE_INT_RGB;
        else if (format == Pixmap.Format.LuminanceAlpha)
            throw new RuntimeException("image format " + pixmap.getFormat().name() + " not supported");
            // imageType = BufferedImage.TYPE_USHORT_GRAY;
        else
            throw new RuntimeException("image format " + pixmap.getFormat().name() + " not supported");

        BufferedImage bufferedImage = new BufferedImage(pixmap.getWidth(), pixmap.getHeight(), imageType);

        final boolean flipY = false;
        for (int y = 0, h = pixmap.getHeight(); y < h; y++) {
            int py = flipY ? (h - y - 1) : y;
            for (int px = 0, x = 0; px < pixmap.getWidth(); px++) {
                int pixel = pixmap.getPixel(px, py);
                if (format == Pixmap.Format.Alpha) {
                    pixel &= 0xFF;
                    bufferedImage.getRaster().setPixel(px, py, new int[]{pixel});
                } else if (format == Pixmap.Format.RGB888) {
                    int r = pixel >>> 24;
                    int g = (pixel & 0xFF0000) >>> 16;
                    int b = (pixel & 0xFF00) >>> 8;
                    bufferedImage.getRaster().setPixel(px, py, new int[]{r, g, b});
                } /* NOT WORKING:
                else if (format == Pixmap.Format.LuminanceAlpha) {
                    int luminance = (pixel & 0x0000ff00) >>> 8;
                    int alpha = pixel & 0x000000ff;
                    bufferedImage.getRaster().setPixel(px, py, new int[]{luminance, alpha});
                } */
            }
        }

        return bufferedImage;
    }

    @Override
    public void savePixmapAsPng(File output, Pixmap pixmap) {
        // TODO: 8bit images should be saved as 8bit PNGs
        assert output.toString().toUpperCase().endsWith(".PNG");

        if (pixmap.getFormat() == Pixmap.Format.LuminanceAlpha) {
            PixmapIO.writePNG(new FileHandle(output), pixmap);
            return;
        }

        BufferedImage bufferedImage = getBufferedImage(pixmap);
        try {
            ImageIO.write(bufferedImage, "png", output);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void savePixmapAsJpg(File output, Pixmap pixmap) {
        assert output.toString().toUpperCase().endsWith(".JPG") |
                output.toString().toUpperCase().endsWith(".JPEG");

        BufferedImage bufferedImage = getBufferedImage(pixmap);

        try {
            ImageIO.write(bufferedImage, "jpg", output);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
