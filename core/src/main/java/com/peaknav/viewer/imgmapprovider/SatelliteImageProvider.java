package com.peaknav.viewer.imgmapprovider;

import static com.peaknav.utils.PeakNavUtils.s;
import static com.peaknav.utils.PreferencesManager.P;
import static com.peaknav.viewer.tiles.MapTile.MF_ZOOM;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.peaknav.utils.PeakNavUtils;

import org.mapsforge.core.model.Tile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.LinkedList;
import java.util.List;

public class SatelliteImageProvider {

    public static final String OPENSTREETMAP_CONTRIBUTORS = "OpenStreetMap contributors";

    /** Used for custom providers, whose real zoom ceiling we cannot know up-front. */
    public static final byte DEFAULT_MAX_ZOOM = 15;

    public enum SatelliteProviderOptions {
        USGS_SATELLITE("https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryTopo/MapServer/tile/{z}/{y}/{x}", "USGS Satellite", "U.S. Geological Survey", "jpg", (byte) 8),
        // The tile matrix set is GoogleMapsCompatible_Level12, so the service only serves up to
        // zoom 12 and answers 400 above it. Anything higher has to be scaled up from a zoom 12
        // tile, which is what downloadTileImageIfNotExists does for zoom levels beyond maxZoom.
        LANDSAT("https://gitc.earthdata.nasa.gov/wmts/epsg3857/best/Landsat_WELD_CorrectedReflectance_TrueColor_Global_Annual/default/default//GoogleMapsCompatible_Level12/{z}/{y}/{x}.jpeg", s("Satellite"), "USGS/Nasa", "jpeg", (byte) 12);

        // These ones probably need a license in order to be used, DO NOT UNCOMMENT:

        // SENTINEL2("http://s2maps-tiles.eu/wmts/1.0.0/s2cloudless-2021_3857/default/g/{z}/{y}/{x}.jpg", "Sentinel-2", "Copernicus Sentinel Data", "jpg"),

        // OPENSTREETMAP("https://tile.openstreetmap.org/{z}/{x}/{y}.png", "OpenStreetMap", OPENSTREETMAP_CONTRIBUTORS, "png"),
        // CYCLOSM("https://a.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png", "Cyclosm", OPENSTREETMAP_CONTRIBUTORS + ". Tiles style by CyclOSM"),
        // OPEN_TOPO_MAP("https://a.tile.opentopomap.org/{z}/{x}/{y}.png", "OpenTopoMap", OPENSTREETMAP_CONTRIBUTORS + " OpenTopoMap (CC-BY-SA)"),

        // CYCLOSM2("https://a.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png", "Cyclosm", "png"),
        // GOOGLE_EARTH("https://mt1.google.com/vt/lyrs=s&x={x}&y={y}&z={z}", "Google Earth", "jpg"),
        // MICROSOFT_EARTH("http://a0.ortho.tiles.virtualearth.net/tiles/a{u}.jpg?g=45", "Microsoft Earth", "jpg");

        private final SatelliteImageProvider satelliteImageProvider;

        SatelliteProviderOptions(
                String urlTemplate, String providerName,
                String copyrightNotice, String imageExtension, byte maxZoom) {
            this.satelliteImageProvider = new SatelliteImageProvider(
                    name(), urlTemplate, providerName, copyrightNotice, imageExtension, maxZoom, false);
        }

        SatelliteProviderOptions(
                String urlTemplate, String providerName,
                String copyrightNotice, String imageExtension) {
            this(urlTemplate, providerName, copyrightNotice, imageExtension, DEFAULT_MAX_ZOOM);
        }

        SatelliteProviderOptions(
                String urlTemplate, String providerName,
                String copyrightNotice) {
            this(urlTemplate, providerName, copyrightNotice, null);
        }

        public SatelliteImageProvider getSatelliteImageProvider() {
            return satelliteImageProvider;
        }

        public String getProviderName() {
            return satelliteImageProvider.getProviderName();
        }

        public String getCopyrightNotice() {
            return satelliteImageProvider.getCopyrightNotice();
        }
    }

    private final SatelliteImageCacheStorage satelliteImageCacheStorage;
    private final SatelliteUrlTemplate urlTemplate;
    private final String id;
    private final String providerName;
    private final String copyrightNotice;
    private final byte maxZoom;
    private final boolean custom;

    public SatelliteImageProvider(
            String id,
            String urlTemplate, String providerName,
            String copyrightNotice,
            String imageExtension,
            byte maxZoom,
            boolean custom) {
        this.id = id;
        this.urlTemplate = new SatelliteUrlTemplate(urlTemplate);
        this.providerName = providerName;
        this.copyrightNotice = copyrightNotice == null ? "" : copyrightNotice;
        this.maxZoom = maxZoom;
        this.custom = custom;
        if (imageExtension == null) {
            imageExtension = SatelliteUrlTemplate.guessImageExtension(urlTemplate);
        }
        this.satelliteImageCacheStorage = new SatelliteImageCacheStorage(urlTemplate, imageExtension);
    }

    /** Builds a user supplied provider. The url template must already have been validated. */
    public static SatelliteImageProvider custom(
            String id, String urlTemplate, String providerName, String copyrightNotice) {
        return new SatelliteImageProvider(
                id, urlTemplate, providerName, copyrightNotice, null, DEFAULT_MAX_ZOOM, true);
    }

    public String getId() {
        return id;
    }

    public boolean isCustom() {
        return custom;
    }

    public byte getMaxZoom() {
        return maxZoom;
    }

    public String getProviderName() {
        return providerName;
    }

