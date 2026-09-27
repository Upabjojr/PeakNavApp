#!/usr/bin/env python3
"""Builds the offline world map: coarse SVG tiles the app's maps draw under the satellite imagery.

The search and download screens show satellite tiles fetched from the network. On a slow
connection, or with a provider that does not answer, they showed a grey nothing. These tiles
are in the app itself: the land coloured by what it roughly is - forest, dry land, desert,
mountains, tundra, ice - the oceans and large lakes blue, the national borders, and the first
level divisions of the largest countries (US states, Canadian provinces, Russian oblasts ...).
The app draws one under every satellite tile, so the map is there at once and wherever the
imagery is missing.

Data: Natural Earth (public domain, https://www.naturalearthdata.com): 1:110m for zoom 0-1,
1:50m for zoom 2-4. Web Mercator tiles, 256 units a side, as the map's own tiles are, written
to assets/world_map/<z>/<x>_<y>.svg; a tile that is all ocean is left out (the app fills it
with the ocean colour).

The SVG is a deliberately small subset, which the app's own reader (WorldMapTiles) draws:
    <rect> the whole tile, filled   - the ocean
    <path fill="#rrggbb" d="...">   - polygons: M/L/Z in absolute coordinates, even-odd fill
    <path fill="none" stroke="#rrggbb" stroke-width="w" d="..."> - lines: M/L
Anything else is not used, so any SVG viewer shows the tiles as the app does.

    tools/world_map_tiles.py [--cache DIR] [--max-zoom 4]

Needs shapely and pyshp (pip install shapely pyshp).
"""
import argparse
import io
import math
import os
import sys
import urllib.request
import zipfile

import shapefile
from shapely.geometry import box, shape, mapping
from shapely.ops import transform, unary_union

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT = os.path.join(ROOT, "assets", "world_map")
NE = "https://naciscdn.org/naturalearth/"

TILE = 256
MAX_LAT = 85.05112878

OCEAN = "#a8cbe6"
LAKE = "#a8cbe6"
ICE = "#f3f6f8"
BORDER = "#6e5a4e"
PROVINCE = "#a08a7c"

# The land's base colour by latitude, from these anchors, in bands of BAND_STEP degrees
# with the colour interpolated between them, so it shades from one zone to the next
# rather than stepping: rainforest, savanna, dry subtropics, temperate, boreal, tundra.
ANCHORS = [  # (|lat|, colour)
    (0, "#5f9a4c"),
    (10, "#6f9e55"),
    (18, "#a6b06a"),
    (28, "#b3b572"),
    (36, "#97b16f"),
    (48, "#8fae6c"),
    (56, "#789a66"),
    (65, "#7a9868"),
    (70, "#b3bca0"),
    (90, "#c3c9b2"),
]
BAND_STEP = 2.5


def colour_at(lat):
    lat = abs(lat)
    for (a, ca), (b, cb) in zip(ANCHORS, ANCHORS[1:]):
        if a <= lat <= b:
            t = (lat - a) / (b - a)
            rgb = [round(int(ca[i:i + 2], 16) * (1 - t) + int(cb[i:i + 2], 16) * t) for i in (1, 3, 5)]
            return "#%02x%02x%02x" % tuple(rgb)
    return ANCHORS[-1][1]


# Natural Earth's geography regions drawn over the base, in this order (later on top). Its
# plateaus are left out: drawn as rough ellipses, they came out as boxes over Siberia.
REGIONS = [
    ("Tundra", "#b8bf9f"),
    ("Desert", "#e2cf9b"),
    ("Range/mtn", "#a88f73"),
]


def fetch(cache, path):
    """A Natural Earth shapefile, downloaded once into the cache."""
    name = os.path.basename(path)
    folder = os.path.join(cache, name)
    shp = os.path.join(folder, name + ".shp")
    if not os.path.exists(shp):
        print("downloading", name, file=sys.stderr)
        data = urllib.request.urlopen(NE + path + ".zip", timeout=120).read()
        zipfile.ZipFile(io.BytesIO(data)).extractall(folder)
    return shp


def read(cache, path, keep=None):
    reader = shapefile.Reader(fetch(cache, path))
    fields = [f[0] for f in reader.fields[1:]]
    out = []
    for rec, shp in zip(reader.records(), reader.shapes()):
        attrs = dict(zip(fields, rec))
        if keep is None or keep(attrs):
            geom = shape(shp.__geo_interface__)
            if not geom.is_empty:
                out.append(geom if geom.is_valid else geom.buffer(0))
    return out


def mercator(lon, lat):
    """Longitude and latitude to 0..1 across the world, y downwards, as the tiles count."""
    lat = max(-MAX_LAT, min(MAX_LAT, lat))
    x = (lon + 180.0) / 360.0
    s = math.sin(math.radians(lat))
    y = 0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)
    return x, y


def project(geom):
    def f(xs, ys, zs=None):
        pts = [mercator(x, y) for x, y in zip(xs, ys)]
        return [p[0] for p in pts], [p[1] for p in pts]
    return transform(f, geom)


def band_polygons(land):
    """The land cut into latitude bands, each with the colour of its middle latitude."""
    out = []
    lat = -90.0
    while lat < 90.0:
        top = lat + BAND_STEP
        # A little over into the next band, so no seam of ocean shows where two meet.
        part = land.intersection(box(-180, max(lat - 0.4, -MAX_LAT), 180, min(top + 0.4, MAX_LAT)))
        if not part.is_empty:
            out.append((colour_at(lat + BAND_STEP / 2), part))
        lat = top
    return out


