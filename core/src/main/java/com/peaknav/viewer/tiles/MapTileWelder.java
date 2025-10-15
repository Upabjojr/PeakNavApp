package com.peaknav.viewer.tiles;


import static com.peaknav.compatibility.PeakNavAppState.getAppState;
import static com.peaknav.elevation.ElevationImageAbstract.numVertAttributes;
import static com.peaknav.viewer.tiles.MapTile.MapTileState.ELEVATION_DATA_NOT_LOADED;
import static com.peaknav.viewer.tiles.MapTile.MapTileState.IS_DRAWN;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.math.Vector3;

import org.mapsforge.core.model.BoundingBox;

import java.math.BigInteger;

public class MapTileWelder {

    private final MapTile mapTile1;
    private final MapTile mapTile2;
    private final int zoomLevelDiff;

    public MapTileWelder(MapTile mapTile1, MapTile mapTile2) {
        if (mapTile1.tile.zoomLevel < mapTile2.tile.zoomLevel) {
            this.mapTile1 = mapTile1;
            this.mapTile2 = mapTile2;
        } else {
            this.mapTile1 = mapTile2;
            this.mapTile2 = mapTile1;
        }
        int d = this.mapTile2.tile.zoomLevel - this.mapTile1.tile.zoomLevel;
        zoomLevelDiff = 1 << d;
    }

    public boolean canWeldIsDrawn() {
        return mapTile1.getMapTileState() == IS_DRAWN && mapTile2.getMapTileState() == IS_DRAWN;
    }

    public boolean isTileDisposed() {
        if (mapTile1.isDisposed())
            return true;
        if (mapTile2.isDisposed())
            return true;
        return false;
    }

    public boolean isElevationDataNotFound() {
        if (mapTile1.getMapTileState() == MapTile.MapTileState.ELEVATION_DATA_NOT_FOUND)
            return true;
        if (mapTile2.getMapTileState() == MapTile.MapTileState.ELEVATION_DATA_NOT_FOUND)
            return true;
        return false;
    }

    public void weldLockPositions() {
        getAppState().setLastAnyMapTileUpdateTimeToNow();
        this.weldPositions();
    }

    static class Range {
        public final int start, step, end;

        public Range(int start, int step, int end) {
            this.start = start;
            this.step = step;
            this.end = end;
        }

        public int getSteps() {
            /*
            int count = 0;
            for (int i = start; i < end; i += step) {
                count++;
            }
             */
            int diff = end - start;
            int steps = diff / step;
            if (diff % step != 0)
                steps++;
            /*
            if (steps != count) {
                System.err.println("error!");
            }
            return count;
             */
            return steps;
        }

        public boolean equals(Object other) {
            if (other instanceof Range) {
                Range r = (Range) other;
                return r.start == start && r.end == end && r.step == step;
            }
            return false;
        }

    }

    public void weldPositions() {
        if (mapTile1.getMapTileState() == ELEVATION_DATA_NOT_LOADED || mapTile2.getMapTileState() == ELEVATION_DATA_NOT_LOADED)
            return;

        assert mapTile1.tile.getBoundingBox().intersects(
                mapTile2.tile.getBoundingBox()
        );

        BoundingBox bb1 = mapTile1.tile.getBoundingBox();
        BoundingBox bb2 = mapTile2.tile.getBoundingBox();

        Range range1, range2;

        if (isApproxEqual(bb1.maxLongitude, bb2.minLongitude)) {
            // mapTile1 is left of mapTile2:
            range1 = getLatRangeB(mapTile1, true);
            range2 = getLatRangeB(mapTile2, false);
        } else if (isApproxEqual(bb1.minLongitude, bb2.maxLongitude)) {
            // mapTile1 is right of mapTile2:
            range1 = getLatRangeB(mapTile1,false);
            range2 = getLatRangeB(mapTile2,true);
        } else if (isApproxEqual(bb1.maxLatitude, bb2.minLatitude)) {
            // mapTile1 is below mapTile2:
            // Remember: larger tileY means lower latitude!
            range1 = getLonRangeB(mapTile1, true);
            range2 = getLonRangeB(mapTile2, false);
        } else if (isApproxEqual(bb1.minLatitude, bb2.maxLatitude)) {
            // mapTile1 is below mapTile2:
            // Remember: larger tileY means lower latitude!
            range1 = getLonRangeB(mapTile1, false);
            range2 = getLonRangeB(mapTile2, true);
        } else {
            throw new RuntimeException("impossible state");
        }

        weldPositions(range1, range2);

        mapTile1.recomputeNormals();
        mapTile2.recomputeNormals();

        Gdx.app.postRunnable(() -> {
            mapTile1.reassignVertices();
            mapTile2.reassignVertices();
        });

    }

    private boolean isApproxEqual(double a, double b) {
        return Math.abs(a - b) < 0.0000001;
    }

