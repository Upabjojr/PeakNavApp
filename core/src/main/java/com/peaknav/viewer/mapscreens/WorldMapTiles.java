package com.peaknav.viewer.mapscreens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The offline world map drawn under the satellite imagery of the app's maps: coarse SVG tiles in
 * assets/world_map/ (made by tools/world_map_tiles.py from Natural Earth), turned into pictures
 * here, so the search and download screens show the land, the borders and the large countries'
 * divisions at once, and wherever the imagery is slow, failing or not there at all.
 *
 * <p>The tiles go down to zoom {@link #MAX_ZOOM}; closer in, the part of a zoom-4 tile that
 * covers the view is drawn at full size, from its vectors, so it stays sharp at any zoom.
 *
 * <p>The SVG is the generator's own small subset: a {@code <rect>} of ocean, {@code <path>}s
 * filled even-odd, and {@code <path>}s stroked. libGDX draws no SVG, and nothing more is needed
 * for these; they are filled here scanline by scanline, at twice the size and scaled down for
 * smooth edges. Any thread: the map's loader threads call {@link #render}.
 */
final class WorldMapTiles {

    private WorldMapTiles() {
    }

    /** The deepest zoom there are tiles for. */
    static final int MAX_ZOOM = 4;
    private static final int SIZE = 256;
    private static final int SUPERSAMPLE = 2;
    /** The ocean, where there is no tile at all: every tile left out is all ocean. */
    private static final int OCEAN = Color.rgba8888(Color.valueOf("a8cbe6"));

    /** One drawing instruction of a tile. */
    private static final class Shape {
        int colour;
        boolean stroke;
        float width;
        /** Each subpath's points, x and y in turn, in the tile's 0..256 units. */
        final List<float[]> parts = new ArrayList<>();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
    }

    private static final class Tile {
        int background = OCEAN;
        final List<Shape> shapes = new ArrayList<>();
    }

    /** Parsed tiles kept: a zoomed-in view keeps drawing parts of the same few. */
    private static final Map<String, Tile> PARSED = new LinkedHashMap<String, Tile>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Tile> eldest) {
            return size() > 48;
        }
    };
    private static final Tile OCEAN_TILE = new Tile();

    /** The picture of map tile (z, x, y), 256 pixels a side. */
    static Pixmap render(int z, int x, int y) {
        int sourceZoom = Math.min(z, MAX_ZOOM);
        int depth = z - sourceZoom;
        int sx = x >> depth, sy = y >> depth;
        Tile tile = parsed(sourceZoom, sx, sy);
        // The part of the source tile this one covers, in the source's units.
        float part = SIZE / (float) (1 << depth);
        float ox = (x - (sx << depth)) * part, oy = (y - (sy << depth)) * part;
        int canvas = SIZE * SUPERSAMPLE;
        float scale = canvas / part;

        Pixmap big = new Pixmap(canvas, canvas, Pixmap.Format.RGBA8888);
        // Disposed on every way out: the tile is retried every few seconds, and a malformed
        // one left a megabyte of native memory behind at each attempt.
        try {
        big.setBlending(Pixmap.Blending.None);
        big.setColor(tile.background);
        big.fill();
        for (Shape shape : tile.shapes) {
            float pad = shape.stroke ? shape.width : 0f;
            if (shape.maxX + pad < ox || shape.minX - pad > ox + part
                    || shape.maxY + pad < oy || shape.minY - pad > oy + part) {
                continue;
            }
            big.setColor(shape.colour);
            if (shape.stroke) {
                // Lines keep their width in pixels however far in: a border, not a band.
                float half = shape.width * SUPERSAMPLE / 2f;
                for (float[] line : shape.parts) {
                    for (int i = 0; i + 3 < line.length; i += 2) {
                        float x0 = (line[i] - ox) * scale, y0 = (line[i + 1] - oy) * scale;
                        float x1 = (line[i + 2] - ox) * scale, y1 = (line[i + 3] - oy) * scale;
                        fillSegment(big, x0, y0, x1, y1, half);
                    }
                }
            } else {
                List<float[]> rings = new ArrayList<>(shape.parts.size());
                for (float[] ring : shape.parts) {
                    float[] t = new float[ring.length];
                    // In pairs; a stray last number of a malformed path has no partner.
                    for (int i = 0; i + 1 < ring.length; i += 2) {
                        t[i] = (ring[i] - ox) * scale;
                        t[i + 1] = (ring[i + 1] - oy) * scale;
                    }
                    rings.add(t);
                }
                fillEvenOdd(big, rings);
            }
        }
        Pixmap out = new Pixmap(SIZE, SIZE, Pixmap.Format.RGBA8888);
        out.setBlending(Pixmap.Blending.None);
        out.setFilter(Pixmap.Filter.BiLinear);
        out.drawPixmap(big, 0, 0, canvas, canvas, 0, 0, SIZE, SIZE);
        return out;
        } finally {
            big.dispose();
        }
    }

    private static Tile parsed(int z, int x, int y) {
        String key = z + "/" + x + "_" + y;
        synchronized (PARSED) {
            Tile tile = PARSED.get(key);
            if (tile != null) {
                return tile;
            }
        }
        Tile tile = OCEAN_TILE;
        FileHandle file = Gdx.files.internal("world_map/" + key + ".svg");
        if (file.exists()) {
            try {
                tile = parse(file.readString("UTF-8"));
            } catch (RuntimeException unreadable) {
                tile = OCEAN_TILE;
            }
        }
        synchronized (PARSED) {
            PARSED.put(key, tile);
        }
        return tile;
    }

    private static final Pattern ELEMENT = Pattern.compile("<(rect|path)\\b([^>]*)/>");
    private static final Pattern ATTRIBUTE = Pattern.compile("([a-z-]+)=\"([^\"]*)\"");

    static Tile parse(String svg) {
        Tile tile = new Tile();
        Matcher element = ELEMENT.matcher(svg);
        while (element.find()) {
            Map<String, String> attributes = new LinkedHashMap<>();
            Matcher a = ATTRIBUTE.matcher(element.group(2));
            while (a.find()) {
                attributes.put(a.group(1), a.group(2));
            }
            if (element.group(1).equals("rect")) {
                String fill = attributes.get("fill");
                if (fill != null) {
                    tile.background = colour(fill);
                }
                continue;
            }
            String d = attributes.get("d");
            if (d == null) {
                continue;
            }
            Shape shape = new Shape();
            String stroke = attributes.get("stroke");
            shape.stroke = stroke != null && !"none".equals(stroke);
            shape.colour = colour(shape.stroke ? stroke : attributes.get("fill"));
            String width = attributes.get("stroke-width");
            shape.width = width == null ? 1f : Float.parseFloat(width);
            readPath(d, shape);
            if (!shape.parts.isEmpty()) {
                tile.shapes.add(shape);
            }
        }
        return tile;
    }

    private static int colour(String hex) {
        if (hex == null || !hex.startsWith("#")) {
            return OCEAN;
        }
        return Color.rgba8888(Color.valueOf(hex.substring(1)));
    }

    /** M, L and Z, absolute, numbers separated by spaces: what the generator writes. */
    private static void readPath(String d, Shape shape) {
        float[] points = new float[64];
        int count = 0;
        int i = 0, n = d.length();
        while (i < n) {
            char c = d.charAt(i);
            if (c == 'M' || c == 'Z') {
                if (count >= 4) {
                    shape.parts.add(Arrays.copyOf(points, count));
                }
                count = 0;
                i++;
            } else if (c == 'L' || c == ' ' || c == ',') {
                i++;
            } else {
                int start = i;
                while (i < n && "0123456789.-eE".indexOf(d.charAt(i)) >= 0) {
                    i++;
                }
                if (i == start) {
                    i++;   // anything else: skipped
                    continue;
                }
                float v = Float.parseFloat(d.substring(start, i));
                if (count == points.length) {
                    points = Arrays.copyOf(points, count * 2);
                }
                points[count++] = v;
                if ((count & 1) == 0) {
                    float x = points[count - 2];
                    shape.minX = Math.min(shape.minX, x);
                    shape.maxX = Math.max(shape.maxX, x);
                    shape.minY = Math.min(shape.minY, v);
                    shape.maxY = Math.max(shape.maxY, v);
                }
            }
        }
        if (count >= 4) {
            shape.parts.add(Arrays.copyOf(points, count));
        }
    }

    /**
     * Fills the rings even-odd - a lake inside a country's outline is a hole - row by row: at
     * each pixel row's middle, where the edges cross it, sorted, filling between each pair.
     */
    private static void fillEvenOdd(Pixmap pixmap, List<float[]> rings) {
        int size = pixmap.getHeight();
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        int edges = 0;
        for (float[] ring : rings) {
            edges += ring.length / 2;
            for (int i = 1; i < ring.length; i += 2) {
                minY = Math.min(minY, ring[i]);
                maxY = Math.max(maxY, ring[i]);
            }
        }
        int y0 = Math.max(0, (int) Math.floor(minY)), y1 = Math.min(size - 1, (int) Math.ceil(maxY));
        if (y0 > y1) {
            return;
        }
        // Each edge once, as x0 y0 x1 y1 with y0 < y1, sorted by where it starts.
        float[] e = new float[edges * 4];
        int m = 0;
        for (float[] ring : rings) {
            int points = ring.length / 2;
            for (int i = 0; i < points; i++) {
                int j = (i + 1) % points;
                float ax = ring[2 * i], ay = ring[2 * i + 1], bx = ring[2 * j], by = ring[2 * j + 1];
                if (ay == by) {
                    continue;
                }
                if (ay > by) {
                    float t = ax; ax = bx; bx = t;
                    t = ay; ay = by; by = t;
                }
                e[m++] = ax; e[m++] = ay; e[m++] = bx; e[m++] = by;
            }
        }
        int count = m / 4;
        Integer[] order = new Integer[count];
        for (int i = 0; i < count; i++) {
            order[i] = i;
        }
        final float[] edgesRef = e;
        Arrays.sort(order, (a, b) -> Float.compare(edgesRef[a * 4 + 1], edgesRef[b * 4 + 1]));
        int[] active = new int[count];
        int activeCount = 0, next = 0;
        float[] xs = new float[count];
        for (int y = y0; y <= y1; y++) {
            float yc = y + 0.5f;
            while (next < count && e[order[next] * 4 + 1] <= yc) {
                active[activeCount++] = order[next++];
            }
            int crossings = 0;
            for (int k = 0; k < activeCount; ) {
                int idx = active[k] * 4;
                if (e[idx + 3] <= yc) {
                    active[k] = active[--activeCount];   // ended above this row
                    continue;
                }
                float ax = e[idx], ay = e[idx + 1], bx = e[idx + 2], by = e[idx + 3];
                xs[crossings++] = ax + (yc - ay) * (bx - ax) / (by - ay);
                k++;
            }
            Arrays.sort(xs, 0, crossings);
            for (int k = 0; k + 1 < crossings; k += 2) {
                int from = Math.max(0, (int) Math.ceil(xs[k] - 0.5f));
                int to = Math.min(size - 1, (int) Math.floor(xs[k + 1] - 0.5f));
                if (to >= from) {
                    pixmap.fillRectangle(from, y, to - from + 1, 1);
                }
            }
        }
    }

    /** A line segment {@code half} each side of its centre line, as a filled quadrilateral. */
    private static void fillSegment(Pixmap pixmap, float x0, float y0, float x1, float y1, float half) {
        float dx = x1 - x0, dy = y1 - y0;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 1e-4f) {
            return;
        }
        // Each end reached a little past, so consecutive segments leave no notch at the joint.
        float ux = dx / length * half, uy = dy / length * half;
        float nx = -uy, ny = ux;
        List<float[]> quad = new ArrayList<>(1);
        quad.add(new float[]{
                x0 - ux + nx, y0 - uy + ny,
                x1 + ux + nx, y1 + uy + ny,
                x1 + ux - nx, y1 + uy - ny,
                x0 - ux - nx, y0 - uy - ny});
        fillEvenOdd(pixmap, quad);
    }
}