def layers(cache, detail):
    """Everything a tile draws, projected, in drawing order: (kind, colour, geometry)."""
    scale = "110m" if detail == "coarse" else "50m"
    land = unary_union(read(cache, scale + "/physical/ne_" + scale + "_land"))
    # The whole land first, in a middle colour, under the bands: whatever of it they leave
    # uncovered at the tiles' edges is land-coloured, not ocean.
    items = [("fill", colour_at(40), land)]
    items += [("fill", colour, part) for colour, part in band_polygons(land)]
    regions = read(cache, "50m/physical/ne_50m_geography_regions_polys")
    reader_attrs = shapefile.Reader(fetch(cache, "50m/physical/ne_50m_geography_regions_polys"))
    fields = [f[0] for f in reader_attrs.fields[1:]]
    classes = [dict(zip(fields, r))["FEATURECLA"] for r in reader_attrs.records()]
    for name, colour in REGIONS:
        parts = [g for g, c in zip(regions, classes) if c == name]
        if parts:
            items.append(("fill", colour, unary_union(parts).intersection(land)))
    items.append(("fill", ICE, unary_union(read(cache, "50m/physical/ne_50m_glaciated_areas"))))
    items.append(("fill", LAKE, unary_union(read(cache, scale + "/physical/ne_" + scale + "_lakes"))))
    if detail != "coarse":
        items.append(("line", PROVINCE, unary_union(read(cache, "50m/cultural/ne_50m_admin_1_states_provinces_lines"))))
    items.append(("line", BORDER, unary_union(read(cache, scale + "/cultural/ne_" + scale + "_admin_0_boundary_lines_land"))))
    # Projected geometry can come out invalid (a ring folded on itself at the poles' cut-off):
    # repaired once here, so the per-tile cuts below do not trip over it.
    from shapely.validation import make_valid
    return [(kind, colour, make_valid(project(geom))) for kind, colour, geom in items]


def fmt(v):
    """A coordinate with one decimal, trailing zeros dropped: 12.0 -> 12, 3.25 -> 3.2."""
    s = "%.1f" % v
    return s[:-2] if s.endswith(".0") else s


def rings_of(geom):
    if geom.geom_type == "Polygon":
        yield geom.exterior.coords
        for hole in geom.interiors:
            yield hole.coords
    elif hasattr(geom, "geoms"):
        for g in geom.geoms:
            yield from rings_of(g)


def lines_of(geom):
    if geom.geom_type == "LineString":
        yield geom.coords
    elif geom.geom_type == "Polygon":
        yield from rings_of(geom)
    elif hasattr(geom, "geoms"):
        for g in geom.geoms:
            yield from lines_of(g)


def path_data(seqs, x0, y0, size, close):
    parts = []
    for coords in seqs:
        pts = []
        last = None
        for x, y in coords:
            p = (fmt((x - x0) / size * TILE), fmt((y - y0) / size * TILE))
            if p != last:
                pts.append(p)
                last = p
        if len(pts) < (3 if close else 2):
            continue
        d = "M" + " L".join(a + " " + b for a, b in pts)
        parts.append(d + ("Z" if close else ""))
    return "".join(parts)


def tile_svg(items, z, x, y):
    size = 1.0 / (1 << z)
    x0, y0 = x * size, y * size
    pad = size * 0.01
    clip = box(x0 - pad, y0 - pad, x0 + size + pad, y0 + size + pad)
    tolerance = size / TILE * 0.5   # half a pixel of this zoom
    body = []
    any_land = False
    for kind, colour, geom in items:
        try:
            part = geom.intersection(clip)
        except Exception:
            part = geom.buffer(0).intersection(clip)
        if part.is_empty:
            continue
        part = part.simplify(tolerance, preserve_topology=kind == "fill")
        if part.is_empty:
            continue
        if kind == "fill":
            d = path_data(rings_of(part), x0, y0, size, True)
            if d:
                any_land = any_land or colour not in (LAKE,)
                body.append('<path fill="%s" d="%s"/>' % (colour, d))
        else:
            d = path_data(lines_of(part), x0, y0, size, False)
            if d:
                width = "0.6" if colour == PROVINCE else "1"
                body.append('<path fill="none" stroke="%s" stroke-width="%s" d="%s"/>' % (colour, width, d))
    if not any_land:
        return None
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256" fill-rule="evenodd">'
            '<rect width="256" height="256" fill="%s"/>' % OCEAN + "".join(body) + "</svg>\n")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--cache", default=os.path.join(ROOT, ".temp", "naturalearth"))
    ap.add_argument("--max-zoom", type=int, default=4)
    args = ap.parse_args()
    os.makedirs(args.cache, exist_ok=True)
    coarse = layers(args.cache, "coarse")
    fine = layers(args.cache, "fine")
    total = 0
    count = 0
    for z in range(args.max_zoom + 1):
        items = coarse if z <= 1 else fine
        folder = os.path.join(OUT, str(z))
        os.makedirs(folder, exist_ok=True)
        for f in os.listdir(folder):
            os.remove(os.path.join(folder, f))
        for x in range(1 << z):
            for y in range(1 << z):
                svg = tile_svg(items, z, x, y)
                if svg is None:
                    continue
                with open(os.path.join(folder, "%d_%d.svg" % (x, y)), "w", encoding="utf-8") as fh:
                    fh.write(svg)
                total += len(svg)
                count += 1
        print("zoom", z, "done", file=sys.stderr)
    print("%d tiles, %.2f MB" % (count, total / 1e6))


if __name__ == "__main__":
    main()
