package com.peaknav.network;

import java.util.Locale;

public class ElevationTileUtils {

    public static final int[] rescaleFactors = {2, 4, 9, 18, 24, 30};

    public static String getNiceFormatLatitude(int latitude) {
        String prefixLat = (latitude >= 0)? "N" : "S";
        return String.format(Locale.ENGLISH, "%s%02d", prefixLat, Math.abs(latitude));
    }

    public static String getNiceFormatLongitude(int longitude) {
        String prefixLon = (longitude >= 0)? "E" : "W";
        return String.format(Locale.ENGLISH, "%s%03d", prefixLon, Math.abs(longitude));
    }

    public static String getElevationSuffix(int latitude, int longitude) {
        return String.format(Locale.ENGLISH, "%s%s", getNiceFormatLatitude(latitude), getNiceFormatLongitude(longitude));
    }

    private static String getElevationSubfolder(String suffix) {
        return String.format(
                Locale.ENGLISH, "elevation_tiles/%sx/%sx/",
                suffix.substring(0, 2),
                suffix.substring(3, 6));
    }

    public static String getElevationFilepathImgJpg(int latitude, int longitude) {
        String suffix = getElevationSuffix(latitude, longitude);
        String nameShort = String.format(Locale.ENGLISH, "elv1.%s.jpg", suffix);
        return getElevationSubfolder(suffix) + nameShort;
    }

    public static String getElevationFilepathImgPng(int latitude, int longitude) {
        String suffix = getElevationSuffix(latitude, longitude);
        String nameShort = String.format(Locale.ENGLISH, "elv2.%s.png", suffix);
        return getElevationSubfolder(suffix) + nameShort;
    }

    /*
    public static String getElevationFilepathImgNorm(int latitude, int longitude) {
        String suffix = getElevationSuffix(latitude, longitude);
        String nameShort = String.format(Locale.ENGLISH, "norm.%s.jpg", suffix);
        return getElevationSubfolder(suffix) + nameShort;
    }
     */
}
