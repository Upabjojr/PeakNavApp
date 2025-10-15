package com.peaknav.viewer.tiles;

import com.peaknav.utils.TileBoundingBox;
import com.peaknav.utils.Units;

public class TileId {
    public int z, x, y;

    public TileId(int z, int x, int y) {
        this.z = z;
        this.x = x;
        this.y = y;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof TileId) {
            TileId other = (TileId) obj;
            return other.z == z && other.x == x && other.y == y;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return 100000000 * z + 10000 * x + y;
    }

    public TileId zoomOut() {
        int z = this.z - 1;
        int x = this.x / 2;
        int y = this.y / 2;
        return new TileId(z, x, y);
    }

    public TileId zoomIn() {
        // Return north-western tile:
        int z = this.z + 1;
        int x = 2 * this.x;
        int y = 2 * this.y + 1;
        return new TileId(z, x, y);
    }

    public TileId getOtherChild(int i) {
        // clockwise rotate:
        int xh = x / 2;
        int yh = y / 2;
        int xc = x % 2;
        int yc = y % 2;
        int c = xc + 2 * yc;
        int cnew = (c + i) % 4;
        int xnew = 2 * xh + cnew % 2;
        int ynew = 2 * yh + cnew / 2;
        return new TileId(z, xnew, ynew);
    }

    public TileBoundingBox getBoundingBox() {
        return Units.tile2boundingBox(x, y, z);
    }

}
