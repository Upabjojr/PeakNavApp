package com.peaknav.elevation;

import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.Units.convertLonitsToLatits;

import com.badlogic.gdx.math.Vector3;
import com.peaknav.utils.Units;

import org.mapsforge.core.model.BoundingBox;
import org.mapsforge.core.model.Tile;

public abstract class ElevationImageAbstract {
    public static final float MISSING_VERTEX_VALUE = Float.NaN;;
    public static final int numVertAttributes = 8;
    protected final float coordStepX, coordStepY;
    public final BoundingBox boundingBox;
    protected volatile short[] elevations;
    protected final Tile tile;
    protected final int edgeLength;
    private short[] indices = null;
    private float[] vertices = null;

    // TODO: this should be private... there should be a loader checking if the tile has already been loaded...
    public ElevationImageAbstract(int edgeLength, Tile tile, BoundingBox boundingBox) {
        this.edgeLength = edgeLength;
        this.tile = tile;
        this.boundingBox = boundingBox;

        coordStepX = (float) (boundingBox.maxLongitude - boundingBox.minLongitude);
        coordStepY = (float) (boundingBox.maxLatitude - boundingBox.minLatitude);

    }

    protected abstract short[] computeElevationsFromImages();

    public float getTileMatrixElevationLatits(int x, int y) {
        short[] elevations = getElevations();
        float elevation = elevations[x + edgeLength * (edgeLength - 1 - y)];
        return Units.convertMetersToLatits(elevation);
    }

    protected short[] getElevations() {
        if (elevations == null) {
            synchronized (this) {
                if (elevations == null) {
                    elevations = computeElevationsFromImages();
                }
            }
        }
        return elevations;
    }

    public abstract ElevationImageAbstract rescale(int newScale);

    public abstract ElevationImageAbstract crop(Tile newTile);

    public float getTileElevationLatitsFromMaxCoords(double lon, double lat) {
        double floatX = (lon - boundingBox.minLongitude) / coordStepX * (edgeLength);
        double floatY = (lat - boundingBox.minLatitude) / coordStepY * (edgeLength);

        int eleX = (int) floatX;
        int eleY = (int) floatY;

        final int eL1 = edgeLength - 1;
        float el1 = getTileMatrixElevationLatits(eleX, eleY);
        float el2 = getTileMatrixElevationLatits(eleX + ((eleX < eL1)? 1 : -1), eleY);
        float el3 = getTileMatrixElevationLatits(eleX, eleY + ((eleY < eL1)? 1 : -1));
        float el4 = getTileMatrixElevationLatits(eleX + ((eleX < eL1)? 1 : -1), eleY + ((eleY < eL1)? 1 : -1));

        el1 = Math.max(el1, el2);
        el1 = Math.max(el1, el3);
        el1 = Math.max(el1, el4);
        return el1;
    }

    private synchronized short[] computeMeshIndices() {
        final int l = edgeLength, l1 = edgeLength + 1;
        final int w = l;
        final int h = l;
        short[] indices = new short[w * h * 6];
        int i = -1;
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                final int c00 = y * l1 + x;
                final int c10 = c00 + 1;
                final int c01 = c00 + l1;
                final int c11 = c10 + l1;
                indices[++i] = (short) c11;
                indices[++i] = (short) c00;
                indices[++i] = (short) c10;
                indices[++i] = (short) c00;
                indices[++i] = (short) c11;
                indices[++i] = (short) c01;
            }
        }
        return indices;
    }

    private synchronized float[] computeMeshVertices() {
        final int stride = numVertAttributes;
        final int l = edgeLength + 1;
        final int vertexArrayLen = l * l * stride;
        float[] vertices = new float[vertexArrayLen];

        double midLat = getC().L.getTargetLatitude();

        for (int i = 0, s = 0; s < l*l; i += stride, s++) {

            float rw = (float) (s % l);
            float rh = (float) (s / l);

            double longitude = boundingBox.minLongitude + rw / (l - 1) * coordStepX;
            double latitude = boundingBox.minLatitude + rh / (l - 1) * coordStepY;

            vertices[i] = (float) convertLonitsToLatits(longitude, midLat);
            vertices[i + 1] = (float) latitude;

            int pos_x = s % l;
            int pos_y = s / l;

            if ((pos_x == edgeLength) || (pos_y == edgeLength)) {
                vertices[i + 2] = MISSING_VERTEX_VALUE;
            } else {
                float z = getTileMatrixElevationLatits(pos_x, pos_y);
                vertices[i + 2] = z;
            }

            vertices[i + 6] = rw / (l - 1.0f);
            vertices[i + 7] = (l - 1.0f - rh) / (l - 1.0f);
        }

        setVertexNormals(vertices);

        return vertices;
    }

    public short[] getMeshIndices() {
        if (indices == null) {
            indices = computeMeshIndices();
        }
        return indices;
    }

    public float[] getMeshVertices() {
        if (vertices == null) {
            synchronized (this) {
                if (vertices == null) {
                    vertices = computeMeshVertices();
                }
            }
        }
        return vertices;
    }

    public void setVertexNormals(float[] vertices) {
        // TODO: after welding, redo only missing vertices?
        final int stride = numVertAttributes;
        final int l = edgeLength + 1;

        Vector3 tmp1 = new Vector3(), tmp2 = new Vector3();
        for (int i = 0, s = 0; s < l*l; i += stride, s++) {
            int sRight = s + 1;
            int sTop = s + l;
            // float h = vertices[i + 2];
            int r = numVertAttributes*sRight;
            int t = numVertAttributes*sTop;
            if ((s % l != l - 1) && (s + l < l*l) &&
                !Float.isNaN(vertices[r+2]) && !Float.isNaN(vertices[t+2])
            ) {
                tmp1.set(vertices[r], vertices[r+1], vertices[r+2]);
                tmp2.set(vertices[t], vertices[t+1], vertices[t+2]);
                tmp1.x -= vertices[i];
                tmp1.y -= vertices[i + 1];
                tmp1.z -= vertices[i + 2];
                tmp2.x -= vertices[i];
                tmp2.y -= vertices[i + 1];
                tmp2.z -= vertices[i + 2];
                tmp1.crs(tmp2).nor();
                // tmp1.set(Vector3.X);
                vertices[i + 3] = tmp1.x;
                vertices[i + 4] = tmp1.y;
                vertices[i + 5] = tmp1.z;
            }
        }
    }

    public float getTileElevationLatitsFromCoords(float lon, float lat) {
        float floatX = (float) (lon - boundingBox.minLongitude) / coordStepX * (edgeLength - 1);
        float floatY = (float) (lat - boundingBox.minLatitude) / coordStepY * (edgeLength - 1);
        float wX = floatX - ((int) floatX);
        float wY = floatY - ((int) floatY);
        int eleX = (int) floatX;
        int eleY = (int) floatY;
        if (floatX >= edgeLength - 1)
            eleX = edgeLength - 2;
        if (eleY >= edgeLength - 1)
            eleY = edgeLength - 2;
        return (
                wX * wY * getTileMatrixElevationLatits(eleX, eleY) +
                        (1 - wX) * wY * getTileMatrixElevationLatits(eleX + 1, eleY) +
                        wX * (1 - wY) * getTileMatrixElevationLatits(eleX, eleY + 1) +
                        (1 - wX) * (1 - wY) * getTileMatrixElevationLatits(eleX + 1, eleY + 1)
        );
    }

    public abstract void dispose();
}
