package com.peaknav.geo;

/**
 * One tile of the web Mercator grid (see {@link MercatorProjection}): its column and row at a
 * zoom level, and a size in pixels.
 *
 * <p>The size is part of a tile's identity: two tiles over the same ground with different sizes
 * are not equal, and the app keys some of its caches by tiles of size 1 and others by tiles of
 * size 256 (MapTile.TILE_SIZE). Immutable.
 */
public final class Tile {

    public final int tileX;
    public final int tileY;
    public final byte zoomLevel;
    /** Pixels along a side. */
    public final int tileSize;

    /** Worked out on first use; racing threads at worst both compute the same box. */
    private volatile BoundingBox boundingBox;

    public Tile(int tileX, int tileY, byte zoomLevel, int tileSize) {
        if (zoomLevel < 0) {
            throw new IllegalArgumentException("zoomLevel must not be negative: " + zoomLevel);
        }
        if (tileX < 0) {
            throw new IllegalArgumentException("tileX must not be negative: " + tileX);
        }
        if (tileY < 0) {
            throw new IllegalArgumentException("tileY must not be negative: " + tileY);
        }
        long last = maxTileNumber(zoomLevel);
        if (tileX > last) {
            throw new IllegalArgumentException("invalid tileX number on zoom level " + zoomLevel + ": " + tileX);
        }
        if (tileY > last) {
            throw new IllegalArgumentException("invalid tileY number on zoom level " + zoomLevel + ": " + tileY);
        }
        this.tileX = tileX;
        this.tileY = tileY;
        this.zoomLevel = zoomLevel;
        this.tileSize = tileSize;
    }

    /** The highest column or row number at this zoom level. */
    public static long maxTileNumber(byte zoomLevel) {
        return zoomLevel >= 31 ? Integer.MAX_VALUE : (1L << zoomLevel) - 1;
    }

    /** The ground the tile covers: from its western to its eastern edge, its southern to its northern. */
    public BoundingBox getBoundingBox() {
        BoundingBox box = boundingBox;
        if (box == null) {
            box = new BoundingBox(
                    MercatorProjection.tileYToLatitude(tileY + 1L, zoomLevel),
                    MercatorProjection.tileXToLongitude(tileX, zoomLevel),
                    MercatorProjection.tileYToLatitude(tileY, zoomLevel),
                    MercatorProjection.tileXToLongitude(tileX + 1L, zoomLevel));
            boundingBox = box;
        }
        return box;
    }

    /** The tile one zoom level out that holds this one, or null at zoom level 0. */
    public Tile getParent() {
        if (zoomLevel == 0) {
            return null;
        }
        return new Tile(tileX / 2, tileY / 2, (byte) (zoomLevel - 1), tileSize);
    }

    /** The tile to the west; west of the first column comes the last, across the antimeridian. */
    public Tile getLeft() {
        int x = tileX == 0 ? (int) maxTileNumber(zoomLevel) : tileX - 1;
        return new Tile(x, tileY, zoomLevel, tileSize);
    }

    /** The tile to the east, wrapping round from the last column to the first. */
    public Tile getRight() {
        int x = tileX == maxTileNumber(zoomLevel) ? 0 : tileX + 1;
        return new Tile(x, tileY, zoomLevel, tileSize);
    }

    /** The tile to the north, wrapping round from the first row to the last. */
    public Tile getAbove() {
        int y = tileY == 0 ? (int) maxTileNumber(zoomLevel) : tileY - 1;
        return new Tile(tileX, y, zoomLevel, tileSize);
    }

    /** The tile to the south, wrapping round from the last row to the first. */
    public Tile getBelow() {
        int y = tileY == maxTileNumber(zoomLevel) ? 0 : tileY + 1;
        return new Tile(tileX, y, zoomLevel, tileSize);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Tile)) {
            return false;
        }
        Tile t = (Tile) o;
        return tileX == t.tileX && tileY == t.tileY && zoomLevel == t.zoomLevel && tileSize == t.tileSize;
    }

    @Override
    public int hashCode() {
        int h = tileX;
        h = h * 31 + tileY;
        h = h * 31 + zoomLevel;
        h = h * 31 + tileSize;
        return h;
    }

    /** "x=534, y=364, z=10" - also the stem of the vertex dumps MapTileStorage writes. */
    @Override
    public String toString() {
        return "x=" + tileX + ", y=" + tileY + ", z=" + zoomLevel;
    }
}
