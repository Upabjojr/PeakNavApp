"""Elevation of any coordinate, from PeakNav's compressed ASTER dataset.

The dataset (`PeakNav/global-elevation-aster-slippy-tiles-tar-gz
<https://huggingface.co/datasets/PeakNav/global-elevation-aster-slippy-tiles-tar-gz>`_
- the same one the app downloads from) is ASTER GDEM re-tiled to slippy-map tiles at
zoom 8 and compressed into an unusual pair of images per tile: a JPEG carrying the
fine detail and a PNG carrying the coarse bits. JPEG is lossy but small and smooth
terrain compresses superbly; the PNG rides along to pin down which kilometre band
each pixel is in, so the JPEG's noise cannot move a mountain. The encoding, per
pixel of elevation ``e`` (metres)::

    png = 128 + floor(e / 1024)          # which 1024 m band
    jpg = (e % 1024) / 4                 # position inside the band, 4 m steps
    jpg = 255 - jpg   where png is odd   # alternate bands flip, so band edges
                                         # stay JPEG-friendly gradients

Decoding reverses it exactly - see :func:`decode_elevation` for the arithmetic and
its doctest.

The dataset ships one ``.tar.gz`` per zoom-6 tile, holding the sixteen zoom-8
pairs beneath it. The first lookup in an area therefore downloads a ~30 MB archive;
it is unpacked into the cache (``~/.cache/peaknav/elev_tiles.v2``, override with
``PEAKNAV_ELEV_CACHE``), so every later lookup within that 4x4 block of tiles is
local. An area the dataset does not have - open ocean, mostly - reads as
elevation 0. (Earlier releases fetched single tiles from the superseded
``Upabjojr/elevation-data-ASTER-compressed-retiled`` dataset; the cache directory
changed with the switch, so stale uncorrected tiles are never mixed in.)

One honesty note about the data itself: ASTER is a stereo-photogrammetric DEM, and
like all of its kind it rounds off sharp summits - broad ones read true (the
Breithorn's snow dome comes back 4160 m against a surveyed 4164) while raw ASTER
clipped a rock spire by hundreds of metres. The dataset now corrects summits
against surveyed elevations: the Matterhorn, which the raw DEM clipped to about
4040 m, tops out at 4484 - the surveyed 4478 to within the encoding's 4 m step.
The rebuilt top can still sit a pixel or two (~30 m each) from the coordinate you
have for a peak - the classic Matterhorn coordinate reads 4312 - so on a spire,
the last few tens of metres depend on hitting the summit pixel, not on the data
missing the summit.

This module deliberately needs only Pillow: single lookups sample four pixels, so
there is nothing for numpy to speed up, and the download is one HTTPS GET.
"""

import math
import os
import re
import tarfile
import urllib.error
import urllib.request

from PIL import Image

__all__ = ["elevation_at", "tile_xy", "decode_elevation", "DATASET_URL"]

#: Where the tiles live: one tar.gz per ZOOM-6 tile ({x}, {y} are zoom-6 tile
#: numbers), each holding the sixteen zoom-8 JPEG+PNG pairs beneath it.
DATASET_URL = ("https://huggingface.co/datasets/"
               "PeakNav/global-elevation-aster-slippy-tiles-tar-gz/resolve/main/"
               "elev_tiles/zoom_06/x_{x:05d}/y_{y:05d}.tar.gz")

#: The dataset's tiling: slippy-map zoom 8, 256 tiles per axis.
ZOOM = 8


def tile_xy(lat, lon, zoom=ZOOM):
    """The slippy-map tile containing a coordinate.

    Standard Web-Mercator tile numbering: x grows eastward from the antimeridian,
    y grows southward from the north.

    >>> tile_xy(45.9763, 7.6586)         # the Matterhorn's tile
    (133, 91)
    >>> tile_xy(0.0, 0.0)                # the Gulf of Guinea
    (128, 128)
    >>> tile_xy(-43.5950, 170.1418)      # southern hemisphere, Mount Cook
    (248, 162)
    """
    n = 1 << zoom
    x = int((lon + 180.0) / 360.0 * n)
    lat_rad = math.radians(lat)
    y = int((1.0 - math.asinh(math.tan(lat_rad)) / math.pi) / 2.0 * n)
    return min(x, n - 1), min(y, n - 1)


