package com.peaknav.viewer.imgmapprovider;

import static com.peaknav.utils.PathUtils.joinPaths;
import static com.peaknav.utils.PeakNavUtils.getCacheDir;

import java.io.File;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

public class SatelliteImageCacheStorage {
    private final String folderHash;
    private String imageExtension;

    public SatelliteImageCacheStorage(String urlTemplate, String imageExtension) {
        this.folderHash = getFolderHash(urlTemplate);

        this.imageExtension = imageExtension;
    }

    private String getFolderHash(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(url.getBytes());
            byte[] hashBytes = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public String getImgFolder() {
        return "sat_downloads";
    }

    public String getImgPrefix() {
        return this.folderHash.substring(0, 3);
    }

    public String getImgSubfolder() {
        return this.folderHash.substring(0, 8);
    }

    protected String getImageName(int z, int x, int y) {
        String imgPrefix = getImgPrefix();
        String suffix = getImgExtension();
        return String.format(Locale.ENGLISH, "%s_%d_%d_%d.%s", imgPrefix, z, x, y, suffix);
    }

    private String getImgExtension() {
        return this.imageExtension;
    }

    private static final int numParents = 5;

    public File getImageFileHandle(int z, int x, int y) {
        File imgFolder = new File(joinPaths(getCacheDir(), getImgFolder(), getImgSubfolder()));
        File imgX1Folder = new File(imgFolder, String.format(Locale.ENGLISH, "%d", x / 100));
        File imgX2Folder = new File(imgX1Folder, String.format(Locale.ENGLISH, "%d", x % 100));
        File imgY1Folder = new File(imgX2Folder, String.format(Locale.ENGLISH, "%d", y / 100));
        File file = new File(imgY1Folder, getImageName(z, x, y));
        File[] parents = new File[numParents];
        File parent = file.getParentFile();
        for (int i = 0; i < numParents; i++) {
            parents[i] = parent;
            parent = parent.getParentFile();
        }
        for (int i = numParents - 1; i >= 0; i--) {
            if (!parents[i].exists()) {
                parents[i].mkdir();
            }
        }
        return file;
    }

}