    public String getUrlTemplate() {
        return urlTemplate.getTemplate();
    }

    /** The attribution as supplied, without the OpenStreetMap credit composed onto it. */
    public String getRawCopyrightNotice() {
        return copyrightNotice;
    }

    public String getCopyrightNotice() {
        List<String> cn = new LinkedList<>();
        if (P.isLayerVisibleUnderlayLayer() && !copyrightNotice.isEmpty())
            cn.add(copyrightNotice);
        if (!copyrightNotice.contains(OPENSTREETMAP_CONTRIBUTORS)) {
            if (P.isPeakVisible() || P.isVisiblePlaceNames() ||
                    P.isVisibleAlpineHuts() || P.isViewerLayerVisibleBaseRoads()) {
                cn.add(OPENSTREETMAP_CONTRIBUTORS);
            }
        }
        if (cn.size() == 0)
            return "";
        // Joined by hand rather than with String.join: that is a Java 8 method, and RoboVM's
        // runtime is Android's, which does not have it - the iOS build would compile and then
        // die with NoSuchMethodError the first time a copyright notice was drawn.
        StringBuilder joined = new StringBuilder("©");
        for (int i = 0; i < cn.size(); i++) {
            if (i > 0) {
                joined.append(". ");
            }
            joined.append(cn.get(i));
        }
        return joined.toString();
    }

    public String getURL(final int z, final int x, final int y) {
        return urlTemplate.expand(z, x, y);
    }

    public File getImageFileHandle(int z, int x, int y) {
        return satelliteImageCacheStorage.getImageFileHandle(z, x, y);
    }

    public void downloadTileImageIfNotExists(Tile tile) {
        if (tile.zoomLevel > maxZoom) {
            int factor = (1 << (tile.zoomLevel - maxZoom));
            Tile zoutTile = new Tile(tile.tileX/factor, tile.tileY/factor, maxZoom, MF_ZOOM);
            File zoImagePath = downloadTileToFileIfNotExists(zoutTile);
            if (zoImagePath == null) {
                return; // download failed; the tile pass simply retries later
            }
            File imagePath = getImageFileHandle(tile.zoomLevel, tile.tileX, tile.tileY);
            Pixmap zoPixmap = PeakNavUtils.readImageCached(zoImagePath);
            if (zoPixmap == null) {
                // Unreadable (e.g. an old truncated download): remove it so it is re-downloaded
                // on the next pass instead of failing on every visit to this area forever.
                zoImagePath.delete();
                return;
            }
            try {
                int w = zoPixmap.getWidth()/factor;
                int h = zoPixmap.getHeight()/factor;
                Pixmap pixmap = new Pixmap(w, h, zoPixmap.getFormat());
                // Write via a per-thread temp file + rename, so a concurrent worker producing the
                // same tile can never interleave bytes into one file.
                // UUID, not thread id: thread ids are per-JVM, and several renderer processes
                // share this cache - two of them can hold the same thread id, and a
                // shared temp name splices two writers into one torn file.
                File tmp = new File(imagePath.getPath() + ".part-" + java.util.UUID.randomUUID());
                try {
                    int srcx = (tile.tileX - factor*zoutTile.tileX)*w;
                    int srcy = (tile.tileY - factor*zoutTile.tileY)*h;
                    pixmap.drawPixmap(zoPixmap, srcx, srcy, w, h, 0, 0, w, h);
                    PixmapIO.writePNG(new FileHandle(tmp), pixmap);
                } catch (RuntimeException e) {
                    tmp.delete(); // never leave a partial file behind
                    throw e;
                } finally {
                    pixmap.dispose();
                }
                if (!tmp.renameTo(imagePath)) {
                    tmp.delete(); // another worker won the race; its file is just as good
                }
            } finally {
                PeakNavUtils.decrementReferenceCounter(zoPixmap);
            }
        } else {
            downloadTileToFileIfNotExists(tile);
        }
    }

    /**
     * @return the tile's image file, or null when it is not on disk and could not be downloaded.
     *         The download goes to a per-thread temp file first and is renamed into place only
     *         when complete, so a dropped connection can never leave a truncated file that
     *         {@code file.exists()} would then trust forever.
     */
    private File downloadTileToFileIfNotExists(Tile tile) {
        File file = getImageFileHandle(tile.zoomLevel, tile.tileX, tile.tileY);
        if (file.exists())
            return file;
        File tmp = new File(file.getPath() + ".part-" + java.util.UUID.randomUUID());
        try {
            URL url = new URL(getURL(tile.zoomLevel, tile.tileX, tile.tileY));

            String user_agent = "PeakNav-3D-UA";
            URLConnection con = url.openConnection();
            con.setRequestProperty("User-Agent", user_agent);
            // A URLConnection with the default timeouts blocks forever on a half-open
            // socket, and this runs on a two-thread pool that nothing interrupts: two hung
            // sockets and no satellite tile downloads again until the app restarts - the
            // toggles in the options menu only queue work behind the parked workers. The
            // values match the map-data downloader's, which is why that path never wedged.
            con.setConnectTimeout(20_000);
            con.setReadTimeout(60_000);

            PeakNavUtils.copyFile(con.getInputStream(), tmp); // closes both streams
            if (!tmp.renameTo(file)) {
                tmp.delete(); // concurrent download of the same tile finished first
                if (!file.exists()) {
                    return null;
                }
            }
            return file;
        } catch (IOException e) {
            tmp.delete();
            System.err.println("[Satellite] download failed for "
                    + tile.zoomLevel + "/" + tile.tileX + "/" + tile.tileY + ": " + e);
            return null;
        }
    }

}