def _tile_bounds(x, y, zoom=ZOOM):
    """The tile's (min_lat, min_lon, max_lat, max_lon) in degrees."""
    n = 1 << zoom

    def lat_of(yy):
        return math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * yy / n))))

    return lat_of(y + 1), x / n * 360.0 - 180.0, lat_of(y), (x + 1) / n * 360.0 - 180.0


def decode_elevation(jpg_value, png_value):
    """One pixel's elevation in metres, from its JPEG and PNG samples.

    The inverse of the encoding described in the module docstring: the PNG names a
    1024 m band (128 = the band starting at sea level), the JPEG the position inside
    it in 4 m steps, flipped in odd bands.

    >>> decode_elevation(0, 128)         # sea level, band 0
    0
    >>> decode_elevation(100, 128)       # 100 steps of 4 m up
    400
    >>> decode_elevation(255, 129)       # band 1 is flipped: 255 means its BOTTOM
    1024
    >>> decode_elevation(0, 129)         # and 0 its top
    2044
    >>> decode_elevation(161, 129)       # 1024 + (255-161)*4, inside the flipped band
    1400
    >>> decode_elevation(100, 127)       # band -1 is odd, so it is flipped too:
    -404
    """
    if png_value % 2 == 1:
        jpg_value = 255 - jpg_value
    return (jpg_value + (png_value - 128) * 256) * 4


def _cache_dir():
    # ".v2" because the switch to the corrected PeakNav dataset must not read
    # tiles a pre-switch install cached from the superseded one.
    return os.environ.get("PEAKNAV_ELEV_CACHE",
                          os.path.join(os.path.expanduser("~"), ".cache",
                                       "peaknav", "elev_tiles.v2"))


def _fetch_and_unpack(x6, y6, timeout_s):
    """Download one zoom-6 archive and unpack its full-detail pairs into the cache.

    Returns False when the dataset has no such archive (open ocean).
    """
    url = DATASET_URL.format(x=x6, y=y6)
    os.makedirs(_cache_dir(), exist_ok=True)
    # Streamed to a private name, and every extracted tile is renamed into place,
    # so an interrupted download can never be mistaken for data.
    archive = os.path.join(_cache_dir(),
                           "x%05d.y%05d.tar.gz.part-%d" % (x6, y6, os.getpid()))
    try:
        with urllib.request.urlopen(url, timeout=timeout_s) as resp, \
                open(archive, "wb") as f:
            while True:
                chunk = resp.read(1 << 16)
                if not chunk:
                    break
                f.write(chunk)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return False
        raise
    try:
        with tarfile.open(archive, "r:gz") as tar:
            for member in tar:
                name = os.path.basename(member.name)
                m = re.match(r"elev\.z08\.x(\d{5})\.y\d{5}\.f000\.(jpg|png)$", name)
                if m is None or not member.isfile():
                    continue  # f001+ are coarser levels the app uses; not wanted
                subdir = os.path.join(_cache_dir(), "x%05d" % int(m.group(1)))
                os.makedirs(subdir, exist_ok=True)
                out = os.path.join(subdir, name)
                partial = out + ".part-%d" % os.getpid()
                src = tar.extractfile(member)
                with open(partial, "wb") as f:
                    while True:
                        chunk = src.read(1 << 16)
                        if not chunk:
                            break
                        f.write(chunk)
                os.replace(partial, out)
    finally:
        os.remove(archive)
    return True


