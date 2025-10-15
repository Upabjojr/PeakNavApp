package com.peaknav.viewer.renderer_gdx;

import static com.peaknav.compatibility.PeakNavAppState.getAppState;
import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PreferencesManager.P;
import static com.peaknav.viewer.tiles.MapTile.MapTileState.CAN_DRAW;
import static com.peaknav.viewer.tiles.MapTile.MapTileState.ELEVATION_DATA_LOADING;
import static com.peaknav.viewer.tiles.MapTile.MapTileState.ELEVATION_DATA_NOT_LOADED;
import static com.peaknav.viewer.tiles.MapTile.MapTileState.IS_DRAWN;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.Shader;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.graphics.g3d.utils.DefaultShaderProvider;
import com.badlogic.gdx.graphics.g3d.utils.TextureDescriptor;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.peaknav.elevation.ElevationImageProvider;
import com.peaknav.utils.TileAndZoomElevFactor;
import com.peaknav.viewer.MapViewerSingleton;
import com.peaknav.viewer.PerspectiveCameraExt;
import com.peaknav.viewer.render_tiles.ImpactPixmap;
import com.peaknav.viewer.screens.LabelLoading;
import com.peaknav.viewer.tiles.MapTile;
import com.peaknav.viewer.tiles.MapTileWelder;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class TileBatchRenderer {

    private final ModelBatch modelBatch;
    private final PerspectiveCameraExt camera;
    private final Environment environment;
    private final ModelBatch modelBatchPseudodistances;
    private FrameBuffer fbo;
    private final ExecutorService executorStartThreads = Executors.newSingleThreadExecutor();
    private volatile Future<?> futureStartThreads = null;

    private final ExecutorService executorMapTileFixer = Executors.newSingleThreadExecutor();

    public TileBatchRenderer(PerspectiveCameraExt camera, Environment environment) {
        this.camera = camera;
        this.environment = environment;

        this.modelBatch = new ModelBatch(null,
                new DefaultShaderProvider(
                        Gdx.files.internal("vertex_shader.glsl").readString(),
                        Gdx.files.internal("fragment_shader.glsl").readString()) {
                    @Override
                    protected Shader createShader(final Renderable renderable) {
                        // WARNING: do not read "userData" here, as "renderable" refers to the first
                        // renderable in the array, not necessarily the correct one!
                        DefaultShader shader = new DefaultShader(renderable, config);

                        BaseShader.Uniform u_whiteBackground = new BaseShader.Uniform("u_whiteBackground");
                        shader.register(u_whiteBackground, new BaseShader.LocalSetter() {
                            @Override
                            public void set(BaseShader shader, int inputID, Renderable renderable, Attributes combinedAttributes) {
                                MapTile.RenderableUserData rud = (MapTile.RenderableUserData) renderable.userData;
                                int whiteBackground = (P.isLayerVisibleUnderlayLayer())? 0 : 1;
                                if (rud.textureSatellite == null)
                                    whiteBackground = 1;
                                shader.program.setUniformi(u_whiteBackground.alias, whiteBackground);
                            }
                        });

                        BaseShader.Uniform u_roadsSet = new BaseShader.Uniform("u_roadsSet");
                        shader.register(u_roadsSet, new BaseShader.LocalSetter() {
                            @Override
                            public void set(BaseShader shader, int inputID, Renderable renderable, Attributes combinedAttributes) {
                                MapTile.RenderableUserData rud = (MapTile.RenderableUserData) renderable.userData;
                                shader.program.setUniformi(
                                        u_roadsSet.alias,
                                        ((rud.textureRoads != null) && P.isViewerLayerVisibleBaseRoads())? 1 : 0);
                            }
                        });

                        BaseShader.Uniform u_textureSatellite = new BaseShader.Uniform("u_textureSatellite");
                        TextureDescriptor<Texture> textureDescriptor = new TextureDescriptor<>();
                        shader.register(u_textureSatellite, new BaseShader.LocalSetter() {
                            @Override
                            public void set(BaseShader shader, int inputID, Renderable renderable, Attributes combinedAttributes) {
                                Texture texture = ((MapTile.RenderableUserData) renderable.userData).textureSatellite;
                                if (texture == null)
                                    return;
                                textureDescriptor.set(texture, null, null, null, null);
                                final int unit = shader.context.textureBinder.bind(textureDescriptor);
                                shader.set(inputID, unit);
                            }
                        });
                        
                        BaseShader.Uniform u_textureRoads = new BaseShader.Uniform("u_textureRoads");
                        TextureDescriptor<Texture> textureDescriptor2 = new TextureDescriptor<>();

                        shader.register(u_textureRoads, new BaseShader.LocalSetter() {
                            @Override
                            public void set(BaseShader shader, int inputID, Renderable renderable, Attributes combinedAttributes) {
                                Texture texture = ((MapTile.RenderableUserData) renderable.userData).textureRoads;
                                if (texture == null)
                                    return;
                                textureDescriptor2.set(texture, null, null, null, null);
                                final int unit = shader.context.textureBinder.bind(textureDescriptor2);
                                shader.set(inputID, unit);
                            }
                        });

                        return shader;
                    }
                },
                null);

        this.modelBatchPseudodistances = new ModelBatch(null,
                new DefaultShaderProvider(
                        Gdx.files.internal("vertex_shader_pseudodistances.glsl").readString(),
                        Gdx.files.internal("fragment_shader_pseudodistances.glsl").readString()),
                null);

        this.fbo = new FrameBuffer(
                Pixmap.Format.RGBA8888,
                Gdx.graphics.getWidth(),
                Gdx.graphics.getHeight(),
                true);
    }

    public void startElevationRetrievalAndAssignmentThreads() {
        // TODO: add a maximum amount of rescales that can be computed in a single run:
        // getC().elevationImageRescaleManager.processRemainingCoordBlocks(rescaleShader);

        if (futureStartThreads != null && !futureStartThreads.isDone())
            return;

        futureStartThreads = executorStartThreads.submit(() -> {
            boolean flag = true;
            for (MapTile mapTile : getC().mapTileStorage.getMapTiles()) {
                if (mapTile.getMapTileState() != ELEVATION_DATA_NOT_LOADED)
                    continue;
                flag = false;

                TileAndZoomElevFactor tileZoom = mapTile.getMinZoomTileWithElevFactor();
                ElevationImageProvider provider = getC().elevationImageProviderManager.getProviderOrQueueForLoading(tileZoom);
                if (provider == null) {
                    continue;
                }
                if (provider.isLoaded()) {
                    mapTile.setMapTileState(ELEVATION_DATA_LOADING);
                    mapTile.submitToExecutor(provider);
                } else {
                    if (provider.isMissingElevationFiles()) {
                        mapTile.setMapTileStateNoData();
                        // TODO: remove from tile list!
                    }
                }

            }
            if (flag && MapViewerSingleton.getViewerInstance().labelLoading.getState() == LabelLoading.State.LOADING) {
                MapViewerSingleton.getViewerInstance().labelLoading.setState(LabelLoading.State.LOADED);
            }
        });
    }

    public void render() {
        drawQueuedMapTiles();

        if (getC().mapTileStorage.readyToDispose) {
            getC().mapTileStorage.readyToDispose = false;
            while (!getC().mapTileStorage.mapTilesForDisposal.isEmpty()) {
                MapTile mapTile = getC().mapTileStorage.mapTilesForDisposal.pop();
                mapTile.dispose();
            }
        }

        if (!getC().weldingQueue.isEmpty()) {
            weldMapTiles();
        }

        modelBatch.begin(camera);

        try {
            for (MapTile mapTile : getC().mapTileStorage.getMapTiles()) {

                try {
                    if (!mapTile.isDisposed() && mapTile.instance != null) {
                        // checkIfMapTileNeedsRedraw(mapTile);
                        modelBatch.render(mapTile.instance, environment);
                    }
                } catch (Throwable throwable) {
                    // CrashLogger crashLogger = getLoadFactory().getCrashLogger(throwable, "TileBatchRenderer.modelBatch.render(...)");
                    // crashLogger.logToFile();
                }

            }

        } catch (Throwable throwable) {
            // CrashLogger crashLogger = getLoadFactory().getCrashLogger(throwable, "modelBatch");
            // crashLogger.logToFile();
        } finally {
            modelBatch.end();
        }

        // timeWarner.track("end modelBatch.end()");
    }

    /*
    private void checkIfMapTileNeedsRedraw(MapTile mapTile) {
        boolean locked = mapTile.pixmapLock.tryLock();
        if (locked) {
            try {
                if (mapTile.isNeedsRedrawPixmap()) { // TODO: not thread safe
                    mapTile.setNeedsRedrawPixmap(false);
                    getC().tileManager.tileRenderer.execDraw.executeStoppableRunnable(
                            new ExecDrawRunner(mapTile)
                    );
                }
                if (mapTile.isNeedsRedrawTexture()) { // TODO: not thread safe
                    mapTile.redrawTexture();
                }
            } finally {
                mapTile.pixmapLock.unlock();
            }
        }
    }
    */

    private void weldMapTiles() {
        executorMapTileFixer.submit(() -> {
            getAppState().setLastAnyMapTileUpdateTimeToNow();
            List<MapTileWelder> readd = new LinkedList<>();
            while (!getC().weldingQueue.isEmpty()) {
                MapTileWelder welder = getC().weldingQueue.remove();
                if (welder.canWeldIsDrawn()) {
                    welder.weldLockPositions();
                } else if (!welder.isTileDisposed() && !welder.isElevationDataNotFound()) {
                    if (!getC().weldingQueue.contains(welder)) {
                        readd.add(welder);
                    }
                }
            }
            getC().weldingQueue.addAll(readd);
        });
    }

    public void renderPseudodistances()  {
        renderPseudodistances(camera, false);
    }

    public Pixmap renderPseudodistances(Camera camera, boolean requestPixmap) {
        fbo.begin();
        Gdx.gl.glClearColor(1, 1, 1, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);

        // ScreenUtils.clear(new Color(1, 1, 1, 1));
        try {
            modelBatchPseudodistances.begin(camera);
            for (MapTile mapTile : getC().mapTileStorage.getMapTiles()) {
                if (mapTile.isDisposed()) {
                    continue;
                }
                if (mapTile.getMapTileState() == IS_DRAWN) {
                    if (mapTile.instance != null) {
                        // TODO: maybe this should be all inside a lock?
                        modelBatchPseudodistances.render(mapTile.instance);
                    }
                }
            }
        } catch (Throwable throwable) {
            // CrashLogger crashLogger = getLoadFactory().getCrashLogger(throwable, "fbo.modelBatch");
            // crashLogger.logToFile();
        } finally {
            modelBatchPseudodistances.end();
        }

        Pixmap pixmap = null;

        if (requestPixmap) {
            // pixmap = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            pixmap = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        }

        fbo.end();

        return pixmap;
    }

    /*
    public void renderPseudodistancesNoFrameBuffer() {
        try {
            modelBatchPseudodistances.begin(camera.camera180degPointNorth);
            for (MapTile mapTile : getC().mapTileStorage.getMapTiles()) {
                if (mapTile.isDisposed()) {
                    continue;
                }
                if (mapTile.getMapTileState() == IS_DRAWN) {
                    if (mapTile.instance != null) {
                        // TODO: maybe this should be all inside a lock?
                        modelBatchPseudodistances.render(mapTile.instance);
                    }
                }
            }
        } catch (Throwable throwable) {
            // CrashLogger crashLogger = getLoadFactory().getCrashLogger(throwable, "fbo.modelBatch");
            // crashLogger.logToFile();
        } finally {
            modelBatchPseudodistances.end();
        }
    }
     */


    public void renderPseudodistancesGeographical(ImpactPixmap impactPixmap) {
        if (impactPixmap.impactPixmapNewRequested) {
            Pixmap pixmapNorth = renderPseudodistances(camera.camera180degPointNorth, true);
            Pixmap pixmapEast = renderPseudodistances(camera.camera180degPointEast, true);
            Pixmap pixmapSouth = renderPseudodistances(camera.camera180degPointSouth, true);
            Pixmap pixmapWest = renderPseudodistances(camera.camera180degPointWest, true);

            impactPixmap.setPixmapGeographical(pixmapNorth, pixmapEast, pixmapSouth, pixmapWest);
            impactPixmap.impactPixmapNewRequested = false;
        }
    }

    public Texture getSobelTexture() {
        Texture distanceTex = fbo.getColorBufferTexture();
        distanceTex.bind(0);
        distanceTex.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        distanceTex.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        return distanceTex;
    }


    private void drawQueuedMapTiles() {
        for (MapTile mapTile : getC().mapTileStorage.getMapTiles()) {
            if (mapTile.isDisposed()) {
                continue;
            }
            if (mapTile.getMapTileState() == CAN_DRAW) {
                getAppState().setLastAnyMapTileUpdateTimeToNow();
                mapTile.drawGraphics();
            }
        }
    }

    private void resizeCamera(Camera camera, int width, int height) {
        if (camera == null)
            return;
        camera.viewportWidth = width;
        camera.viewportHeight = height;
        camera.update();
    }
    public void resize(int width, int height) {
        this.fbo = new FrameBuffer(
                Pixmap.Format.RGBA8888,
                width,
                height,
                true);

        resizeCamera(modelBatchPseudodistances.getCamera(), width, height);
        resizeCamera(modelBatch.getCamera(), width, height);
    }

}
