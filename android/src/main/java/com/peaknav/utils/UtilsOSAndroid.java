package com.peaknav.utils;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

import ar.com.hjg.pngj.ImageInfo;
import ar.com.hjg.pngj.PngWriter;

public class UtilsOSAndroid extends UtilsOSDep {
    @Override
    public void savePixmapAsPng(File output, Pixmap pixmap) {
        assert output.toString().toUpperCase().endsWith(".PNG");

        if (pixmap.getFormat() == Pixmap.Format.Alpha) {
            int c = pixmap.getWidth();
            int r = pixmap.getHeight();
            ImageInfo imgInfo = new ImageInfo(c, r, 8, false, true, false);
            PngWriter pngWriter = new PngWriter(output, imgInfo);

            for (int i = 0; i < r; i++) {
                int[] arr = new int[c];
                for (int j = 0; j < c; j++) {
                    int pixel = pixmap.getPixel(j, i);
                    pixel &= 0xFF;
                    arr[j] = pixel;
                }
                pngWriter.writeRowInt(arr);
            }
            pngWriter.end();
        } else {
            PixmapIO.writePNG(new FileHandle(output), pixmap);
        }
    }

    @Override
    public void savePixmapAsJpg(File output, Pixmap pixmap) {
        assert output.toString().toUpperCase().endsWith(".JPG") |
                output.toString().toUpperCase().endsWith(".JPEG");

        Bitmap bitmap = getBitmapFromPixmap(pixmap);
        FileOutputStream outputStream = null;
        int quality = 80;
        try {
            outputStream = new FileOutputStream(output);
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
    }

    private Bitmap getBitmapFromPixmap(Pixmap pixmap) {

        Bitmap.Config config = null;
        Pixmap.Format format = pixmap.getFormat();
        if (format == Pixmap.Format.Alpha) {
            config = Bitmap.Config.ARGB_8888;
        } else if (format == Pixmap.Format.RGB888) {
            config = Bitmap.Config.RGB_565;
        } else {
            throw new RuntimeException("format not supported");
        }

        Bitmap bitmap = Bitmap.createBitmap(pixmap.getWidth(), pixmap.getHeight(), config);

        final boolean flipY = false;
        // TODO: should this for-loop go to the upper class?
        for (int y = 0, h = pixmap.getHeight(); y < h; y++) {
            int py = flipY ? (h - y - 1) : y;
            for (int px = 0, x = 0; px < pixmap.getWidth(); px++) {
                int pixel = pixmap.getPixel(px, py);
                if (pixmap.getFormat() == Pixmap.Format.Alpha) {
                    pixel &= 0xFF;
                    bitmap.setPixel(px, py, Color.argb(255, pixel, 0, 0));
                } else if (pixmap.getFormat() == Pixmap.Format.RGB888) {
                    int r = pixel >>> 24;
                    int g = (pixel & 0xFF0000) >>> 16;
                    int b = (pixel & 0xFF00) >>> 8;
                    bitmap.setPixel(px, py, Color.rgb(r, g, b));
                } else {
                    throw new RuntimeException("could not handle format to save it as JPG");
                }
            }
        }
        return bitmap;
    }
}
