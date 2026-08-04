"""Elevation of any coordinate, from PeakNav's compressed ASTER dataset.

The dataset (`Upabjojr/elevation-data-ASTER-compressed-retiled
<https://huggingface.co/datasets/Upabjojr/elevation-data-ASTER-compressed-retiled>`_)
is ASTER GDEM re-tiled to slippy-map tiles at zoom 8 and compressed into an unusual
pair of images per tile: a JPEG carrying the fine detail and a PNG carrying the coarse
bits. JPEG is lossy but small and smooth terrain compresses superbly; the PNG rides
along to pin down which kilometre band each pixel is in, so the JPEG's noise cannot
move a mountain. The encoding, per pixel of elevation ``e`` (metres)::

    png = 128 + floor(e / 1024)          # which 1024 m band
    jpg = (e % 1024) / 4                 # position inside the band, 4 m steps
    jpg = 255 - jpg   where png is odd   # alternate bands flip, so band edges
                                         # stay JPEG-friendly gradients

Decoding reverses it exactly - see :func:`decode_elevation` for the arithmetic and
its doctest.

Tiles download on first use and are cached under ``~/.cache/peaknav/elev_tiles``
(override with ``PEAKNAV_ELEV_CACHE``). A tile the dataset does not have - open
ocean, mostly - reads as elevation 0.

One honesty note about the data itself: ASTER is a stereo-photogrammetric DEM, and
like all of its kind it rounds off sharp summits. Broad ones read true - the
Breithorn's snow dome comes back 4160 m against a surveyed 4164 - but a rock spire
clips by hundreds of metres: the Matterhorn reads about 4100. That is the dataset
speaking, not a bug in this module; take spire summits as lower bounds.

This module deliberately needs only Pillow: single lookups sample four pixels, so
there is nothing for numpy to speed up, and the download is one HTTPS GET.
"""

import math
import os
import urllib.error
import urllib.request

from PIL import Image

__all__ = ["elevation_at", "tile_xy", "decode_elevation", "DATASET_URL"]

#: Where the tiles live; {x}, {y} are zero-padded tile numbers, {ext} jpg or png.
DATASET_URL = ("https://huggingface.co/datasets/"
               "Upabjojr/elevation-data-ASTER-compressed-retiled/resolve/main/"
               "elev_tiles/zoom_08/x_{x:05d}/y_{y:05d}/"
               "elev.z08.x{x:05d}.y{y:05d}.f000.{ext}")

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
    >>> decode_elevation(161, 129)       # 1024 + (255-161)*4: the Matterhorn's band
    1400
    >>> decode_elevation(100, 127)       # band -1 is odd, so it is flipped too:
    -404
    """
    if png_value % 2 == 1:
        jpg_value = 255 - jpg_value
    return (jpg_value + (png_value - 128) * 256) * 4


def _cache_dir():
    return os.environ.get("PEAKNAV_ELEV_CACHE",
                          os.path.join(os.path.expanduser("~"), ".cache",
                                       "peaknav", "elev_tiles"))


def _tile_paths(x, y, dataset_path=None, timeout_s=120):
    """Local JPEG and PNG paths for a tile, downloading into the cache if needed.

    Returns None when the dataset has no such tile (open ocean), which callers
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
    os.makedirs(cache, exist_ok=True)
    out = []
    for ext in ("jpg", "png"):
        local = os.path.join(cache, "elev.z08.x%05d.y%05d.f000.%s" % (x, y, ext))
        if not os.path.exists(local):
            url = DATASET_URL.format(x=x, y=y, ext=ext)
            try:
                # Streamed to a private name and renamed into place, so an
                # interrupted download can never be mistaken for a tile.
                partial = local + ".part-%d" % os.getpid()
                with urllib.request.urlopen(url, timeout=timeout_s) as resp, \
                        open(partial, "wb") as f:
                    while True:
                        chunk = resp.read(1 << 16)
                        if not chunk:
                            break
                        f.write(chunk)
                os.replace(partial, local)
            except urllib.error.HTTPError as e:
                if e.code == 404:
                    return None
                raise
        out.append(local)
    return tuple(out)


def elevation_at(lat, lon, *, sample="max", dataset_path=None, timeout_s=120):
    """The elevation of a coordinate, in metres.

    Downloads the covering tile on first use (a few hundred kB) and caches it; later
    lookups in the same tile are local. Coordinates the dataset has no tile for -
    open ocean - return 0.

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
