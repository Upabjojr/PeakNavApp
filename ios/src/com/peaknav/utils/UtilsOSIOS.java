package com.peaknav.utils;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.GdxRuntimeException;

import org.robovm.apple.foundation.NSData;
import org.robovm.apple.uikit.UIImage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Writing a rendered frame to a file on iOS.
 *
 * <p>PNG is encoded by libGDX itself, which is platform-independent and already used by
 * every other target. JPEG has no libGDX encoder, so the pixels go through UIImage - the
 * only JPEG encoder on the platform, and the one the Photos app expects anyway.
 */
public class UtilsOSIOS extends UtilsOSDep {

    /** What a snapshot is saved at. Visually lossless for a photograph-like render. */
    private static final double JPEG_QUALITY = 0.92;

    /** The frame as PNG bytes - what the share sheet hands to UIImage. */
    public byte[] encodePng(Pixmap pixmap) {
        return writeToPNG(pixmap);
    }

    @Override
    public void savePixmapAsPng(File output, Pixmap pixmap) {
        write(output, writeToPNG(pixmap));
    }

    @Override
    public void savePixmapAsJpg(File output, Pixmap pixmap) {
        // Via PNG rather than by handing UIImage the raw pixel buffer: the PNG bytes carry
        // their own width, height and layout, so there is no chance of describing the
        // buffer to CoreGraphics incorrectly - the mistake that produces a picture with
        // skewed rows or swapped colour channels, and one that cannot happen this way.
        NSData png = new NSData(writeToPNG(pixmap));
        UIImage image = new UIImage(png);
        NSData jpeg = image.toJPEGData(JPEG_QUALITY);
        if (jpeg == null) {
            throw new GdxRuntimeException("iOS refused to encode the frame as JPEG");
        }
        write(output, jpeg.getBytes());
    }

    private static void write(File output, byte[] bytes) {
        File parent = output.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (FileOutputStream out = new FileOutputStream(output)) {
            out.write(bytes);
        } catch (IOException failed) {
            throw new GdxRuntimeException("could not write " + output, failed);
        }
    }
}
