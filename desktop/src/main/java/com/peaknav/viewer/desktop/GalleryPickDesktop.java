package com.peaknav.viewer.desktop;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.peaknav.utils.PeakNavUtils;
import com.peaknav.viewer.MapViewerSingleton;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileFilter;


public class GalleryPickDesktop {

    public GalleryPickDesktop() {

        selectImage();
    }

    private void selectImage() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        fileChooser.setAcceptAllFileFilterUsed(false);

        fileChooser.addChoosableFileFilter(new FileFilter() {
            @Override
            public boolean accept(File f) {
                return (
                    f.getName().toLowerCase().endsWith(".png") ||
                    f.getName().toLowerCase().endsWith(".jpg") ||
                    f.getName().toLowerCase().endsWith(".jpeg") ||
                    f.isDirectory()
                );
            }

            @Override
            public String getDescription() {
                return "Gallery files (*.png, *.jpg, *.jpeg)";
            }
        });

        int result = fileChooser.showOpenDialog(null);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            if (selectedFile != null) {
                setAppBackgroundImage(selectedFile.getAbsoluteFile());
            } else {
                System.err.println("No image selected.");
            }
        }
    }

    private void setAppBackgroundImage(File imageFile) {
        try {
            // Go through the shared byte path so the background gets EXIF orientation
            // applied, exactly as it does on Android.
            byte[] bytes = Files.readAllBytes(imageFile.toPath());
            PeakNavUtils.setBytesAsBackgroundImage(bytes);
            PeakNavUtils.checkImageGpsAndPrompt(bytes);
        } catch (IOException e) {
            // Fall back to decoding straight from the file, without orientation/GPS.
            Pixmap pixmap = new Pixmap(new FileHandle(imageFile));
            MapViewerSingleton.getViewerInstance().backgroundPicManager.setBackgroundPixmap(pixmap);
        }
    }
}
