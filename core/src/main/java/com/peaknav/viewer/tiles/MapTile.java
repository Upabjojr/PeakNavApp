package com.peaknav.viewer.tiles;

import static com.peaknav.compatibility.PeakNavAppState.getAppState;
import static com.peaknav.elevation.ElevationImageAbstract.numVertAttributes;
import static com.peaknav.elevation.ElevationUtils.getElevationCorrectionForRoundEarth;
import static com.peaknav.utils.PeakNavUtils.getC;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.peaknav.elevation.ElevationImageAbstract;
import com.peaknav.elevation.ElevationImageProvider;
import com.peaknav.utils.TileAndZoomElevFactor;
import com.peaknav.utils.TileBoundingBox;
import com.peaknav.utils.Units;
import com.peaknav.viewer.render_tiles.PixmapLayerName;

import org.mapsforge.core.model.BoundingBox;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.Tile;

public class MapTile {

    public static final byte ZOOM_LEVEL_MIN = (byte) 8;
    public static final byte ZOOM_LEVEL_MAX = (byte) 13;
    public static final int MF_ZOOM = 256;
    public final int zoomElevFactor;
    public final Tile tile;
    public final Tile tileMinZoom;
    private final int edgeLength;
    public transient ElevationImageAbstract elevationImage = null;
    // public ReentrantReadWriteLock tileLock = new ReentrantReadWriteLock();
    public final PixmapLayers pixmapLayers;
    // public boolean[] welded = {false, false, false, false};
    transient Mesh mesh;
    public TileBoundingBox tileBoundingBox;
    public transient Material material;
    public volatile ModelInstance instance;

    // public transient Texture texture;
    // public final Pixmap pixmap;
    public Lock pixmapLock = new ReentrantLock();

    // public final LayerQueueForMapTile layerQueueForMapTile;

    protected Future<?> future;
    private volatile boolean disposed = false;
    private final int textureWidth;
    private final int textureHeight;
    private volatile boolean roadsSet = false;
    // private Texture blockSatelliteTexture = null;
    private final LatLong center;
    private float[] vertices = null;

    public boolean isLayerDrawn(PixmapLayerName pixmapLayerName) {
        return textureLayerAdded.contains(pixmapLayerName);
    }

    public void reassignVertices() {
        uploadMeshVertices();
    }

    /** Reused scratch for the sanitised copy sent to the GPU while the skirt is still NaN. */
    private float[] renderVertices = null;

    /**
     * Uploads the vertices to the GPU, but with any not-yet-welded skirt — the last mesh row and
     * column, held at {@code NaN} until a neighbour tile fills them — replaced by their nearest
     * interior neighbour. Welding relies on those {@code NaN} markers in {@link #vertices}, yet the
     * GPU turns a {@code NaN} position into a black spike (see it over the sea) or a hole at a tile
     * seam. Sanitising only the uploaded copy keeps welding correct while keeping that garbage off
     * screen; for flat sea tiles the skirt collapses onto sea level, which is exactly right.
     */
    private void uploadMeshVertices() {
        if (mesh == null || vertices == null) {
            return;
        }
        float[] upload = vertices;
        if (hasNanElevation(vertices)) {
            upload = sanitizeSkirt(vertices);
        }
        mesh.setVertices(upload);
    }

    private static boolean hasNanElevation(float[] v) {
        for (int i = 2; i < v.length; i += numVertAttributes) {
            if (Float.isNaN(v[i])) {
                return true;
            }
        }
        return false;
    }

    private float[] sanitizeSkirt(float[] v) {
        if (renderVertices == null || renderVertices.length != v.length) {
            renderVertices = new float[v.length];
        }
        System.arraycopy(v, 0, renderVertices, 0, v.length);

        int count = v.length / numVertAttributes;
        // The mesh is square, so the grid width is the root of the vertex count. Deriving it from
        // the array itself (rather than getWidth()) stays correct whatever the tile's resolution.
        int width = (int) Math.round(Math.sqrt(count));
        int last = width - 1;
        for (int s = 0; s < count; s++) {
            int dst = s * numVertAttributes;
            if (!Float.isNaN(renderVertices[dst + 2])) {
                continue;
            }
            int x = s % width;
            int y = s / width;

            // Step direction from the skirt back into the interior (east and/or north edge).
            int dx = (x >= last) ? -1 : 0;
            int dy = (y >= last) ? -1 : 0;
            int nx = x + dx;
            int ny = y + dy;
            int inner1 = (nx + ny * width) * numVertAttributes;

            // Extrapolate the elevation from the two innermost rows so the edge continues the
            // (round-earth curved) surface rather than repeating the interior height — that keeps
            // adjacent flat sea tiles meeting flush, so the outline pass finds no false step there.
            int ix2 = nx + dx;
            int iy2 = ny + dy;
            if (ix2 >= 0 && ix2 < width && iy2 >= 0 && iy2 < width) {
                int inner2 = (ix2 + iy2 * width) * numVertAttributes;
                renderVertices[dst + 2] = 2f * renderVertices[inner1 + 2] - renderVertices[inner2 + 2];
            } else {
                renderVertices[dst + 2] = renderVertices[inner1 + 2];
            }
            // The normal only affects shading, so the nearest interior one is plenty.
            renderVertices[dst + 3] = renderVertices[inner1 + 3];
            renderVertices[dst + 4] = renderVertices[inner1 + 4];
            renderVertices[dst + 5] = renderVertices[inner1 + 5];
        }
        return renderVertices;
    }

