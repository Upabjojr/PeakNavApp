package com.peaknav.viewer.desktop;

import com.badlogic.gdx.Files;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.GdxRuntimeException;

import java.io.File;

public class DesktopFiles implements Files {

    static public final String externalPath = getGdxFilesExternalPath();
    static public final String localPath = new File("").getAbsolutePath() + File.separator;

    public static class DesktopFileHandle extends FileHandle {
        public DesktopFileHandle (String fileName, FileType type) {
            super(fileName, type);
        }

        public DesktopFileHandle (File file, FileType type) {
            super(file, type);
        }

        public FileHandle child (String name) {
            if (file.getPath().length() == 0) return new DesktopFileHandle(new File(name), type);
            return new DesktopFileHandle(new File(file, name), type);
        }

        public FileHandle sibling (String name) {
            if (file.getPath().length() == 0) throw new GdxRuntimeException("Cannot get the sibling of the root.");
            return new DesktopFileHandle(new File(file.getParent(), name), type);
        }

        public FileHandle parent () {
            File parent = file.getParentFile();
            if (parent == null) {
                if (type == FileType.Absolute)
                    parent = new File("/");
                else
                    parent = new File("");
            }
            return new DesktopFileHandle(parent, type);
        }

        public File file () {
            if (type == FileType.External) return new File(externalPath, file.getPath());
            if (type == FileType.Local) return new File(localPath, file.getPath());
            return file;
        }
    }

    @Override
    public FileHandle getFileHandle(String fileName, FileType type) {
        return new DesktopFileHandle(fileName, type);
    }

    @Override
    public FileHandle classpath(String path) {
        return new DesktopFileHandle(path, FileType.Classpath);
    }

    @Override
    public FileHandle internal(String path) {
        return new DesktopFileHandle(path, FileType.Internal);
    }

    @Override
    public FileHandle external(String path) {
        return new DesktopFileHandle(path, FileType.External);
    }

    @Override
    public FileHandle absolute(String path) {
        return new DesktopFileHandle(path, FileType.Absolute);
    }

    @Override
    public FileHandle local(String path) {
        return new DesktopFileHandle(path, FileType.Local);
    }

    @Override
    public String getExternalStoragePath() {
        return externalPath;
    }

    @Override
    public boolean isExternalStorageAvailable() {
        return true;
    }

    @Override
    public String getLocalStoragePath() {
        return localPath;
    }

    @Override
    public boolean isLocalStorageAvailable() {
        return true;
    }

    public static String getGdxFilesExternalPath() {
        String os = System.getProperty("os.name").toLowerCase();
        String userHome = System.getProperty("user.home");

        String folder;
        if (os.contains("win")) {
            folder = System.getenv("APPDATA") + "\\" + DesktopLauncher.appName;
        } else if (os.contains("mac")) {
            folder = userHome + "/Library/Application Support/" + DesktopLauncher.appName;
        } else {
            folder = userHome + "/." + DesktopLauncher.appName.toLowerCase();
        }

        new File(folder).mkdirs();

        return folder;
    }
}
