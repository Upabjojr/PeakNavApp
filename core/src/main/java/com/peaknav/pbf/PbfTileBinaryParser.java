package com.peaknav.pbf;


import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.Tag;
import org.mapsforge.core.model.Tile;
import org.mapsforge.map.datastore.MapReadResult;
import org.mapsforge.map.datastore.PointOfInterest;
import org.mapsforge.map.datastore.Way;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import crosby.binary.BinaryParser;
import crosby.binary.Osmformat;

public class PbfTileBinaryParser extends BinaryParser {

    private static final String TAG = "PbfTileBinaryParser";
    private final Tile tile;
    private final MapReadResult mapReadResult;
    Map<Long, List<Long>> wayToNodeIds = new HashMap<>();
    Map<Long, List<Tag>> wayToTags = new HashMap<>();
    Map<Long, LatLong> nodeToLatLong = new HashMap<>();

    public PbfTileBinaryParser(Tile tile, MapReadResult mapReadResult) {
        this.tile = tile;
        this.mapReadResult = mapReadResult;
        System.err.println("Parsing tile " + tile);
    }

    public static long convertCoordToLong(double coord) {
        return (long) (coord*1e7);
    }

    public static double convertLongToCoord(long coord) {
        return ((double)coord)/1e7;
    }

    @Override
    protected void parseRelations(List<Osmformat.Relation> rels) {
        for (Osmformat.Relation rel : rels) {
            rel.getId();
            List<Tag> tags = getTagList(rel.getKeysList(), rel.getValsList());
            List<Long> memids = new LinkedList<>();
            long memid = 0;
            for (long next : rel.getMemidsList()) {
                memid += next;
                memids.add(memid);
                addToWayToTags(memid, tags);
            }
        }
    }

    @Override
    protected void parseDense(Osmformat.DenseNodes nodes) {
        long id = 0;
        long lat = 0, lon = 0;
        Iterator<Integer> keyValuesList = nodes.getKeysValsList().iterator();
        for (int i = 0; i < nodes.getIdCount(); i++) {
            id += nodes.getId(i);
            lat += nodes.getLat(i);
            lon += nodes.getLon(i);
            List<Tag> tags = new LinkedList<>();
            while (keyValuesList.hasNext()) {
                int k = keyValuesList.next();
                if (k == 0)
                    break;
                int v = keyValuesList.next();
                Tag tag = new Tag(getStringById(k), getStringById(v));
                tags.add(tag);
            }
            LatLong latLong = new LatLong(convertLongToCoord(lat), convertLongToCoord(lon));
            if (tags.size() > 0) {
                PointOfInterest poi = new PointOfInterest(tile.zoomLevel, tags, latLong);
                mapReadResult.pointOfInterests.add(poi);
            }
            nodeToLatLong.put(id, latLong);
        }
    }

    private List<Tag> getTagList(List<Integer> keys, List<Integer> vals) {
        List<Tag> tags = new ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            Tag tag = new Tag(getStringById(keys.get(i)), getStringById(vals.get(i)));
            tags.add(tag);
        }
        return tags;
    }

    @Override
    protected void parseNodes(List<Osmformat.Node> nodes) {
        for (Osmformat.Node node : nodes) {
            LatLong latLong = new LatLong(
                    convertLongToCoord(node.getLat()),
                    convertLongToCoord(node.getLon()));
            List<Tag> tags = getTagList(node.getKeysList(), node.getValsList());
            PointOfInterest poi = new PointOfInterest(
                    tile.zoomLevel, tags, latLong);
            mapReadResult.pointOfInterests.add(poi);
        }

    }

    @Override
    protected void parseWays(List<Osmformat.Way> ways) {
        for (Osmformat.Way way : ways) {
            long wayId = way.getId();
            List<Tag> tags = getTagList(way.getKeysList(), way.getValsList());
            long nodeId = 0;
            List<Long> nodeIds = new ArrayList<>(way.getRefsCount());
            for (long deltaNodeId : way.getRefsList()) {
                nodeId += deltaNodeId;
                nodeIds.add(nodeId);
            }
            // Way mWay = new Way(tile.zoomLevel, tags, null, null);
            wayToNodeIds.put(wayId, nodeIds);
            addToWayToTags(wayId, tags);
            // Way w = new Way();
            // mapReadResult.ways.add(w);
        }
    }

    private void addToWayToTags(long wayId, List<Tag> tags) {
        List<Tag> wayTags = wayToTags.get(wayId);
        if (wayTags == null) {
            wayTags = tags;
        } else {
            wayTags.addAll(tags);
        }
        wayToTags.put(wayId, wayTags);
    }

    @Override
    protected void parse(Osmformat.HeaderBlock header) {

    }

    @Override
    public void complete() {
        for (Long wayId : wayToNodeIds.keySet()) {
            List<Tag> tags = wayToTags.get(wayId);
            List<Long> nodeIds = wayToNodeIds.get(wayId);
            List<LatLong> latLongs = new ArrayList<>(nodeIds.size());
            for (long nodeId : nodeIds) {
                latLongs.add(nodeToLatLong.get(nodeId));
            }
            LatLong[][] latLongs1 = new LatLong[1][latLongs.size()];
            latLongs.toArray(latLongs1[0]);
            // TODO: understand if "labelPosition" is really necessary:
            Way way = new Way(tile.zoomLevel, tags, latLongs1, null);
            mapReadResult.ways.add(way);
        }
    }

}