    public float[] getMapTileVertices() {
        return vertices;
    }

    // Never really used, better call ElevationImage.getTileElevationLatitsFromMaxCoords
    public float getTileElevationLatitsFromMaxCoords2(float lon, float lat) {
        float x = (float) Units.convertLonitsToLatits(lon, getC().L.getTargetLatitude());

        BoundingBox boundingBox = tileBoundingBox.toMapsforgeBoundingBox();

        float coordStepX = (float) (boundingBox.maxLongitude - boundingBox.minLongitude);
        float coordStepY = (float) (boundingBox.maxLatitude - boundingBox.minLatitude);

        int rw = (int) ((lon - boundingBox.minLongitude) / coordStepX * edgeLength);
        int rh = (int) ((lat - boundingBox.minLatitude) / coordStepY * edgeLength);

        float elev = 0;
        elev += vertices[numVertAttributes*(rw + edgeLength*rh)+ 2];
        elev += vertices[numVertAttributes*(rw+1 + edgeLength*rh)+ 2];
        elev += vertices[numVertAttributes*(rw + edgeLength*(rh+1))+ 2];
        elev += vertices[numVertAttributes*(rw+1 + edgeLength*(rh+1))+ 2];
        return elev/4;
    }

    public static class DrawingPair {
        PixmapLayerName layer;
        Pixmap pixmap;
        public DrawingPair(PixmapLayerName layer, Pixmap pixmap) {
            this.layer = layer;
            this.pixmap = pixmap;
        }
    }

    private final ConcurrentHashMap<PixmapLayerName, Texture> textureMap = new ConcurrentHashMap<>();
    private final Queue<DrawingPair> texturePixmapMap = new LinkedBlockingQueue<>();
    private final Set<PixmapLayerName> textureLayerAdded = new HashSet<>();

    // Version of the GPX paths this tile's GPX_PATH texture was drawn for (see GpxTileRasterizer);
    // -1 means never drawn, so a tile picks the paths up as it loads.
    public volatile int gpxVersionDrawn = -1;
    public volatile boolean hasGpxTexture = false;


    public MapTileState getMapTileState() {
        return mapTileState;
    }

    public synchronized void setMapTileState(MapTileState mapTileState) {
        this.mapTileState = mapTileState;
    }

    public void setMapTileStateNoData() {
        setMapTileState(MapTileState.ELEVATION_DATA_NOT_FOUND);
    }

/*
    public boolean isNeedsRedrawPixmap() {
        return needsRedrawPixmap;
    }

    public void setNeedsRedrawPixmap(boolean needsRedrawPixmap) {
        this.needsRedrawPixmap = needsRedrawPixmap;
    }

    public boolean isNeedsRedrawTexture() {
        return needsRedrawTexture;
    }

    public void setNeedsRedrawTexture(boolean needsRedrawTexture) {
        this.needsRedrawTexture = needsRedrawTexture;
    }
*/

    public boolean isDisposed() {
        return disposed;
    }

    public int getTextureWidth() {
        return textureWidth;
    }

    public int getTextureHeight() {
        return textureHeight;
    }

    public void renderPixmapsToTextures() {
        // Requires OpenGL context!
        while (!texturePixmapMap.isEmpty()) {
            DrawingPair pair = texturePixmapMap.remove();
            // TODO: check if satellite provider has changed...
            Texture texture = new Texture(pair.pixmap);
            // The GPX layer stores an along-track phase in a colour channel; linear filtering would
            // interpolate across its wraps and smear the flow, so it is sampled nearest-neighbour.
            if (pair.layer == PixmapLayerName.GPX_PATH) {
                texture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            } else {
                texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            }
            PixmapLayerName layer = pair.layer;
            Texture previousTexture = textureMap.get(layer);
            textureMap.put(layer, texture);
            if (previousTexture != null) {
                previousTexture.dispose();
            }
            pair.pixmap.dispose();
            refreshUserData();
        }
    }

