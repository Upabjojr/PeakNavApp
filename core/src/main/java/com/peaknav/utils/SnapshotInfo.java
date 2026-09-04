package com.peaknav.utils;

import java.util.Locale;

/**
 * Where and how a saved view was taken: the camera's position and pose at the moment of
 * the snapshot, in the units a photograph's EXIF block carries them, so a picture shared
 * from the app keeps the place it shows - and, opened again in the app, its position,
 * heading and field of view come back out of the file ({@link ExifReader}).
 */
public final class SnapshotInfo {

    public final double latitude;
    public final double longitude;
    /** Camera height above sea level, metres; NaN when unknown. */
    public final double altitudeMeters;
    /** Compass bearing of the view, degrees clockwise from north. */
    public final double bearingDeg;
    /** Elevation angle of the view, degrees, positive upwards. */
    public final double pitchDeg;
    /** Vertical field of view of the saved picture, degrees. */
    public final double verticalFovDeg;
    /** Size of the saved picture in pixels. */
    public final int width;
    public final int height;
    /** Whether a photograph was shown behind the terrain in this view. */
    public final boolean overPhoto;

    public SnapshotInfo(double latitude, double longitude, double altitudeMeters, double bearingDeg,
                        double pitchDeg, double verticalFovDeg, int width, int height, boolean overPhoto) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitudeMeters = altitudeMeters;
        this.bearingDeg = ((bearingDeg % 360) + 360) % 360;
        this.pitchDeg = pitchDeg;
        this.verticalFovDeg = verticalFovDeg;
        this.width = width;
        this.height = height;
        this.overPhoto = overPhoto;
    }

    /**
     * The 35 mm-equivalent focal length that gives this picture's vertical field of view,
     * the way {@link ExifReader.CameraInfo#verticalFovDeg} reads it back: defined on the
     * 43.27 mm diagonal of a full frame.
     */
    public int focalLength35mm() {
        double halfV = Math.toRadians(verticalFovDeg) / 2;
        double tanHalfDiag = Math.tan(halfV) * Math.hypot(width, height) / Math.max(1, height);
        if (tanHalfDiag <= 0) {
            return 0;
        }
        return (int) Math.round(43.27 / 2 / tanHalfDiag);
    }

    /** One line for the picture's description tag. */
    public String description() {
        StringBuilder sb = new StringBuilder("PeakNav view: ");
        sb.append(String.format(Locale.ENGLISH, "%.5f %s, %.5f %s",
                Math.abs(latitude), latitude >= 0 ? "N" : "S", Math.abs(longitude), longitude >= 0 ? "E" : "W"));
        if (!Double.isNaN(altitudeMeters)) {
            sb.append(String.format(Locale.ENGLISH, ", altitude %.0f m", altitudeMeters));
        }
        // ASCII only: the description tag has no room for a degree sign
        sb.append(String.format(Locale.ENGLISH, ", bearing %.1f deg, pitch %.1f deg, vertical field of view %.1f deg",
                bearingDeg, pitchDeg, verticalFovDeg));
        if (overPhoto) {
            sb.append(", terrain over a photograph");
        }
        return sb.toString();
    }
}
