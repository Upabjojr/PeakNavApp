package com.peaknav.pbf;

/**
 * A category of downloadable map data. The constant's name is used verbatim as the folder under
 * {@code map_folder}, as the {@code category} inside each archive's file name, and as the
 * {@code layer_type} recorded in the download queue, so these names are part of both the on-disk
 * and the server-side layout (see {@link com.peaknav.utils.PathUtils}).
 */
public enum PbfLayer {
    PBF_HIGHWAYS((byte) 8, ".tar", ".osm.pbf"),
    PBF_POI((byte) 6, ".tar", ".osm.pbf"),
    /**
     * Named areas — islands, lakes, mountain ranges, cities — whose labels the viewer draws.
     *
     * <p>Fetched exactly like the PBF extracts: one archive per tile under
     * {@code map_folder/AREAS/}, queued and downloaded by the same code. Two things differ, both
     * set by the pipeline that builds them: the archive is gzipped ({@code .tar.gz}, as the
     * elevation blocks are), and it is cut at a coarser zoom than the tiles inside it — one
     * archive carries the 16×16 block of zoom-9 JSON tiles inside its zoom-5 tile.
     *
     * <p>That is one level coarser than {@link #PBF_POI}, because area labels are tiny. Measured
     * over Europe, cutting at zoom 6 gave archives with a median of 7.8 kB — less than the HTTP
     * round trip that fetches them — against 20.3 kB at zoom 5, for the same total bytes in a
     * third of the requests. Changing it means changing the pipeline in step: see
     * {@code AREAS_ZOOM_DIFF} in {@code areas_002_zip_tiles.py}, or the app asks the server for
     * paths that do not exist.
     */
    AREAS((byte) 5, ".tar.gz", ".json");

    public static final byte ZOOM_LEVEL_HIGHWAYS = 10;
    public static final byte ZOOM_LEVEL_POI = 9;
    /** Zoom of the individual area tiles inside an archive (what {@code AreaRegistry} reads). */
    public static final byte ZOOM_LEVEL_AREAS = 9;

    // Zoom each layer's archives are cut at, i.e. the granularity the download queue works in.
    // Kept in step with the values passed to the constants above (an enum constant cannot
    // reference a static field of its own class).
    public static final byte ZOOM_LEVEL_AREAS_ARCHIVE = 5;

    private final byte archiveZoom;
    private final String archiveExtension;
    private final String fileExtension;

    PbfLayer(byte archiveZoom, String archiveExtension, String fileExtension) {
        this.archiveZoom = archiveZoom;
        this.archiveExtension = archiveExtension;
        this.fileExtension = fileExtension;
    }

    /** Zoom level this layer's downloadable archives are cut at. */
    public byte getArchiveZoom() {
        return archiveZoom;
    }

    /** Extension of the downloadable archive: {@code .tar} or {@code .tar.gz}. */
    public String getArchiveExtension() {
        return archiveExtension;
    }

    /**
     * Extension of one unpacked tile of this layer. Kept here so the readers and the path builder
     * cannot disagree: the layout is shared, only the extension differs, and spelling it at each
     * call site is how a caller ends up asking for an area tile named {@code .osm.pbf}.
     */
    public String getFileExtension() {
        return fileExtension;
    }
}
