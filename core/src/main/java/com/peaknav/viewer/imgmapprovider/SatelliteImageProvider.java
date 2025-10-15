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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SatelliteImageProvider {

    public static final String OPENSTREETMAP_CONTRIBUTORS = "OpenStreetMap contributors";

    public enum SatelliteProviderOptions {
        USGS_SATELLITE("https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryTopo/MapServer/tile/{z}/{y}/{x}", "USGS Satellite", "U.S. Geological Survey", "jpg", (byte) 8),
        LANDSAT("https://gitc.earthdata.nasa.gov/wmts/epsg3857/best/Landsat_WELD_CorrectedReflectance_TrueColor_Global_Annual/default/default//GoogleMapsCompatible_Level12/{z}/{y}/{x}.jpeg", s("Satellite"), "USGS/Nasa", "jpeg", (byte) 15);

        // These ones probably need a license in order to be used, DO NOT UNCOMMENT:

        // SENTINEL2("http://s2maps-tiles.eu/wmts/1.0.0/s2cloudless-2021_3857/default/g/{z}/{y}/{x}.jpg", "Sentinel-2", "Copernicus Sentinel Data", "jpg"),

        // OPENSTREETMAP("https://tile.openstreetmap.org/{z}/{x}/{y}.png", "OpenStreetMap", OPENSTREETMAP_CONTRIBUTORS, "png"),
        // CYCLOSM("https://a.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png", "Cyclosm", OPENSTREETMAP_CONTRIBUTORS + ". Tiles style by CyclOSM"),
        // OPEN_TOPO_MAP("https://a.tile.opentopomap.org/{z}/{x}/{y}.png", "OpenTopoMap", OPENSTREETMAP_CONTRIBUTORS + " OpenTopoMap (CC-BY-SA)"),

        // CYCLOSM2("https://a.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png", "Cyclosm", "png"),
        // GOOGLE_EARTH("https://mt1.google.com/vt/lyrs=s&x={x}&y={y}&z={z}", "Google Earth", "jpg"),
        // MICROSOFT_EARTH("http://a0.ortho.tiles.virtualearth.net/tiles/a{u}.jpg?g=45", "Microsoft Earth", "jpg");

        private final SatelliteImageProvider satelliteImageProvider;
        private final String providerName;
        private final String copyrightNotice;
        public final byte maxZoom;

        SatelliteProviderOptions(
                String urlTemplate, String providerName,
                String copyrightNotice, String imageExtension, byte maxZoom) {
            this.providerName = providerName;
            this.copyrightNotice = copyrightNotice;
            this.satelliteImageProvider = new SatelliteImageProvider(
                    urlTemplate, providerName, copyrightNotice, imageExtension, this);
            this.maxZoom = maxZoom;
        }

        SatelliteProviderOptions(
                String urlTemplate, String providerName,
                String copyrightNotice, String imageExtension) {
            this(urlTemplate, providerName, copyrightNotice, imageExtension, (byte)15);
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
            return providerName;
        }

        public String getCopyrightNotice() {
            List<String> cn = new LinkedList<>();
            if (P.isLayerVisibleUnderlayLayer())
                cn.add(copyrightNotice);
            if (!copyrightNotice.contains(OPENSTREETMAP_CONTRIBUTORS)) {
                if (P.isPeakVisible() || P.isVisiblePlaceNames() ||
                        P.isVisibleAlpineHuts() || P.isViewerLayerVisibleBaseRoads()) {
                    cn.add(OPENSTREETMAP_CONTRIBUTORS);
                }
            }
            if (cn.size() == 0)
                return "";
            return "©" + String.join(". ", cn);
        }
    }

    private final SatelliteImageCacheStorage satelliteImageCacheStorage;
    private final String urlTemplate;
    private final String providerName;
    private final String copyrightNotice;
    private SatelliteProviderOptions satelliteProviderOptions;

    public SatelliteImageProvider(
            String urlTemplate, String providerName,
            String copyrightNotice,
            String imageExtension,
            SatelliteProviderOptions satelliteProviderOptions) {
        this.urlTemplate = urlTemplate;
        this.providerName = providerName;
        this.copyrightNotice = copyrightNotice;
        this.satelliteProviderOptions = satelliteProviderOptions;
        if (imageExtension == null)
            imageExtension = getImageExtensionFromTemplate(urlTemplate);
        this.satelliteImageCacheStorage = new SatelliteImageCacheStorage(urlTemplate, imageExtension);
    }

    /*public SatelliteImageProvider(String urlTemplate) {
        this(urlTemplate, null);
    }*/

    private static String getImageExtensionFromTemplate(String urlTemplate) {
        String imageExtension;
        String urlTemplateLower = urlTemplate.toLowerCase();
        if (urlTemplateLower.contains(".jpg") || urlTemplateLower.contains(".jpeg"))
            imageExtension = "jpg";
        else if (urlTemplateLower.contains(".png"))
            imageExtension = "png";
        else
            throw new RuntimeException("could not detect image format");
        return imageExtension;
    }

    public String getURL(final int z, final int x, final int y) {
        Pattern pattern = Pattern.compile("\\{([xyzu])\\}");
        Matcher matcher = pattern.matcher(urlTemplate);

        StringBuilder stringBuilder = new StringBuilder();
        int lastIdx = 0;
        while (matcher.find()) {
            String g = matcher.group(1);
            stringBuilder.append(urlTemplate, lastIdx, matcher.start());
            switch (g) {
                case "x":
                    stringBuilder.append(x);
                    break;
                case "y":
                    stringBuilder.append(y);
                    break;
                case "z":
                    stringBuilder.append(z);
                    break;
                case "u":
                    stringBuilder.append(getQuadKey(z, x, y));
                    break;
            }
            lastIdx = matcher.end();
        }
        stringBuilder.append(urlTemplate.substring(lastIdx));
        return stringBuilder.toString();
    }

    private final char[] num2char = {'0', '1', '2', '3'};

    private String getQuadKey(int z, int x, int y) {
        char[] quadkey = new char[z];
        int ix = x;
        int iy = y;
        for (int i = z - 1; i >= 0; i--) {
            int n = ((iy % 2) << 1) | (ix % 2);
            quadkey[i] = num2char[n];
            ix >>= 1;
            iy >>= 1;
        }
        return new String(quadkey);
    }

    public File getImageFileHandle(int z, int x, int y) {
        return satelliteImageCacheStorage.getImageFileHandle(z, x, y);
    }

    public void downloadTileImageIfNotExists(Tile tile) {
        if (tile.zoomLevel > satelliteProviderOptions.maxZoom) {
            int factor = (1 << (tile.zoomLevel - satelliteProviderOptions.maxZoom));
            Tile zoutTile = new Tile(tile.tileX/factor, tile.tileY/factor, satelliteProviderOptions.maxZoom, MF_ZOOM);
            File zoImagePath = downloadTileToFileIfNotExists(zoutTile);
            File imagePath = getImageFileHandle(tile.zoomLevel, tile.tileX, tile.tileY);
            Pixmap zoPixmap = PeakNavUtils.readImageCached(zoImagePath);
            int w = zoPixmap.getWidth()/factor;
            int h = zoPixmap.getHeight()/factor;
            Pixmap pixmap = new Pixmap(w, h, zoPixmap.getFormat());
            int srcx = (tile.tileX - factor*zoutTile.tileX)*w;
            int srcy = (tile.tileY - factor*zoutTile.tileY)*h;
            pixmap.drawPixmap(zoPixmap, srcx, srcy, w, h, 0, 0, w, h);
            PixmapIO.writePNG(new FileHandle(imagePath), pixmap);
            pixmap.dispose();
        } else {
            downloadTileToFileIfNotExists(tile);
        }
    }

    private File downloadTileToFileIfNotExists(Tile tile) {
        File imagePath = getImageFileHandle(tile.zoomLevel, tile.tileX, tile.tileY);
        String tilePath = imagePath.getAbsolutePath();
        File file = new File(tilePath);
        if (file.exists())
            return imagePath;
        try {
            URL url = new URL(getURL(tile.zoomLevel, tile.tileX, tile.tileY));

            String user_agent = "PeakNav-3D-UA";
            URLConnection con = url.openConnection();
            con.setRequestProperty("User-Agent", user_agent);

            InputStream inputStream = con.getInputStream();
            PeakNavUtils.copyFile(inputStream, file);
        } catch (IOException malformedURLException) {
            malformedURLException.printStackTrace();
        }
        return imagePath;
    }

}
