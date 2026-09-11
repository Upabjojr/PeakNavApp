package com.peaknav.roads;

/**
 * The two textures one tile's roads are drawn from, as raw RGBA8888 bytes, row 0 at the north
 * edge. See {@link RoadTileRasterizer} for what each channel holds.
 */
public final class RoadTextures {

    /** Edge of the distance texture, in texels. */
    public final int res;
    /** Edge of the aux texture: half the distance texture's. */
    public final int auxRes;
    /** {@code res * res * 4} bytes: R roads, G tracks, B trails, A pistes, each a distance. */
    public final byte[] distance;
    /**
     * {@code auxRes * auxRes * 4} bytes: RG the dash phase of the nearest trail as sine and
     * cosine, B the difficulty of the nearest piste, A the difficulty of the nearest trail.
     */
    public final byte[] aux;
    /** Nothing was drawn: every texel is "far from everything". */
    public final boolean empty;
    /** Ground metres per texel of the distance texture, for the shader's widths. */
    public final float metersPerTexel;

    RoadTextures(int res, int auxRes, byte[] distance, byte[] aux, boolean empty,
                 float metersPerTexel) {
        this.res = res;
        this.auxRes = auxRes;
        this.distance = distance;
        this.aux = aux;
        this.empty = empty;
        this.metersPerTexel = metersPerTexel;
    }

    /** The distance, in texels, stored in channel {@code channel} of texel (x, y). */
    public float distanceAt(int x, int y, int channel) {
        return RoadTileRasterizer.decodeDistance(distance[(y * res + x) * 4 + channel]);
    }

    /** The unsigned byte of the aux texture at (x, y), channel 0..3. */
    public int auxAt(int x, int y, int channel) {
        return aux[(y * auxRes + x) * 4 + channel] & 0xFF;
    }
}
