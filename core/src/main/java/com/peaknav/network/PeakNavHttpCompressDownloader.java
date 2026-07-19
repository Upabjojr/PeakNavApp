package com.peaknav.network;

import static com.peaknav.elevation.ElevationImageStorage.getElevTileTarGzPath;
import static com.peaknav.utils.PathUtils.getMapFolder;
import static com.peaknav.utils.PathUtils.joinPaths;

import com.peaknav.database.MapSqlite;
import com.peaknav.utils.PathUtils;

import org.mapsforge.core.model.Tile;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Turns queued tiles into the URLs they can be downloaded from. Each tile maps to a
 * provider-independent relative path (its {@code objectKey}); the actual URLs are that
 * path appended to each configured {@link DownloadProvider}'s base URL, in order, so the
 * caller can try one after another (see {@link DownloadProviderRegistry}).
 */
public class PeakNavHttpCompressDownloader {

    private final DownloadProviderRegistry providerRegistry;

    public PeakNavHttpCompressDownloader(DownloadProviderRegistry providerRegistry) {
        this.providerRegistry = providerRegistry;
    }

    public List<DownloadTarget> getDownloadTargets(List<MapSqlite.QueuedTile> queuedTiles) {
        List<DownloadProvider> providers = providerRegistry.getProviders();
        List<DownloadTarget> targets = new LinkedList<>();

        for (MapSqlite.QueuedTile queuedTile : queuedTiles) {
            Tile tile = queuedTile.toTile();
            boolean elevation = queuedTile.layer.equals("elev");

            String objectKey;
            if (elevation) {
                objectKey = joinPaths(getElevTileTarGzPath(tile));
            } else {
                String category = queuedTile.pbfLayer.name();
                String basePath = joinPaths(getMapFolder(), category);
                // The extension comes from the layer: the PBF extracts ship as plain .tar, the
                // area tiles as .tar.gz.
                objectKey = PathUtils.createRecurrentPathsForOsmTilesInExternal(
                        basePath, tile, queuedTile.pbfLayer.getArchiveExtension(), category);
            }

            List<String> candidateUrls = new ArrayList<>(providers.size());
            for (DownloadProvider provider : providers) {
                String base = provider.baseUrlFor(elevation);
                if (base != null) {
                    candidateUrls.add(base + objectKey);
                }
            }

            targets.add(new DownloadTarget(objectKey, candidateUrls, queuedTile));
        }
        return targets;
    }

    /** A single tile to fetch: its local relative path plus the ordered URLs to try for it. */
    public static class DownloadTarget {
        public final String objectKey;
        public final List<String> candidateUrls;
        public final MapSqlite.QueuedTile queuedTile;

        public DownloadTarget(String objectKey, List<String> candidateUrls, MapSqlite.QueuedTile queuedTile) {
            this.objectKey = objectKey;
            this.candidateUrls = candidateUrls;
            this.queuedTile = queuedTile;
        }

        /** The first candidate URL, for logging/messages; null when no provider is configured. */
        public String getUrl() {
            return candidateUrls.isEmpty() ? null : candidateUrls.get(0);
        }
    }
}