    private int getPositionFromCoord(MapTile mapTile, double coordStart, double coordDiff) {
        int exponent = (int) Math.round(Math.log(coordDiff/coordStart)/Math.log(2.0));
        return (mapTile.getWidth() - 1)/(1 << exponent);
    }

    private Range getLatRangeB(MapTile mapTile, boolean first) {
        int h = mapTile.getHeight(); int w = mapTile.getWidth();

        int start, end;
        int step = w;
        if (mapTile == mapTile1) {
            int dY = mapTile2.tile.tileY - zoomLevelDiff * mapTile1.tile.tileY;
            dY = zoomLevelDiff - dY - 1;

            start = step * (h - 1) * dY / zoomLevelDiff;
            end = step * (h - 1) * (dY + 1) / zoomLevelDiff + 1;
        } else {
            start = 0;
            end = (h-1)*w + 1;
        }

        if (first) {
            int wm1 = w - 1;
            start += wm1;
            end += wm1;
        }

        return new Range(start, step, end);
    }

    private Range getLatRange(MapTile mapTile, double lat1, double lat2, boolean first) {
        BoundingBox bb = mapTile.tile.getBoundingBox();
        int h = mapTile.getHeight(); int w = mapTile.getWidth();

        double latStart = Double.max(bb.minLatitude, lat1);
        double latEnd = Double.min(bb.maxLatitude, lat2);
        double latDiff = bb.maxLatitude - bb.minLatitude;

        latStart -= bb.minLatitude;
        latEnd -= bb.minLatitude;

        int step = w;

        int start = w * getPositionFromCoord(mapTile, latStart, latDiff);
        int end = step * (1 + getPositionFromCoord(mapTile, latEnd, latDiff)) - w + 1;

        if (first) {
            int wm1 = w - 1;
            start += wm1;
            end += wm1;
        }

        return new Range(start, step, end);
    }

    private Range getLonRangeB(MapTile mapTile, boolean first) {
        int h = mapTile.getHeight(); int w = mapTile.getWidth();

        int start, end;
        int step = 1;
        if (mapTile == mapTile1) {
            int dX = mapTile2.tile.tileX - zoomLevelDiff * mapTile1.tile.tileX;

            start = (w - 1) * dX / zoomLevelDiff;
            end = (w - 1) * (dX + 1) / zoomLevelDiff + 1;
        } else {
            start = 0;
            end = w;
        }

        if (first) {
            int hm1 = w * (h - 1);
            start += hm1;
            end += hm1;
        }

        return new Range(start, step, end);
    }

    private Range getLonRange(MapTile mapTile, double lon1, double lon2, boolean first) {
        BoundingBox bb = mapTile.tile.getBoundingBox();
        int h = mapTile.getHeight(); int w = mapTile.getWidth();

        double lonStart = Double.max(bb.minLongitude, lon1);
        double lonEnd = Double.min(bb.maxLongitude, lon2);
        double lonDiff = bb.maxLongitude - bb.minLongitude;

        lonStart -= bb.minLongitude;
        lonEnd -= bb.minLongitude;

        int step = 1;

        int start = getPositionFromCoord(mapTile, lonStart, lonDiff);
        int end = step * (1 + getPositionFromCoord(mapTile, lonEnd, lonDiff));

        if (first) {
            int hm1 = w * (h - 1);
            start += hm1;
            end += hm1;
        }

        return new Range(start, step, end);
    }

    private void weldPositions(Range range1, Range range2) {
        float[] vert1 = mapTile1.getMapTileVertices();
        float[] vert2 = mapTile2.getMapTileVertices();

        weldDo(vert1, range1, vert2, range2);

    }

    private void weldDo(
            float[] vert1, Range range1,
            float[] vert2, Range range2) {
        int i1;
        int i2;

        int steps1 = range1.getSteps();
        int steps2 = range2.getSteps();
        int gcd = BigInteger
                .valueOf(steps1-1)
                .gcd(BigInteger
                        .valueOf(steps2-1))
                .intValue();
        int gstep1 = steps1/gcd;
        int gstep2 = steps2/gcd;
        for (
                i1 = range1.start, i2 = range2.start;
                i2 < range2.end;
                i1 += gstep1*range1.step, i2 += gstep2*range2.step
        ) {
            averageOut(i1, i2, range1, range2, vert1, vert2);
        }

        // TODO: is this block necessary?
        int end1 = range1.start + steps1 * range1.step;
        int end2 = range2.start + steps2 * range2.step;
        int s1 = end1*numVertAttributes;
        int s2 = end2*numVertAttributes;
        if (s1 < vert1.length && s2 < vert2.length) {
            averageOut(end1, end2, range1, range2, vert1, vert2);
        }

        // (range1.start + (j+1)*range1.step)*numVertAttributes + 2
        replaceWithInterpolatedLinspace(vert1, gstep1, range1);
        replaceWithInterpolatedLinspace(vert2, gstep2, range2);
    }