    public void setTexturePixmap(PixmapLayerName layer, Pixmap pixmap) {
        texturePixmapMap.add(new DrawingPair(layer, pixmap));
        getC().mapTilePixmapToTexturesHandler.addMapTileToQueue(this);
        textureLayerAdded.add(layer);
    }

    public enum MapTileState {
        ELEVATION_DATA_NOT_LOADED,
        ELEVATION_DATA_LOADING,
        CAN_DRAW,
        IS_DRAWN,
        ELEVATION_DATA_NOT_FOUND
    }

    private volatile MapTileState mapTileState;
    // private volatile boolean needsRedrawTexture = false;
    // private volatile boolean needsRedrawPixmap = false;

    int numVertices;

    public MapTile(Tile tile) {
        this(tile, computeZoomElevFactor(tile));
    }

    public static int computeZoomElevFactor(Tile tile) {
        return computeZoomElevFactor(tile.zoomLevel);
    }

    public static int computeZoomElevFactor(int zoomLevel) {
        // int f = 1 << (tile.zoomLevel - MapTile.ZOOM_LEVEL_MIN);
        return Integer.max(0, 6 - (zoomLevel - MapTile.ZOOM_LEVEL_MIN));
    }

    public MapTile(Tile tile, int zoomElevFactor) {
        this.tile = tile;
        Tile tileMinZoom = tile;
        while (tileMinZoom.zoomLevel > ZOOM_LEVEL_MIN)
            tileMinZoom = tileMinZoom.getParent();
        this.tileMinZoom = tileMinZoom;

        this.zoomElevFactor = zoomElevFactor;
        this.edgeLength = 1 + 4096 / (1 << (tile.zoomLevel - ZOOM_LEVEL_MIN)) / (1 << zoomElevFactor);

        textureWidth = 1024; // 15 * (getWidth() - 1);
        textureHeight = 1024; // 15 * (getHeight() - 1);

        tileBoundingBox = new TileBoundingBox(
                tile.getBoundingBox()
        );

        center = tile.getBoundingBox().getCenterPoint();

        // TODO: create two materials (roads and satellite)!
        material = new Material(ColorAttribute.createDiffuse(Color.WHITE));

        setMapTileState(MapTileState.ELEVATION_DATA_NOT_LOADED);

        // elevationImageStorage = new ElevationImageStorage(MapTile.this);
        pixmapLayers = new PixmapLayers(this);
    }

    public int getWidth() {
        return edgeLength;
    }

    public int getHeight() {
        return edgeLength;
    }

    public void prepareMeshData() {
        numVertices = getWidth() * getHeight();
        int numIndices = (getWidth() - 1) * (getHeight() - 1) * 6;
        mesh = new Mesh(true, numVertices, numIndices, getC().staticData.mapTileVertexAttributes);
        mesh.setIndices(elevationImage.getMeshIndices());
        uploadMeshVertices();
        buildModelInstance();
    }

    private float elevationCorrectionForRoundEarth(float[] vertices, int i, final float cLon, final float cLat, float corrForRadius, float dx, float dy) {
        float longitude = tileBoundingBox.west + dx*vertices[i+6];
        float latitude = tileBoundingBox.south + dy*(1.0f - vertices[i+7]);
        return getElevationCorrectionForRoundEarth(latitude, longitude);
    }

    private void correctForRoundEarth(float[] vertices) {
        final float cLon = getC().L.getTargetLongitude();
        final float cLat = getC().L.getTargetLatitude();

        final float corrForRadius = (float) Math.pow(Math.cos(Math.toRadians(cLat)), 2);
        final float dx = tileBoundingBox.east - tileBoundingBox.west;
        final float dy = tileBoundingBox.north - tileBoundingBox.south;

        for (int i = 0; i < vertices.length; i += numVertAttributes) {
            float dz = elevationCorrectionForRoundEarth(vertices, i, cLon, cLat, corrForRadius, dx, dy);
            vertices[i + 2] -= dz;
        }
    }

    public void buildModelInstance() {
        ModelBuilder modelBuilder = new ModelBuilder();
        modelBuilder.begin();
        modelBuilder.part("meshMap", mesh, GL20.GL_TRIANGLES, material);
        Model modelMesh = modelBuilder.end();

        instance = new ModelInstance(modelMesh, 0, 0, 0);
        refreshUserData();
    }

    private void refreshUserData() {
        if (instance == null)
            return;
        instance.userData = new RenderableUserData(this,
                textureMap.get(PixmapLayerName.BASE_ROADS),
                textureMap.get(PixmapLayerName.UNDERLAY_LAYER),
                textureMap.get(PixmapLayerName.GPX_PATH));
    }

