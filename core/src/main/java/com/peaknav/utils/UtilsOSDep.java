package com.peaknav.utils;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.utils.GdxRuntimeException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.zip.Deflater;


public abstract class UtilsOSDep {
    public abstract void savePixmapAsPng(File output, Pixmap pixmap);
    public abstract void savePixmapAsJpg(File output, Pixmap pixmap);

    protected byte[] writeToPNG(Pixmap pixmap) {
        boolean flipY = false;
        int compression = Deflater.DEFAULT_COMPRESSION;
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            PixmapIO.PNG writer = new PixmapIO.PNG((int)(pixmap.getWidth() * pixmap.getHeight() * 1.5f)); // Guess at deflated size.
            try {
                writer.setFlipY(flipY);
                writer.setCompression(compression);
                writer.write(outputStream, pixmap);
            } finally {
                writer.dispose();
            }
            return outputStream.toByteArray();
        } catch (IOException ex) {
            throw new GdxRuntimeException("Error writing PNG to ByteArrayOutputStream ", ex);
        }
    }

}
