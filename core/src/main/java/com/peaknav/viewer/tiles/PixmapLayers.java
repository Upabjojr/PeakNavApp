package com.peaknav.viewer.tiles;

import com.peaknav.utils.PeakNavThreadExecutor;
import com.peaknav.viewer.render_tiles.PixmapLayerName;

import java.io.File;
import java.util.Comparator;
import java.util.concurrent.PriorityBlockingQueue;

public class PixmapLayers {

    // final ConcurrentLinkedQueue<PixmapLayersDrawingAction> drawingActions = new ConcurrentLinkedQueue<>();
    /*
    final PriorityBlockingQueue<PixmapLayersDrawingAction> drawingActionsPQ = new PriorityBlockingQueue<>(
            64,
            (Comparator<PixmapLayersDrawingAction>) (a1, a2) -> Integer.compare(a1.pixmapLayerName.ordinal(), a2.pixmapLayerName.ordinal())
    );
     */
    // public volatile boolean drawingActionsRunning = false;
    // final ReentrantReadWriteLock rwlock = new ReentrantReadWriteLock();
    // final EnumMap<PixmapLayerName, ConcurrentLinkedQueue<PixmapLayersDrawingAction>> drawingActions = new EnumMap<>(PixmapLayerName.class);

    final MapTile mapTile;
    // TODO: this executor keeps ending up in WAIT state:
    private static final PeakNavThreadExecutor executorPixmapDrawing = new PeakNavThreadExecutor(4, "pixmapDrawingExec");

    public PixmapLayers(MapTile mapTile) {
        this.mapTile = mapTile;
    }

    public static synchronized void stopLayerDrawPixmapExecutor() {
        executorPixmapDrawing.stopLoop();
    }

    /*
    public synchronized void queueDrawingActions(PixmapLayerName pixmapLayerName, File tileFile, int srcX, int srcY, int srcWidth, int srcHeight, int destX, int destY, int ovWidth, int ovHeight) {
        drawingActionsPQ.add(new PixmapLayersDrawingAction(this, pixmapLayerName, tileFile, srcX, srcY, srcWidth, srcHeight, destX, destY, ovWidth, ovHeight));
    }
     */

    public void dispose() {
    }

/*    public PriorityBlockingQueue<PixmapLayersDrawingAction> getDrawingActionsPQ() {
        return drawingActionsPQ;
    }*/
}
