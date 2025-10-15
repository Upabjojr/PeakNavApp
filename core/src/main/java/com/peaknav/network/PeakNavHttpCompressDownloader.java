package com.peaknav.network;

import static com.peaknav.elevation.ElevationImageStorage.getElevTileTarGzPath;
import static com.peaknav.utils.PathUtils.getMapFolder;
import static com.peaknav.utils.PathUtils.joinPaths;

import com.peaknav.database.MapSqlite;
import com.peaknav.utils.PathUtils;

import org.mapsforge.core.model.Tile;

import java.util.LinkedList;
import java.util.List;

public class PeakNavHttpCompressDownloader {
    String prefixElev = "https://huggingface.co/datasets/PeakNav/global-elevation-aster-slippy-tiles-tar-gz/resolve/main/";
    String prefixMapFolderData = "https://huggingface.co/datasets/PeakNav/global-openstreetmap-extraction-slippy-tiles-tar/resolve/main/";

    public PeakNavHttpCompressDownloader() {
    }

    public List<HfDownloadUrl> getHfDownloadUrlList(List<MapSqlite.QueuedTile> queuedTiles) {
        List<HfDownloadUrl> objects = new LinkedList<>();

        for (MapSqlite.QueuedTile queuedTile : queuedTiles) {
            Tile tile = queuedTile.toTile();
            String extension;
            String relativeFilePath;
            String prefix;
            if (queuedTile.layer.equals("elev")) {
                extension = ".tar.gz";
                relativeFilePath = joinPaths(getElevTileTarGzPath(queuedTile.toTile()));
                prefix = prefixElev;
            } else {
                extension = ".tar";
                String category = queuedTile.pbfLayer.name();
                String basePath = joinPaths(getMapFolder(), category);
                relativeFilePath = PathUtils.createRecurrentPathsForOsmTilesInExternal(basePath, tile, extension, category);
                prefix = prefixMapFolderData;
            }
                objects.add(new HfDownloadUrl(prefix, relativeFilePath, queuedTile));
        }
        return objects;
    }


    public static class HfDownloadUrl {
        public final String prefix;
        public final String objectKey;
        public final MapSqlite.QueuedTile queuedTile;

        public String getUrl() {
            return prefix + objectKey;
        }

        public HfDownloadUrl(String prefix, String objectKey, MapSqlite.QueuedTile queuedTile) {
            this.prefix = prefix;
            this.objectKey = objectKey;
            this.queuedTile = queuedTile;
        }
    }

}
