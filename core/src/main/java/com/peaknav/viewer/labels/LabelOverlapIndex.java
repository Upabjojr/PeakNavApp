package com.peaknav.viewer.labels;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Polygon;
import com.badlogic.gdx.utils.IntArray;

/**
 * Screen-space index of the label rectangles placed so far, used to drop labels that would
 * overlap one that is already shown.
 *
 * The straightforward way to do this is to compare every candidate against every label placed
 * so far, which costs O(n^2) exact polygon (SAT) tests. With the number of peaks/places/huts
 * that can be on screen at once that was too slow to run while the camera turns, so the
 * overlap pass could only be triggered rarely and visible overlaps survived in between.
 *
 * Here the placed labels are bucketed into a uniform grid over the screen. A candidate is only
 * compared against labels sharing one of the cells its bounding box covers, and each of those
 * is first rejected with a cheap bounding-box test before the exact SAT test runs. In practice
 * that turns the pass into roughly O(n) with a handful of cheap comparisons per label.
 *
 * The result is identical to the brute-force version, not an approximation:
 * <ul>
 *   <li>bounding boxes are conservative, so a box that misses can never hide a real overlap;</li>
 *   <li>cell coordinates are clamped the same way on insert and on query, so any genuinely
 *       overlapping pair always shares at least one cell — including labels projected far
 *       outside the screen.</li>
 * </ul>
 *
 * Not thread safe: one instance is meant to be filled by a single pass over the labels.
 */
public class LabelOverlapIndex {

    /** Grid resolution. Cells end up roughly label-sized on a typical screen. */
    private static final int COLS = 32;
    private static final int ROWS = 32;

    private static final int INITIAL_CAPACITY = 256;

    private final IntArray[] cells = new IntArray[COLS * ROWS];

    private Polygon[] polygons = new Polygon[INITIAL_CAPACITY];
    private float[] minX = new float[INITIAL_CAPACITY];
    private float[] minY = new float[INITIAL_CAPACITY];
    private float[] maxX = new float[INITIAL_CAPACITY];
    private float[] maxY = new float[INITIAL_CAPACITY];

    /**
     * Per-entry marker of the last query that already examined it, so a label covering several
     * cells is only tested once per candidate.
     */
    private int[] visited = new int[INITIAL_CAPACITY];
    private int queryStamp;

    private int size;

    private float cellWidth = 1f;
    private float cellHeight = 1f;

    public LabelOverlapIndex() {
        for (int i = 0; i < cells.length; i++) {
            cells[i] = new IntArray(8);
        }
    }

    /** Drops everything placed so far and refits the grid to the given screen size. */
    public void reset(int screenWidth, int screenHeight) {
        for (int i = 0; i < cells.length; i++) {
            cells[i].clear();
        }
        size = 0;
        cellWidth = Math.max(1f, screenWidth / (float) COLS);
        cellHeight = Math.max(1f, screenHeight / (float) ROWS);
    }

    /**
     * Tests {@code polygon} against everything placed so far and, when it fits, keeps it so later
     * candidates are tested against it too.
     *
     * @return true when the label does not overlap anything already placed (and was added),
     *         false when it overlaps and should therefore be hidden.
     */
    public boolean tryPlace(Polygon polygon) {
        float[] vertices = polygon.getTransformedVertices();
        if (vertices.length < 2) {
            return true;
        }

        float boxMinX = vertices[0], boxMaxX = vertices[0];
        float boxMinY = vertices[1], boxMaxY = vertices[1];
        for (int i = 2; i < vertices.length; i += 2) {
            float x = vertices[i];
            float y = vertices[i + 1];
            if (x < boxMinX) boxMinX = x; else if (x > boxMaxX) boxMaxX = x;
            if (y < boxMinY) boxMinY = y; else if (y > boxMaxY) boxMaxY = y;
        }

        int colFrom = col(boxMinX), colTo = col(boxMaxX);
        int rowFrom = row(boxMinY), rowTo = row(boxMaxY);

        int stamp = ++queryStamp;
        for (int r = rowFrom; r <= rowTo; r++) {
            int rowOffset = r * COLS;
            for (int c = colFrom; c <= colTo; c++) {
                IntArray cell = cells[rowOffset + c];
                for (int k = 0, n = cell.size; k < n; k++) {
                    int idx = cell.get(k);
                    if (visited[idx] == stamp) {
                        continue;
                    }
                    visited[idx] = stamp;
                    // Cheap bounding-box rejection before the exact (and much costlier) SAT test.
                    if (boxMinX > maxX[idx] || boxMaxX < minX[idx]
                            || boxMinY > maxY[idx] || boxMaxY < minY[idx]) {
                        continue;
                    }
                    if (Intersector.overlapConvexPolygons(polygon, polygons[idx])) {
                        return false;
                    }
                }
            }
        }

        add(polygon, boxMinX, boxMinY, boxMaxX, boxMaxY, colFrom, colTo, rowFrom, rowTo);
        return true;
    }

    private void add(Polygon polygon, float boxMinX, float boxMinY, float boxMaxX, float boxMaxY,
                     int colFrom, int colTo, int rowFrom, int rowTo) {
        if (size == polygons.length) {
            grow();
        }
        int idx = size++;
        polygons[idx] = polygon;
        minX[idx] = boxMinX;
        minY[idx] = boxMinY;
        maxX[idx] = boxMaxX;
        maxY[idx] = boxMaxY;
        // 0 is never a live stamp (queryStamp is pre-incremented before use).
        visited[idx] = 0;

        for (int r = rowFrom; r <= rowTo; r++) {
            int rowOffset = r * COLS;
            for (int c = colFrom; c <= colTo; c++) {
                cells[rowOffset + c].add(idx);
            }
        }
    }

    private void grow() {
        int capacity = polygons.length * 2;
        Polygon[] newPolygons = new Polygon[capacity];
        System.arraycopy(polygons, 0, newPolygons, 0, size);
        polygons = newPolygons;
        minX = copyOf(minX, capacity);
        minY = copyOf(minY, capacity);
        maxX = copyOf(maxX, capacity);
        maxY = copyOf(maxY, capacity);
        int[] newVisited = new int[capacity];
        System.arraycopy(visited, 0, newVisited, 0, size);
        visited = newVisited;
    }

    private float[] copyOf(float[] source, int capacity) {
        float[] copy = new float[capacity];
        System.arraycopy(source, 0, copy, 0, size);
        return copy;
    }

    private int col(float x) {
        int c = (int) (x / cellWidth);
        if (c < 0) return 0;
        return c >= COLS ? COLS - 1 : c;
    }

    private int row(float y) {
        int r = (int) (y / cellHeight);
        if (r < 0) return 0;
        return r >= ROWS ? ROWS - 1 : r;
    }
}
