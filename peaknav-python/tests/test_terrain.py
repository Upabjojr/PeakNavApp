"""peaknav.terrain, tested against things it did not compute itself.

Three layers, by what they need: pure arithmetic (always runs), a real tile already
on this machine (runs on a machine that has used PeakNav), and the live dataset
(runs when PEAKNAV_NETWORK_TESTS=1). Each layer skips itself with a reason rather
than passing vacuously.
"""

import glob
import os
import re

import pytest

from peaknav.terrain import decode_elevation, elevation_at, tile_xy


# ------------------------------------------------------------------ pure arithmetic

def encode_elevation(e):
    """The encoder, ported from the dataset's own tooling (peaknav-tools) - an
    independent implementation for the decoder to round-trip against."""
    png = 128 + e // 1024
    jpg = (e % 1024) // 4
    if png % 2 == 1:
        jpg = 255 - jpg
    return jpg, png


def test_decode_inverts_the_datasets_encoder():
    # Every 4 m step from the Dead Sea to past Everest: the decoder must undo the
    # encoder exactly - off-by-one in the band flip or the 128 offset fails here.
    for e in range(-1024, 9000, 4):
        jpg, png = encode_elevation(e)
        assert decode_elevation(jpg, png) == e, "at %d m" % e


def test_tile_math_against_known_places():
    assert tile_xy(45.9763, 7.6586) == (133, 91)      # Matterhorn
    assert tile_xy(35.3606, 138.7274) == (226, 101)   # Fuji
    assert tile_xy(-32.653, -70.011) == (78, 152)     # Aconcagua
    # Antimeridian and poles clamp instead of overflowing.
    assert tile_xy(0, 179.999)[0] == 255
    assert tile_xy(85.05, 0)[1] == 0  # the Mercator limit is the top row


# ------------------------------------------------------------------ a real local tile

#: The app keeps its elevation crops here; any of them exercises the real decode.
_APP_TILES = os.path.expanduser("~/.peaknav/elev_tiles/zoom_08")


def _find_local_pair():
    """An (jpg, png, x, y) quadruple from the app's cache, or None.

    The app's crops carry a detail suffix f>=2 rather than the dataset's f000, but
    the ENCODING is the same - which is exactly what this fixture checks.
    """
    for jpg in sorted(glob.glob(os.path.join(_APP_TILES, "x_*", "y_*", "*.jpg"))):
        png = jpg[:-4] + ".png"
        m = re.search(r"x(\d{5})\.y(\d{5})", jpg)
        if os.path.exists(png) and m:
            return jpg, png, int(m.group(1)), int(m.group(2))
    return None


@pytest.mark.skipif(_find_local_pair() is None,
                    reason="no PeakNav elevation tiles on this machine")
def test_decodes_a_real_tile_to_plausible_terrain():
    from PIL import Image
    jpg_path, png_path, x, y = _find_local_pair()
    jpg = Image.open(jpg_path).convert("L")
    png = Image.open(png_path).convert("L")
    lo, hi = 9000, -1000
    w, h = jpg.size
    for py in range(0, h, max(1, h // 64)):
        for px in range(0, w, max(1, w // 64)):
            e = decode_elevation(jpg.getpixel((px, py)), png.getpixel((px, py)))
            lo, hi = min(lo, e), max(hi, e)
    # Real terrain, not noise: the range must be Earth-shaped. A band-flip bug
    # produces values near ±32000; a swapped pair produces a mountain range of
    # rubble around zero.
    assert -500 <= lo <= hi <= 8900, "decoded range %d..%d m" % (lo, hi)
    assert hi - lo > 100, "a whole tile is never flat to within 100 m"


# ------------------------------------------------------------------ the live dataset

network = pytest.mark.skipif(os.environ.get("PEAKNAV_NETWORK_TESTS") != "1",
                             reason="set PEAKNAV_NETWORK_TESTS=1 to hit the dataset")


@network
def test_matterhorn_from_the_dataset(tmp_path):
    os.environ["PEAKNAV_ELEV_CACHE"] = str(tmp_path)
    try:
        # The Breithorn: a broad snow dome, which a stereo DEM reads true - so a
        # tight band here asserts the whole chain (tile choice, download, decode,
        # Mercator row mapping, sampling); almost any error lands far outside it.
        e = elevation_at(45.9417, 7.7480)
        assert 4100 <= e <= 4200, "Breithorn read as %s m (surveyed 4164)" % e

        # The Matterhorn: a rock spire raw ASTER clipped to ~4040 m, corrected in
        # the dataset against its surveyed 4478. The lower bound asserts the
        # corrected data is actually being served (the superseded dataset fails
        # it); the band stays loose below the survey because the summit pixel
        # sits a pixel or two from this coordinate. Code-level errors (wrong tile
        # or row mapping) land on a glacier at ~3000 m or a valley at ~2000 m.
        m = elevation_at(45.97645, 7.65837)
        assert 4250 <= m <= 4490, "Matterhorn read as %s m (corrected data reads ~4376)" % m

        # Scanned around the coordinate, the corrected summit itself appears.
        top = max(elevation_at(45.9763 + i * 0.0003, 7.6586 + j * 0.0003)
                  for i in range(-6, 7) for j in range(-7, 8))
        assert 4474 <= top <= 4490, "summit scan found %s m (surveyed 4478)" % top

        ocean = elevation_at(0.0, -30.0)
        assert ocean == 0, "mid-Atlantic should have no tile, got %s m" % ocean

        # The docstring's other claim: bilinear differs from max but not wildly.
        b = elevation_at(45.9417, 7.7480, sample="bilinear")
        assert abs(b - e) < 200
    finally:
        del os.environ["PEAKNAV_ELEV_CACHE"]