    private void averageOut(int i1, int i2, Range range1, Range range2, float[] vert1, float[] vert2) {
        int s1 = i1 * numVertAttributes;
        int s2 = i2 * numVertAttributes;
        boolean nan1 = Float.isNaN(vert1[s1 + 2]);
        boolean nan2 = Float.isNaN(vert2[s2 + 2]);
        if (nan1 && nan2) {
            float interp = interpolateMissing(i1, i2, range1, range2, vert1, vert2);
            vert1[s1 + 2] = interp;
            vert2[s2 + 2] = interp;
        } else if (nan1) {
            vert1[s1 + 2] = vert2[s2 + 2];
        } else if (nan2) {
            vert2[s2 + 2] = vert1[s1 + 2];
        } else {
            float avg = (vert1[s1 + 2] + vert2[s2 + 2]) / 2.0f;
            vert1[s1 + 2] = avg;
            vert2[s2 + 2] = avg;
        }
        if (vert1[s1 + 3] == 0 && vert1[s1 + 4] == 0 && vert1[s1 + 5] == 0) {
            vert1[s1 + 3] = vert2[s2 + 3];
            vert1[s1 + 4] = vert2[s2 + 4];
            vert1[s1 + 5] = vert2[s2 + 5];
        } else if (vert2[s2 + 3] == 0 && vert2[s2 + 4] == 0 && vert2[s2 + 5] == 0) {
            vert2[s2 + 3] = vert1[s1 + 3];
            vert2[s2 + 4] = vert1[s1 + 4];
            vert2[s2 + 5] = vert1[s1 + 5];
        }
    }

    private float interpolateMissing(int i1, int i2, Range range1, Range range2, float[] vert1, float[] vert2) {
        int x1 = i1 % mapTile1.getWidth();
        int y1 = i1 / mapTile1.getWidth();
        int x2 = i2 % mapTile2.getWidth();
        int y2 = i2 / mapTile2.getWidth();
        float avg = 0.0f;
        int count = 0;
        for (int x = x1-1; x <= x1+1; x++) {
            for (int y = y1 - 1; y <= y1+1; y++) {
                if (x < 0 || x >= mapTile1.getWidth() || y < 0 || y >= mapTile1.getHeight())
                    continue;
                int idx = (x + y*mapTile1.getWidth())*numVertAttributes + 2;
                // if (idx < 0 || idx >= vert1.length)
                    // continue;
                float elev = vert1[idx];
                if (!Float.isNaN(elev)) {
                    avg += elev;
                    count++;
                }
            }
        }
        for (int x = x2 - 1; x <= x2 + 1; x++) {
            for (int y = y2 - 1; y <= y2 + 1; y++) {
                if (x < 0 || x >= mapTile2.getWidth() || y < 0 || y >= mapTile2.getHeight())
                    continue;
                int idx = (x + y*mapTile2.getWidth())*numVertAttributes + 2;
                // if (idx < 0 || idx >= vert2.length)
                    // continue;
                float elev = vert2[idx];
                if (!Float.isNaN(elev)) {
                    avg += elev;
                    count++;
                }
            }
        }
        if (count == 0) {
            throw new RuntimeException("count is zero!!!");
        }
        return avg / count;
    }

    private void setVect(final Vector3 v, float[] vert, int i) {
        v.x = vert[i*numVertAttributes + 3];
        v.y = vert[i*numVertAttributes + 4];
        v.z = vert[i*numVertAttributes + 5];
    }

    private void replaceWithInterpolatedLinspace(float[] vert, int gstep, Range range) {
        float avgPrev = 0, avgNext = 0;
        final Vector3 normPrev = new Vector3(), normNext = new Vector3();

        for (
                int j = 0, i = range.start;
                i < range.end;
                j++, i += range.step
        ) {
            if (j % gstep == 0) {
                avgPrev = vert[i*numVertAttributes + 2];
                setVect(normPrev, vert, i);
                if (i + gstep*range.step >= range.end) {
                    avgNext = vert[(range.end - range.step) * numVertAttributes + 2];
                    setVect(normNext, vert, (range.end - range.step));
                } else {
                    avgNext = vert[(i + gstep * range.step) * numVertAttributes + 2];
                    setVect(normNext, vert, (i + gstep * range.step));
                }
                if (Float.isNaN(avgNext)) {
                    // TODO: find a better way?
                    avgNext = avgPrev;
                    normNext.set(normPrev);
                }
            } else {
                float r = (j % gstep) * 1.0f / gstep;
                vert[i * numVertAttributes + 2] = (1 - r) * avgPrev + r * avgNext;
                vert[i * numVertAttributes + 3] = (1 - r) * normPrev.x + r * normNext.x;
                vert[i * numVertAttributes + 4] = (1 - r) * normPrev.y + r * normNext.y;
                vert[i * numVertAttributes + 5] = (1 - r) * normPrev.z + r * normNext.z;
            }
        }
    }

}
