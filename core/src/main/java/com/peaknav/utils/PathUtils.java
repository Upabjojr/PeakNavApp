package com.peaknav.utils;

import com.badlogic.gdx.Gdx;
import com.peaknav.pbf.PbfLayer;

import org.mapsforge.core.model.Tile;

import java.io.File;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

public class PathUtils {

    public static void createRecurrentPathsForOsmTilesInExternal(List<String> dirs) {
        if (dirs.size() == 0)
            return;
        File current = new File(Gdx.files.getExternalStoragePath());
        for (String dir : dirs) {
            current = new File(current, dir);
            if (!current.exists()) {
                current.mkdir();
            }
        }
    }

    public static void createRecurrentPathsForOsmTilesInExternal(String path) {
        List<String> dirs = Arrays.asList(path.split("/"));
        dirs = dirs.subList(0, dirs.size()-1);
        createRecurrentPathsForOsmTilesInExternal(dirs);
    }

    public static String getMapFolder() {
        return "map_folder";
    }

    public static String getElevationFolder() {
        return "elev_tiles";
    }

    public static File getPbfExternalFilePath(Tile tile, PbfLayer pbfLayer) {
        File file = new File(Gdx.files.external(getMapFolder()).file(), pbfLayer.name());
        LinkedList<String> dirs = getDirsOfOsmPbfFile(file, tile, ".osm.pbf", pbfLayer.name());
        File path = new File(dirs.removeFirst());
        while (!dirs.isEmpty()) {
            path = new File(path, dirs.removeFirst());
        }
        return path;
    }

    public static String createRecurrentPathsForOsmTilesInExternal(String basePath, Tile tile, String extension, String category) {
        List<String> dirs = getDirsOfOsmPbfFile(basePath, tile, extension, category);
        createRecurrentPathsForOsmTilesInExternal(dirs.subList(0, dirs.size()-1));
        return joinPaths(dirs);
    }

    private static LinkedList<String> getDirsOfOsmPbfFile(File basePath, Tile tile, String extension, String category) {
        return getDirsOfOsmPbfFile(basePath.toString(), tile, extension, category);
    }

    public static LinkedList<String> getDirsOfOsmPbfFile(String basePath, Tile tile, String extension, String category) {
        LinkedList<String> dirs = new LinkedList<>();
        dirs.add(basePath);
        dirs.add(String.format(Locale.ENGLISH, "zoom_%02d", tile.zoomLevel));

        dirs.add(String.format(Locale.ENGLISH, "xa_%02d", tile.tileX / 100));
        dirs.add(String.format(Locale.ENGLISH, "xb_%02d", tile.tileX % 100));

        String last;

        dirs.add(String.format(Locale.ENGLISH, "ya_%02d", tile.tileY / 100));
        last = String.format(Locale.ENGLISH, "yb_%02d_%s_z_%02d_x_%04d_y_%04d", tile.tileY % 100, category, tile.zoomLevel, tile.tileX, tile.tileY);

        dirs.add(last + extension);
        return dirs;
    }

    private static String joinPaths(List<String> dirs) {
        String[] pathArray = new String[dirs.size()];
        dirs.toArray(pathArray);
        return joinPaths(pathArray);
    }

    public static String joinPaths(String... paths) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < paths.length - 1; i++) {
            builder.append(paths[i]);
            if (!paths[i].endsWith("/"))
                builder.append("/");
        }
        builder.append(paths[paths.length-1]);
        return builder.toString();
    }

    public static Tile findTileWithDataByZoomingOut(Tile tile, PbfLayer overlay) {
        Tile curTile = tile;
        while (curTile != null) {
            if (getPbfExternalFilePath(curTile, overlay).exists()) {
                return curTile;
            }
            curTile = curTile.getParent();
        }
        return null;
    }
}
