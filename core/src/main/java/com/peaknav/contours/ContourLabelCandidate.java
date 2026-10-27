package com.peaknav.contours;

/**
 * A spot where a contour line's elevation may be written: a point on the line, the level it is
 * at, and which way the ground rises there - the line runs across that, and the renderer needs
 * both to lay the text along the line and to tell whether the shader draws this level in bold
 * at the spot (see {@link ContourLabels#labelled}).
 */
public final class ContourLabelCandidate {

    /** The contour's elevation, in metres: a multiple of {@link ContourLabelPlanner#LEVEL_STEP_METERS}. */
    public final int level;
    public final double lat;
    public final double lon;
    /** The slope uphill, in metres of height per metre: east and north components. */
    public final float gradientEast;
    public final float gradientNorth;
    /**
     * The block of ground this spot stands for (see {@link ContourLabelPlanner#BLOCK_METERS}):
     * a smaller amount of labels keeps the blocks whose indices divide by its stride.
     */
    public final int blockX;
    public final int blockY;

    /**
     * World coordinates of the spot, of a point a little along the line and of one a little
     * uphill, three floats each; filled in on the render thread for the target latitude in
     * {@link #worldTargetLatitude}, which the world's x axis is scaled by.
     */
    public float[] world;
    public float worldTargetLatitude = Float.NaN;

    public ContourLabelCandidate(int level, double lat, double lon, float gradientEast,
                                 float gradientNorth, int blockX, int blockY) {
        this.level = level;
        this.lat = lat;
        this.lon = lon;
        this.gradientEast = gradientEast;
        this.gradientNorth = gradientNorth;
        this.blockX = blockX;
        this.blockY = blockY;
    }

    /** Metres of height per metre of ground, uphill. */
    public float slope() {
        return (float) Math.sqrt(gradientEast * gradientEast + gradientNorth * gradientNorth);
    }

    /** Whether an amount keeping every {@code stride}-th block in each direction keeps this one. */
    public boolean kept(int stride) {
        int s = Math.max(1, stride);
        return ((blockX % s) + s) % s == 0 && ((blockY % s) + s) % s == 0;
    }
}