def _tile_paths(x, y, dataset_path=None, timeout_s=120):
    """Local JPEG and PNG paths for a zoom-8 tile, downloading into the cache if needed.

    The dataset packs each 4x4 block of zoom-8 tiles into one zoom-6 archive, so a
    cache miss downloads that archive (~30 MB) and unpacks the whole block - the
    neighbouring tiles are then already local when a profile or sweep reaches them.

    Returns None when the dataset has no data here (open ocean), which callers
    report as elevation 0 rather than an error - "no land here" is an answer.
    """
    if dataset_path is not None:
        # The same layout the dataset and the PeakNav app both use, so a local HF
        # snapshot AND the app's own ~/.peaknav directory each work as dataset_path
        # (the app keeps f-levels >= 2; only f000, full detail, is looked for here).
        base = os.path.join(dataset_path, "elev_tiles", "zoom_08",
                            "x_%05d" % x, "y_%05d" % y,
                            "elev.z08.x%05d.y%05d.f000" % (x, y))
        jpg, png = base + ".jpg", base + ".png"
        return (jpg, png) if os.path.exists(jpg) and os.path.exists(png) else None

    cache = os.path.join(_cache_dir(), "x%05d" % x)
    paths = tuple(os.path.join(cache, "elev.z08.x%05d.y%05d.f000.%s" % (x, y, ext))
                  for ext in ("jpg", "png"))
    if all(os.path.exists(p) for p in paths):
        return paths
    # The marker says this archive was already fetched and unpacked, so a tile
    # still missing is one the archive genuinely lacks (a coastal block) - asked
    # again, it must answer 0 again rather than re-download 30 MB to re-learn it.
    x6, y6 = x >> 2, y >> 2
    marker = os.path.join(_cache_dir(), "unpacked.x%05d.y%05d" % (x6, y6))
    if not os.path.exists(marker):
        if not _fetch_and_unpack(x6, y6, timeout_s):
            return None
        with open(marker, "w"):
            pass
    return paths if all(os.path.exists(p) for p in paths) else None


def elevation_at(lat, lon, *, sample="max", dataset_path=None, timeout_s=120):
    """The elevation of a coordinate, in metres.

    Downloads the covering archive on first use (~30 MB, covering a 4x4 block of
    tiles) and caches it unpacked; later lookups anywhere in that block are local.
    Coordinates the dataset has no data for - open ocean - return 0.

    :param sample: ``"max"`` (default) returns the highest of the four pixels around
        the point, which is what summit queries want - ASTER pixels are ~30 m and a
        peak is rarely dead-centre on one. ``"bilinear"`` interpolates instead,
        better for slopes and profiles.
    :param dataset_path: a locally downloaded copy of the dataset (the directory
        holding ``elev_tiles/``), for offline use - e.g. via ``huggingface_hub``'s
        ``snapshot_download``.

    >>> elevation_at(45.9417, 7.7480)                    # doctest: +SKIP
    4160
    >>> elevation_at(0.0, -30.0)                         # doctest: +SKIP
    0

    (Examples are skipped in offline test runs; ``tests/test_terrain.py`` runs the
    same lookups for real when the network - or a local dataset - is available.)
    """
    if sample not in ("max", "bilinear"):
        raise ValueError("sample wants 'max' or 'bilinear', got %r" % (sample,))
    x, y = tile_xy(lat, lon)
    paths = _tile_paths(x, y, dataset_path=dataset_path, timeout_s=timeout_s)
    if paths is None:
        return 0
    jpg = Image.open(paths[0]).convert("L")
    png = Image.open(paths[1]).convert("L")
    size = jpg.size[0]

    min_lat, min_lon, max_lat, max_lon = _tile_bounds(x, y)
    # Fractional pixel position; the tile's row 0 is its NORTH edge. Longitude is
    # linear in pixels; latitude is NOT - the tile is a Mercator tile, so rows are
    # linear in PROJECTED y. Interpolating latitude linearly here put points up to
    # ten pixels north or south of themselves mid-tile (~270 m at zoom 8), which on
    # a spike like the Matterhorn read a flank 300 m below the summit.
    fx = (lon - min_lon) / (max_lon - min_lon) * size

    def mercator_y(lat_deg):
        return math.asinh(math.tan(math.radians(lat_deg)))

    merc_top, merc_bottom = mercator_y(max_lat), mercator_y(min_lat)
    fy = (merc_top - mercator_y(lat)) / (merc_top - merc_bottom) * size
    x0 = min(int(fx), size - 1)
    y0 = min(int(fy), size - 1)
    x1 = min(x0 + 1, size - 1)
    y1 = min(y0 + 1, size - 1)

    def sample_px(px, py):
        return decode_elevation(jpg.getpixel((px, py)), png.getpixel((px, py)))

    corners = [sample_px(x0, y0), sample_px(x1, y0),
               sample_px(x0, y1), sample_px(x1, y1)]
    if sample == "max":
        return max(corners)
    wx, wy = fx - x0, fy - y0
    top = corners[0] * (1 - wx) + corners[1] * wx
    bottom = corners[2] * (1 - wx) + corners[3] * wx
    return top * (1 - wy) + bottom * wy