    public void dispose() {
        // WARNING: .dispose() should always be called by the main thread!
        // assert Thread.currentThread().getName().contains("main");
        disposed = true;
        if (mesh != null) {
            mesh.dispose();
            mesh = null;
        }
        for (Texture texture : textureMap.values()) {
            texture.dispose();
        }
        textureMap.clear();
        if (material != null) {
            material = null;
        }
        instance = null;
        pixmapLock.lock();

        if (elevationImage != null) {
            elevationImage.dispose();
        }
        elevationImage = null;
    }

    public void addWeldersForTile() {
        // Add welders to tiles:
        //  - always if they are larger,
        //  - to the east and north if they are the same size of the current map tile
        Tile[] otherMFTiles = {tile.getRight(), tile.getLeft(), tile.getAbove(), tile.getBelow()};
        for (Tile otherMF : otherMFTiles) {
            MapTile otherTile = getC().mapTileStorage.getFromMapIndexLessEq(otherMF);
            if (otherTile == null)
                continue;
            getC().weldingQueue.add(new MapTileWelder(this, otherTile));
        }
    }

    public void drawGraphics() {

        prepareMeshData();

        addWeldersForTile();

        // blockSatelliteTexture = getSatelliteTextureBlock(getMinZoomTileWithElevFactor());

        setMapTileState(MapTileState.IS_DRAWN);
    }

    public Future<?> submitToExecutor(ElevationImageProvider provider) {
        provider.incrementReferenceCounter();
        future = getC().executorEleLoad.submit(() -> {
            elevationImage = provider.provideForMapTile(MapTile.this);
            // elevationImageMesh = elevationImageStorage.retrieveMesh();
            if (elevationImage == null) {
                // This probably means the elevation file has not been found:
                setMapTileState(MapTileState.ELEVATION_DATA_NOT_FOUND);
                return;
            }
            callVertexRetrieval();
            if (tileBoundingBox.toMapsforgeBoundingBox().contains(
                    getC().L.getTargetLatLong()
            )) {
                float ele = elevationImage.getTileElevationLatitsFromMaxCoords(
                        getC().L.getTargetLongitude(), getC().L.getTargetLatitude());
                getC().L.setCurrentTerrainEle(ele);
            }
            provider.decrementReferenceCounter();
        });
        return future;
    }

    public void recomputeNormals() {
        future = getC().executorEleLoad.submit(() -> {
            elevationImage.setVertexNormals(vertices);
            Gdx.app.postRunnable(this::uploadMeshVertices);
        });
    }

    private void callVertexRetrieval() {
        getAppState().setLastAnyMapTileUpdateTimeToNow();

        // This two are meant to avoid computing vertices and indices on the OpenGL thread:
        elevationImage.getMeshVertices();
        elevationImage.getMeshIndices();

        float[] oldVertices = elevationImage.getMeshVertices();
        if (vertices == null) {
            vertices = new float[oldVertices.length];
        }
        System.arraycopy(oldVertices, 0, vertices, 0, oldVertices.length);
        correctForRoundEarth(vertices);

        setMapTileState(MapTileState.CAN_DRAW);
    }

    public LatLong getImpWhiteTileIndex() {
        return center;
    }

    /*
    public void redrawTexture() {
        assert Thread.currentThread().getName().contains("GLThread");
        // this.tileLock.writeLock().lock();
        if (texture != null)
            texture.dispose();
        texture = new Texture(pixmap);
        material = new Material(TextureAttribute.createDiffuse(texture));
        buildModelInstance();
        setNeedsRedrawTexture(false);
    }
     */

    public static class RenderableUserData {
        public final MapTile mapTile;
        public final Texture textureRoads;
        public final Texture textureSatellite;
        public final Texture textureGpx;
        // public final Texture textureNormals;

        public RenderableUserData(MapTile mapTile,
                                  Texture textureRoads,
                                  Texture textureSatellite,
                                  Texture textureGpx
                                  ) {
            this.mapTile = mapTile;
            this.textureRoads = textureRoads;
            this.textureSatellite = textureSatellite;
            this.textureGpx = textureGpx;
        }

    }

    public Tile getMinZoomTile() {
        return tileMinZoom;
        /*
        Tile ptile = tile;
        while (ptile.zoomLevel > ZOOM_LEVEL_MIN)
            ptile = ptile.getParent();
        return ptile;
         */
    }

    public TileAndZoomElevFactor getMinZoomTileWithElevFactor() {
        return new TileAndZoomElevFactor(getMinZoomTile(), zoomElevFactor);
    }

    /*
    In Python, np.fromfile("filename", dtype=np.float32)
     */
    public void serializeVerticesToFile(File outputFile) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(vertices.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        byteBuffer.asFloatBuffer().put(vertices);
        try {
            new DataOutputStream(new FileOutputStream(outputFile)).write(byteBuffer.array());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
