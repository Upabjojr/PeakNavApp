#!/usr/bin/env python3
"""Generates the PeakNav snapshot set into snapshots/images/.

One tool, one source of truth: the tables below define every image; the script
writes snapshots/manifest.tsv from them (one row per image, with the exact
parameters and a stand-alone rerun command) and renders whatever is missing.

    python3 snapshots/generate_snapshots.py                  # render missing images
    python3 snapshots/generate_snapshots.py --overwrite-existing
    python3 snapshots/generate_snapshots.py --only 'santorini*'
    python3 snapshots/generate_snapshots.py --manifest-only   # just rewrite the TSV

Rendering happens off-screen through the :headless module (a hidden window on the
session DISPLAY; no window ever appears). Boots are expensive, so images sharing a
viewpoint and toggle set are rendered in one boot via repeated --shot arguments.

The image set has three kinds:
  matrix   - on-the-spot views from each sight: 8 toggle sets (sky/peaks/area
             labels on+off) x 3 bearings x 2 poses (std, wide)
  ring     - viewpoints 3/5/10 km around the sight looking back at it (up, level)
  curated  - hand-placed framings added after reviewing the generated set:
             classic viewpoints (Gornergrat, Tunnel View, Kala Patthar...) and
             corrections where the generic recipe failed (bearings that cut the
             sight, up-pitches that overshoot low islands into the sky, level
             rings that look over the summit).
  aimed    - stand here, look at that summit; bearing and pitch computed.
  backdrop - a subject with another named summit standing behind it, the camera
             placed on the line through both so the alignment is guaranteed
             rather than hoped for (Rainier with Adams, St Helens and Hood
             stacked up behind it; K2 over Broad Peak).
  toggles  - one viewpoint per kind of scene, rendered in all 16 combinations of
             sky / roads / peak names / area names, to compare the looks.
  vista    - the same computation from much further out (13-66 km), with the
             road and path layer ON. The close range of every other kind fills
             the frame with one rock face; from a named distant viewpoint the
             mountain sits in its own landscape, with the paths and roads that
             lead to it drawn across the terrain. Also the archipelago views,
             where the "sight" is a horizon full of islands rather than a peak.
"""

import argparse
import fnmatch
import json
import math
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
IMAGES_DIR = os.path.join(ROOT, "snapshots", "images")
MANIFEST = os.path.join(ROOT, "snapshots", "manifest.tsv")
#: What each image on disk was actually rendered with, so a shot whose parameters have
#: changed can be spotted and redone. Without it "the file exists" is the only test, and
#: an image silently keeps whatever framing it was first given: when the backdrop
#: geometry was corrected, every one of those images would have been skipped as done.
RENDER_LOG = os.path.join(ROOT, "snapshots", ".rendered.json")
JAVA_HOME = os.environ.get("PEAKNAV_JAVA_HOME", "/usr/lib/jvm/java-17-openjdk-amd64")

# ---------------------------------------------------------------------------
# The sights. (name, viewpoint lat, lon, bearing, pitch, elevation,
# target lat, lon) - the target is what the ring circles; None = the viewpoint.
# ---------------------------------------------------------------------------
LOCATIONS = [
    ("zermatt-matterhorn",  46.0207,    7.7491,   210, -3, 0.55,  45.9763,    7.6586),
    ("dolomites-tre-cime",  46.6180,   12.3050,   200, -3, 0.50,  None, None),
    ("yosemite-valley",     37.7450, -119.5330,    90, -2, 0.45,  None, None),
    ("mount-rainier",       46.9000, -121.9000,   135, -2, 0.55,  46.8523, -121.7603),
    ("banff-lake-louise",   51.4254, -116.2110,   250, -2, 0.55,  None, None),
    ("aoraki-mount-cook",  -43.7200,  170.1000,    15, -2, 0.55, -43.5950,  170.1418),
    ("everest",             27.9000,   86.8500,    40,  0, 0.65,  27.9881,   86.9250),
    ("hvar-croatia",        43.1500,   16.5000,   280, -4, 0.60,  None, None),
    ("santorini-greece",    36.3800,   25.4600,   320, -4, 0.60,  None, None),
]

# ---------------------------------------------------------------------------
# Curated shots: (file stem, lat, lon, bearing, pitch, elevation). Added after
# reviewing the full generated set - each entry either shows a sight the generic
# recipe missed, or reframes one it got wrong. One image each, peaks + area
# labels on, sky on.
#
#   zermatt   gornergrat     the classic Matterhorn view from the Gornergrat ridge
#   rainier   centered-nw    the matrix bearing (135) cut Rainier at the frame edge;
#                            the true viewpoint->summit bearing is ~116
#   rainier   paradise       from Paradise on the south side, looking up the mountain
#   yosemite  tunnel-view    the matrix looked east AWAY from the valley; this is the
#                            Tunnel View framing into it
#   yosemite  glacier-point  Half Dome from Glacier Point
#   dolomites locatelli      the Tre Cime north faces from Rifugio Locatelli
#   aoraki    hooker-valley  Aoraki up the Hooker Valley
#   everest   kala-patthar   the classic Kala Patthar view
#   banff     lake-shore     across Lake Louise to the Victoria Glacier, from the shore
#   santorini caldera-fira   the caldera from Fira, looking down into it - the generic
#                            up-pitch put a 500 m island entirely below the frame
#   santorini sea-approach   the island from the sea to the west
#   hvar      town-west      along the island over Hvar town and the Pakleni islands
#   hvar      sea-north      the island from the sea to the north
# ---------------------------------------------------------------------------
CURATED = [
    ("zermatt-matterhorn.curated.gornergrat",    45.9833,    7.7853, 265,  2, 0.18),
    ("mount-rainier.curated.centered-nw",        46.9000, -121.9000, 116, -1, 0.40),
    ("mount-rainier.curated.paradise",           46.7860, -121.7350, 355,  8, 0.15),
    ("yosemite-valley.curated.tunnel-view",      37.7156, -119.6772,  68,  2, 0.15),
    ("yosemite-valley.curated.glacier-point",    37.7300, -119.5730,  28,  3, 0.20),
    ("dolomites-tre-cime.curated.locatelli",     46.6427,   12.3223, 208,  4, 0.15),
    ("aoraki-mount-cook.curated.hooker-valley", -43.6690,  170.0996,  12,  5, 0.12),
    ("everest.curated.kala-patthar",             27.9626,   86.8285,  78,  6, 0.15),
    ("banff-lake-louise.curated.lake-shore",     51.4172, -116.2160, 235,  3, 0.10),
    ("santorini-greece.curated.caldera-fira",    36.4202,   25.4310, 300, -4, 0.12),
    ("santorini-greece.curated.sea-approach",    36.4000,   25.3500,  80,  1, 0.10),
    ("hvar-croatia.curated.town-west",           43.1729,   16.4433, 250, -5, 0.15),
    ("hvar-croatia.curated.sea-north",           43.2200,   16.3500, 135,  0, 0.10),
]

# ---------------------------------------------------------------------------
# Aimed shots: the camera is not given a bearing at all - it is given a place to
# stand and a summit to look at, and the geometry is computed.
#
#   (stem, eye lat, eye lon, eye ground altitude m, target lat, target lon,
#    target summit altitude m, elevation-bar fraction)
#
# bearing = initial great-circle bearing eye -> target
# pitch   = atan2(target altitude - eye altitude, ground distance)
#
# Hand-estimated bearings are how the first Gornergrat shot came out 18 degrees
# off and framed the wrong ridge: eyeballing a bearing from two coordinates is
# precisely the arithmetic a computer should do. Altitudes are the real ones for
# these viewpoints and summits, so the pitch puts the summit on the horizon line
# of the frame rather than guessing how far to tilt.
# ---------------------------------------------------------------------------
AIMED = [
    # Matterhorn (4478 m) from three classic viewpoints at different distances
    ("zermatt-matterhorn.aimed.gornergrat",      45.9833,   7.7853, 3089,  45.9763,   7.6586, 4478, 0.12),
    ("zermatt-matterhorn.aimed.schwarzsee",      45.9906,   7.7048, 2583,  45.9763,   7.6586, 4478, 0.12),
    ("zermatt-matterhorn.aimed.zermatt-village", 46.0207,   7.7491, 1608,  45.9763,   7.6586, 4478, 0.12),
    # Rainier (4392 m) from the two main visitor areas, north and south
    ("mount-rainier.aimed.paradise",             46.7860, -121.7350, 1647, 46.8523, -121.7603, 4392, 0.12),
    ("mount-rainier.aimed.sunrise",              46.9146, -121.6425, 1950, 46.8523, -121.7603, 4392, 0.12),
    # Half Dome (2694 m) from Tunnel View and Glacier Point
    ("yosemite-valley.aimed.tunnel-view",        37.7156, -119.6772, 1450, 37.7459, -119.5332, 2694, 0.12),
    ("yosemite-valley.aimed.glacier-point",      37.7275, -119.5741, 2199, 37.7459, -119.5332, 2694, 0.12),
    # Cima Grande di Lavaredo (2999 m) from the hut to its north and the road south
    ("dolomites-tre-cime.aimed.locatelli",       46.6427,  12.3223, 2405,  46.6183,  12.3053, 2999, 0.12),
    ("dolomites-tre-cime.aimed.auronzo",         46.6083,  12.2950, 2320,  46.6183,  12.3053, 2999, 0.12),
    # Aoraki / Mount Cook (3724 m) up the Hooker Valley
    ("aoraki-mount-cook.aimed.hooker-valley",   -43.6690, 170.0996,  900, -43.5950, 170.1418, 3724, 0.12),
    # Everest (8849 m) from Kala Patthar and Base Camp
    ("everest.aimed.kala-patthar",               27.9626,  86.8285, 5643,  27.9881,  86.9250, 8849, 0.12),
    ("everest.aimed.base-camp",                  28.0026,  86.8528, 5364,  27.9881,  86.9250, 8849, 0.12),
    # Mount Victoria (3464 m) across Lake Louise
    ("banff-lake-louise.aimed.lake-shore",       51.4172, -116.2160, 1750, 51.3833, -116.2833, 3464, 0.10),
    # Santorini: down into the caldera at Nea Kameni (130 m) from Fira's rim (250 m),
    # and back up at the Thera rim (566 m) from the water - a negative and a positive
    # pitch that the mountain-scaled formula could never have produced.
    ("santorini-greece.aimed.caldera-from-fira",  36.4202,  25.4310,  250, 36.3990,  25.3960,  130, 0.10),
    ("santorini-greece.aimed.rim-from-sea",       36.4100,  25.3500,    0, 36.4200,  25.4300,  566, 0.05),
    # Hvar: Sv. Nikola (628 m), the island's high point, from the town and from the sea
    ("hvar-croatia.aimed.town-to-summit",         43.1729,  16.4433,   20, 43.1400,  16.6200,  628, 0.08),
    ("hvar-croatia.aimed.sea-to-island",          43.2200,  16.5500,    0, 43.1400,  16.6200,  628, 0.05),
]

# ---------------------------------------------------------------------------
# Vistas: the same "stand here, look at that" computation as AIMED, but from a
# real named viewpoint far enough away (13-66 km) that the subject sits in its
# landscape instead of filling the frame. Every other kind is shot from on top of
# the sight or a few km from it, which is why the set had no picture that simply
# shows a mountain. The road and path layer is on for these.
#
#   (stem, eye lat, eye lon, eye ground altitude m,
#    target lat, target lon, target altitude m, camera lift above ground m, pitch)
#
# The bearing is computed - that is arithmetic, and eyeballing it is what put the
# first Gornergrat shot 18 degrees off. The pitch is explicit, because how much sky
# to leave above a subject is a judgement about the frame, not a derivation: these
# values were each chosen by rendering the shot and looking at it.
#
# The lift column is a height in METRES above the viewpoint, passed to the renderer
# as metres (--shot ...,2500m,...). Vistas are flown a couple of kilometres up on
# purpose: a distant view fails when a ridge a kilometre away happens to stand in
# front of the subject - the first attempt at Crystal Mountain looked into the near
# hillside because the coordinate landed at the ski base in the valley rather than on
# the summit. Height clears the foreground and makes a shot tolerant of a viewpoint
# coordinate that is off by a kilometre, which hand-entered ones sometimes are.
#
# Distances below are eye->target, computed not estimated; the comment on each
# line is what the frame is meant to contain.
# ---------------------------------------------------------------------------
VISTAS = [
    # --- Mount Rainier (4392 m): the four quadrants, 17-22 km out. "sunrise" at
    #     10 km already fills the entire frame with the mountain.
    ("mount-rainier.vista.crystal-mountain",          46.9283,  -121.4886, 2100,   46.8523,  -121.7603, 4392, 3200,  -4),  # 22 km NE
    ("mount-rainier.vista.high-rock",                 46.6836,  -121.8944, 1615,   46.8523,  -121.7603, 4392, 3000,  -3),  # 21 km SW
    ("mount-rainier.vista.mount-beljica",             46.7897,  -121.9614, 1690,   46.8523,  -121.7603, 4392, 3000,  -2),  # 17 km W
    ("mount-rainier.vista.shriner-peak",              46.7597,  -121.5433, 1737,   46.8523,  -121.7603, 4392, 3000,  -3),  # 18 km SE
    ("mount-rainier.vista.naches-peak",               46.8697,  -121.5158, 1876,   46.8523,  -121.7603, 4392, 3000,  -3),  # 19 km E
    # --- Matterhorn (4478 m) from across the Mattertal and from 26 km north
    ("zermatt-matterhorn.vista.domhutte",             46.0872,     7.8161, 2940,   45.9763,     7.6586, 4478, 2650,  -4),  # 17 km NE
    ("zermatt-matterhorn.vista.bella-tola",           46.2100,     7.6600, 3025,   45.9763,     7.6586, 4478, 3100,  -5),  # 26 km N
    # --- Everest (8849 m) from the two villages on the trek in
    ("everest.vista.tengboche",                       27.8361,    86.7644, 3867,   27.9881,    86.9250, 8849, 3250,  -1),  # 23 km SW
    ("everest.vista.namche-bazaar",                   27.8047,    86.7139, 3440,   27.9881,    86.9250, 8849, 3500,  -1),  # 29 km SW
    # --- Tre Cime (2999 m): the postcard framing from the ridges west of them
    ("dolomites-tre-cime.vista.strudelkopf",          46.6486,    12.2244, 2307,   46.6183,    12.3053, 2999, 2400,  -4),  # 7 km NW
    ("dolomites-tre-cime.vista.monte-piana",          46.6167,    12.2333, 2324,   46.6183,    12.3053, 2999, 2250,  -4),  # 6 km W
    # --- Aoraki (3724 m) up the Tasman valley, and the long view up Lake Pukaki
    ("aoraki-mount-cook.vista.tasman-valley",        -43.7000,   170.1600,  800,  -43.5950,   170.1418, 3724, 2800,   0),  # 12 km S
    ("aoraki-mount-cook.vista.lake-pukaki",          -44.1878,   170.1367,  520,  -43.5950,   170.1418, 3724, 4450,  -5), # 66 km S
    # --- Half Dome (2694 m) with the whole valley below it
    ("yosemite-valley.vista.washburn-point",          37.7128,  -119.5744, 2200,   37.7459,  -119.5332, 2694, 2100,  -4),  # 5 km SW
    ("yosemite-valley.vista.turtleback-dome",         37.7150,  -119.6900, 1500,   37.7459,  -119.5332, 2694, 2750,  -4),  # 14 km W

    # --- Teide, Tenerife (3715 m) - requested. Four quadrants, 13-22 km out: the
    #     pine forest to the south, the ridge road NE, the observatory E, and the
    #     whole island profile from sea level on the north coast. A fifth from the
    #     caldera floor at Roques de Garcia was dropped: 5 km is close enough that
    #     the cone fills the frame, which is the problem these shots exist to fix.
    ("teide-tenerife.vista.vilaflor",                 28.1583,   -16.6353, 1400,   28.2724,   -16.6425, 3715, 2500,  -4),  # 13 km S
    ("teide-tenerife.vista.mirador-chipeque",         28.3667,   -16.4500, 1740,   28.2724,   -16.6425, 3715, 3200,  -4),  # 22 km NE
    ("teide-tenerife.vista.izana",                    28.3092,   -16.5117, 2390,   28.2724,   -16.6425, 3715, 2700,  -4),  # 13 km NE
    ("teide-tenerife.vista.puerto-de-la-cruz",        28.4189,   -16.5486,   20,   28.2724,   -16.6425, 3715, 3100,  -1),  # 19 km N, from sea level
    ("teide-tenerife.vista.teno-alto",                28.3200,   -16.8800,  800,   28.2724,   -16.6425, 3715, 3200,  -4),  # 24 km W
    ("teide-tenerife.vista.portillo",                 28.3175,   -16.5847, 2050,   28.2724,   -16.6425, 3715, 1200,  -1),  # 8 km NE, camera below the summit
    ("teide-tenerife.vista.chirche",                  28.2000,   -16.7833, 1000,   28.2724,   -16.6425, 3715, 2800,  -4),  # 16 km SW
    ("teide-tenerife.vista.roque-del-conde",          28.1200,   -16.7200, 1000,   28.2724,   -16.6425, 3715, 3000,  -4),  # 19 km S
    ("teide-tenerife.vista.anaga",                    28.5167,   -16.2500,  800,   28.2724,   -16.6425, 3715, 4000,  -5),  # 47 km NE, the whole island
    ("teide-tenerife.vista.la-gomera",                28.0917,   -17.1100,  300,   28.2724,   -16.6425, 3715, 4000,  -5),  # 50 km SW, from another island

    # --- Everest (8849 m): four more angles, including the Tibetan north face
    ("everest.vista.gokyo-ri",                        27.9550,    86.6900, 5357,   27.9881,    86.9250, 8849, 3000,  -2),  # 23 km W
    ("everest.vista.pheriche",                        27.8950,    86.8190, 4371,   27.9881,    86.9250, 8849, 3000,  -1),  # 15 km SW
    ("everest.vista.renjo-la",                        27.9800,    86.6400, 5360,   27.9881,    86.9250, 8849, 3200,  -2),  # 28 km W
    ("everest.vista.rongbuk",                         28.1500,    86.8500, 5000,   27.9881,    86.9250, 8849, 3000,  -2),  # 20 km N, the north face

    # --- K2 (8611 m): a sight the set did not have at all. Concordia is the view.
    ("k2-karakoram.vista.concordia",                  35.7400,    76.5000, 4600,   35.8808,    76.5133, 8611, 3000,  -1),  # 16 km S
    ("k2-karakoram.vista.broad-peak-bc",              35.7900,    76.5400, 4900,   35.8808,    76.5133, 8611, 2500,  -1),  # 10 km S
    ("k2-karakoram.vista.gondogoro-la",               35.6500,    76.5300, 5585,   35.8808,    76.5133, 8611, 3000,  -3),  # 26 km S
    ("k2-karakoram.vista.urdukas",                    35.7300,    76.2900, 4050,   35.8808,    76.5133, 8611, 3200,  -3),  # 26 km SW

    # --- Greek islands: viewpoints chosen for how MANY islands land in frame,
    #     not for one summit. Mount Zas is the great panorama of the Cyclades.
    ("naxos-cyclades.vista.zas-to-paros",             37.0197,    25.4783, 1004,   37.0836,    25.1361,  771, 3050,  -7),  # 31 km W: Paros, Antiparos
    ("naxos-cyclades.vista.zas-to-amorgos",           37.0197,    25.4783, 1004,   36.8333,    25.9833,  821, 3500,  -7),  # 49 km SE: the Small Cyclades
    ("delos-cyclades.vista.kynthos-to-tinos",         37.3944,    25.2708,  113,   37.5667,    25.2000,  726, 2850,  -6),  # 20 km N: Mykonos, Rineia, Syros
    ("santorini-greece.vista.profitis-ilias",         36.3922,    25.4525,  567,   36.7167,    25.2833,  713, 3300,  -6),  # 39 km NW: Ios, Sikinos, Folegandros

    # --- Croatian islands: the Adriatic archipelago from its own high points, and
    #     from the mountain that looks down on all of it.
    ("brac-croatia.vista.vidova-gora",                43.2650,    16.6197,  778,   43.1400,    16.6200,  628, 2800,  -7),  # 14 km S: Bol, the channel, Hvar
    ("hvar-croatia.vista.nikola-to-vis",              43.1400,    16.6200,  628,   43.0333,    16.1000,  587, 3550,  -6),  # 44 km WSW: Scedro, Vis, Bisevo
    ("biokovo-croatia.vista.sveti-jure",              43.3167,    17.0553, 1762,   43.1400,    16.6200,  628, 3000,  -7),  # 40 km SW: Brac and Hvar together
    ("kornati-croatia.vista.dugi-otok",               43.9722,    15.0333,  338,   43.8069,    15.2589,  237, 3100,  -7),  # 26 km SE: the Kornati, dozens of them

    # --- Norwegian fjords, the fjord itself: a trench of water between walls. The
    #     camera stays low here - a few hundred metres - because height is exactly
    #     what destroys a fjord shot, flattening the walls that make it one.
    ("geirangerfjord.vista.dalsnibba",         62.0450,    7.2700, 1476,  62.1010,    7.2060,    0,  800, -6),  # 7 km NNW down to Geiranger
    ("geirangerfjord.vista.ornesvingen",       62.1075,    7.1600,  620,  62.1050,    7.0000,    0,  700, -5),  # 8 km W along the fjord
    ("geirangerfjord.vista.flydalsjuvet",      62.0925,    7.1800,  300,  62.1200,    7.1200,    0,  600, -6),  # 4 km NW, the classic ledge
    ("naeroyfjord.vista.rimstigen",            60.8817,    6.8850,  720,  60.9400,    6.8000,    0,  900, -6),  # 8 km NW, the narrowest fjord in Europe
    ("naeroyfjord.vista.bakkanosi",            60.8917,    6.9333, 1398,  60.9400,    6.8000,    0,  700, -8),  # 9 km NW from 1400 m above it
    ("aurlandsfjord.vista.stegastein",         60.9028,    7.1897,  650,  60.9800,    7.1000,    0,  800, -6),  # 10 km N from the viewing platform
    ("lysefjord.vista.preikestolen",           58.9864,    6.1900,  604,  59.0400,    6.5000,    0,  900, -5),  # 19 km E up the fjord from Pulpit Rock
    ("lysefjord.vista.kjerag",                 59.0344,    6.5936, 1084,  58.9700,    6.2000,    0, 1000, -6),  # 24 km WSW down to the sea
    ("hardangerfjord.vista.dronningstien",     60.4200,    6.7500,  900,  60.3800,    6.5000,    0, 1000, -5),  # 15 km WSW along Sorfjorden

    # --- Norwegian fjords with islands, where the water opens out and fills with them
    ("lofoten.vista.reinebringen",             67.9308,   13.0894,  448,  67.9500,   12.9000,    0,  900, -7),  # 8 km W over Reinefjorden
    ("lofoten.vista.henningsvaer",             68.1533,   14.2033,   20,  68.2500,   14.4000,  900, 1200, -6),  # 14 km NE, islets under the wall
    ("lofoten.vista.from-bodo",                67.2800,   14.4000,  100,  68.0000,   13.5000, 1000, 3000, -5),  # 82 km NW, the whole Lofoten wall
    ("helgeland.vista.torghatten",             65.4000,   12.1000,  258,  65.2500,   12.0000,    0, 1500, -7),  # 17 km S over the Helgeland skerries
    ("alesund.vista.aksla",                    62.4725,    6.1650,  189,  62.4800,    5.9000,    0, 1200, -7),  # 14 km W over the Sunnmore islands
    ("senja.vista.segla",                      69.4906,   17.5461,  639,  69.5500,   17.3000,    0,  900, -7),  # 12 km NW, fjord mouth and islets
    ("tromso.vista.storsteinen",               69.6350,   18.9950,  421,  69.6800,   18.6000,  600, 1200, -7),  # 16 km W over Kvaloya

    # --- Scottish islands: the Hebrides, where a viewpoint sees a dozen at once
    ("skye.vista.elgol",                       57.1450,   -6.1050,   40,  57.2100,   -6.2200,  992,  800, -3),  # 10 km NW to the Cuillin over Loch Scavaig
    ("skye.vista.quiraing",                    57.6400,   -6.2800,  400,  57.7000,   -6.1000,    0,  800, -6),  # 13 km NE over the Sound of Raasay
    ("skye.vista.bealach-na-ba",               57.4200,   -5.7000,  626,  57.4000,   -6.0000,  900, 1200, -6),  # 18 km W from the mainland: Raasay, Scalpay, Skye
    ("mull.vista.ben-more",                    56.4172,   -6.0075,  966,  56.3300,   -6.4000,    0, 1500, -7),  # 26 km WSW to Ulva, Staffa and Iona
    ("arran.vista.goat-fell",                  55.6250,   -5.1917,  874,  55.5300,   -5.0700,  300, 1000, -7),  # 13 km SE over the Firth of Clyde
    ("harris.vista.clisham",                   57.9639,   -6.8125,  799,  57.7500,   -7.0500,    0, 1500, -7),  # 28 km SSW over the Sound of Harris
    ("eigg.vista.an-sgurr",                    56.9000,   -6.1300,  393,  57.0000,   -6.3500,  812, 1000, -5),  # 17 km NW to Rum across the Small Isles
]

# ---------------------------------------------------------------------------
# Backdrop shots: a subject with another named summit standing behind it.
#
# "A nice view of Mount Rainier with other volcanoes in the background" is not a
# taste that has to be guessed at - it is a line. Put the camera on the line that
# runs from the background peak through the subject, extended past the subject, and
# the background peak is behind the subject by construction: look along that line
# and you see the near mountain first and the far one over its shoulder. The Cascade
# volcanoes are 50-150 km apart along one axis, so several stack up at once.
#
# Only the distance from the subject is chosen by hand; everything else - where to
# stand, which way to face, how far to tilt - is computed.
# ---------------------------------------------------------------------------
PEAKS = {
    # Cascades
    "rainier":       (46.8523, -121.7603, 4392),
    "adams":         (46.2024, -121.4909, 3743),
    "st-helens":     (46.1912, -122.1944, 2549),
    "hood":          (45.3735, -121.6959, 3429),
    "glacier-peak":  (48.1120, -121.1130, 3213),
    "baker":         (48.7768, -121.8145, 3286),
    # Alps
    "matterhorn":    (45.9763,    7.6586, 4478),
    "monte-rosa":    (45.9369,    7.8669, 4634),
    "mont-blanc":    (45.8326,    6.8652, 4808),
    "jorasses":      (45.8697,    6.9878, 4208),
    "marmolada":     (46.4344,   11.8517, 3343),
    "tre-cime":      (46.6183,   12.3053, 2999),
    # Himalaya
    "everest":       (27.9881,   86.9250, 8849),
    "lhotse":        (27.9617,   86.9330, 8516),
    "makalu":        (27.8892,   87.0886, 8485),
    "cho-oyu":       (28.0942,   86.6608, 8188),
    "ama-dablam":    (27.8617,   86.8611, 6812),
    # Karakoram
    "k2":            (35.8808,   76.5133, 8611),
    "broad-peak":    (35.8108,   76.5686, 8051),
    "gasherbrum-1":  (35.7239,   76.6961, 8080),
    # Southern Alps
    "aoraki":       (-43.5950,  170.1418, 3724),
    "tasman":       (-43.5667,  170.1500, 3497),
    # Canaries
    "teide":         (28.2724,  -16.6425, 3715),
    "garajonay":     (28.1167,  -17.2372, 1487),
    # More of the Alps
    "eiger":         (46.5775,    8.0053, 3967),
    "jungfrau":      (46.5367,    7.9625, 4158),
    "grossglockner": (47.0744,   12.6944, 3798),
    "grossvenediger":(47.1094,   12.3464, 3657),
    "gran-paradiso": (45.5175,    7.2686, 4061),
    "bernina":       (46.3819,    9.9083, 4049),
    "piz-palu":      (46.3811,    9.9678, 3900),
    "ortler":        (46.5089,   10.5450, 3905),
    # Alpine lakes. A lake is a subject like any other - and being flat, it hides
    # nothing behind it, so its mountains want a much smaller offset than a peak does.
    "lago-braies":   (46.6944,   12.0850, 1496),
    "seekofel":      (46.6544,   12.0894, 2810),
    "lago-carezza":  (46.4092,   11.5772, 1534),
    "latemar":       (46.3844,   11.5883, 2842),
    "oeschinensee":  (46.4972,    7.7267, 1578),
    "doldenhorn":    (46.4667,    7.7500, 3638),
    "konigssee":     (47.5539,   12.9856,  603),
    "watzmann":      (47.5553,   12.9222, 2713),
    "molveno":       (46.1400,   10.9600,  823),
    "cima-tosa":     (46.1650,   10.8800, 3173),
    "silsersee":     (46.4200,    9.7500, 1797),
    "corvatsch":     (46.4083,    9.8194, 3451),
    "lago-como":     (46.0100,    9.2600,  199),
    "monte-legnone": (46.1069,    9.4083, 2609),
    "hallstatter":   (47.5600,   13.6600,  508),
    "dachstein":     (47.4753,   13.6058, 2995),
    "lago-garda":    (45.7000,   10.7000,   65),
    "monte-baldo":   (45.7333,   10.8500, 2218),
    "bled":          (46.3625,   14.0925,  475),
    "triglav":       (46.3775,   13.8367, 2864),
    # Scotland: hills, lochs and islands
    "sgurr-alasdair":(57.2114,   -6.2261,  992),
    "bla-bheinn":    (57.2244,   -6.0872,  928),
    "loch-coruisk":  (57.2000,   -6.1833,    0),
    "loch-lomond":   (56.1000,   -4.6300,    8),
    "ben-lomond":    (56.1900,   -4.6333,  974),
    "loch-maree":    (57.7000,   -5.4500,   10),
    "slioch":        (57.6706,   -5.3436,  981),
    "buachaille":    (56.6417,   -4.9000, 1022),
    "ben-nevis":     (56.7969,   -5.0036, 1345),
    "goat-fell":     (55.6250,   -5.1917,  874),
    "holy-isle":     (55.5300,   -5.0700,  314),
    "rum":           (57.0000,   -6.3500,  812),
    "eigg":          (56.9000,   -6.1300,  393),
    "clisham":       (57.9639,   -6.8125,  799),
    "sound-of-harris": (57.7500, -7.0500,    0),
    # Norway
    "reinefjorden":  (67.9350,   13.0500,    0),
    "hermannsdalstinden": (67.9500, 12.9667, 1029),
    "geirangerfjord":(62.1050,    7.1000,    0),
    "dalsnibba":     (62.0450,    7.2700, 1476),
    "naeroyfjord":   (60.9200,    6.8400,    0),
    "lofoten-wall":  (68.0500,   13.6000, 1000),
    "bakkanosi":     (60.8917,    6.9333, 1398),
    "segla":         (69.4906,   17.5461,  639),
    # Islands already used as vista targets, now as subjects and backdrops
    "santorini":     (36.4000,   25.4300,  566),
    "ios":           (36.7167,   25.2833,  713),
    "naxos-zas":     (37.0197,   25.4783, 1004),
    "paros":         (37.0836,   25.1361,  771),
    "hvar-nikola":   (43.1400,   16.6200,  628),
    "vis-hum":       (43.0333,   16.1000,  587),
    "brac-vidova":   (43.2650,   16.6197,  778),
}

#: (sight, subject, backdrop, ground altitude where the camera lands m, distances km,
#:  separation degrees)
#:
#: The ground altitude is the only estimate here, and it only feeds the pitch: an error
#: of 1 km shifts the tilt by about 2 degrees at 30 km, which the frame absorbs.
#:
#: Separation is how far apart the two stand in the frame. A tall near subject needs a
#: real offset or it eclipses its own backdrop - 8 degrees for peak behind peak. A lake
#: is flat and hides nothing, so 3 degrees puts its mountains directly beyond the water,
#: which is the picture worth having.
BACKDROPS = [
    # --- Cascade volcanoes: the range is one long line of them, 50-150 km apart
    ("mount-rainier",      "rainier",       "adams",         700, (20, 35, 50), 8),
    ("mount-rainier",      "rainier",       "st-helens",     700, (20, 35),     8),
    ("mount-rainier",      "rainier",       "hood",          700, (25, 40),     8),
    ("mount-rainier",      "rainier",       "baker",         700, (25, 40),     8),
    ("mount-rainier",      "rainier",       "glacier-peak",  700, (25, 40),     8),
    ("mount-adams",        "adams",         "rainier",       800, (20, 35),     8),
    ("mount-st-helens",    "st-helens",     "rainier",       600, (20, 30),     8),
    ("mount-hood",         "hood",          "adams",         700, (25, 40),     8),
    # --- Alps: peak against peak
    ("zermatt-matterhorn", "matterhorn",    "monte-rosa",   2000, (15, 30),     8),
    ("monte-rosa",         "monte-rosa",    "matterhorn",   2000, (15, 30),     8),
    ("mont-blanc",         "mont-blanc",    "jorasses",     1500, (20, 35),     7),
    ("gran-paradiso",      "gran-paradiso", "mont-blanc",   1800, (25, 40),     8),
    ("eiger",              "eiger",         "jungfrau",     1500, (15, 25),     7),
    ("jungfrau",           "jungfrau",      "eiger",        1500, (15, 25),     7),
    ("bernina",            "bernina",       "piz-palu",     2000, (12, 22),     6),
    ("grossglockner",      "grossglockner", "grossvenediger",1600,(20, 35),     8),
    ("dolomites-tre-cime", "tre-cime",      "marmolada",    1600, (15, 25),     8),
    ("marmolada",          "marmolada",     "tre-cime",     1600, (18, 30),     8),
    ("ortler",             "ortler",        "bernina",      1500, (25, 40),     8),
    # --- Alpine lakes with their mountains. Small separation on purpose: the water is
    #     flat, so the peaks stand straight beyond it instead of being pushed aside.
    ("lago-braies",        "lago-braies",   "seekofel",     1500, (3, 6),       3),
    ("lago-carezza",       "lago-carezza",  "latemar",      1530, (3, 6),       3),
    ("oeschinensee",       "oeschinensee",  "doldenhorn",   1570, (3, 6),       3),
    ("konigssee",          "konigssee",     "watzmann",      600, (4, 8),       3),
    ("lago-molveno",       "molveno",       "cima-tosa",     820, (4, 8),       3),
    ("silsersee",          "silsersee",     "corvatsch",    1790, (4, 8),       3),
    ("lago-como",          "lago-como",     "monte-legnone", 200, (8, 15),      4),
    ("hallstattersee",     "hallstatter",   "dachstein",     510, (6, 12),      3),
    ("lago-garda",         "lago-garda",    "monte-baldo",    70, (8, 15),      4),
    ("lago-bled",          "bled",          "triglav",       480, (6, 12),      3),
    # --- Himalaya and Karakoram
    ("everest",            "everest",       "makalu",       4500, (25, 40),     8),
    ("everest",            "everest",       "cho-oyu",      4500, (25, 40),     8),
    ("ama-dablam",         "ama-dablam",    "everest",      4200, (15, 25),     7),
    ("everest",            "everest",       "lhotse",       4500, (30, 45),     6),
    ("k2-karakoram",       "k2",            "broad-peak",   4500, (20, 35),     7),
    ("k2-karakoram",       "k2",            "gasherbrum-1", 4500, (25, 40),     8),
    ("broad-peak",         "broad-peak",    "k2",           4500, (20, 30),     7),
    # --- Southern Alps and the Canaries
    ("aoraki-mount-cook",  "aoraki",        "tasman",        600, (20, 35),     6),
    ("teide-tenerife",     "teide",         "garajonay",       0, (25, 40),     8),
    # --- Norwegian fjords: the water in front, the wall or the islands beyond
    ("lofoten",            "reinefjorden",  "hermannsdalstinden", 0, (5, 9),   4),
    ("lofoten",            "lofoten-wall",  "segla",         200, (40, 70),     6),
    ("geirangerfjord",     "geirangerfjord","dalsnibba",       0, (6, 12),      5),
    ("naeroyfjord",        "naeroyfjord",   "bakkanosi",       0, (5, 9),       5),
    # --- Scotland: hills over lochs, and islands beyond islands
    ("skye",               "loch-coruisk",  "sgurr-alasdair",  0, (4, 8),       3),
    ("skye",               "sgurr-alasdair","bla-bheinn",    300, (12, 20),     7),
    ("loch-lomond",        "loch-lomond",   "ben-lomond",     10, (6, 12),      3),
    ("loch-maree",         "loch-maree",    "slioch",         10, (5, 10),      3),
    ("glencoe",            "buachaille",    "ben-nevis",     300, (12, 20),     7),
    ("arran",              "goat-fell",     "holy-isle",     100, (10, 18),     6),
    ("eigg",               "eigg",          "rum",             0, (12, 20),     6),
    ("harris",             "clisham",       "sound-of-harris", 50,(15, 25),     6),
    # --- Islands beyond islands, in the Aegean and the Adriatic
    ("santorini-greece",   "santorini",     "ios",             0, (20, 35),     6),
    ("naxos-cyclades",     "naxos-zas",     "paros",           0, (20, 35),     6),
    ("hvar-croatia",       "hvar-nikola",   "vis-hum",         0, (18, 30),     6),
    ("brac-croatia",       "brac-vidova",   "hvar-nikola",     0, (15, 25),     6),
]

#: Backdrop shots that also get a peaks-on variant, so the summits behind the subject
#: can be read by name. Kept to one group: the label pillars are the reason peaks are
#: off by default, and a frame with five volcanoes in it collects a lot of them.
BACKDROP_PEAK_LABEL_SIGHTS = ("mount-rainier",)

AREA_LABELS = "mountain_ranges,islands,lakes,cities"
PEAK_LABELS = "peaks"
#: Vistas draw the road and path layer - the point of standing back is to see the
#: paths that lead to the mountain, and they carry their own route numbers in the
#: terrain texture rather than on pillars. Peak labels are off: at 20 km a frame
#: catches 15+ summits and the pillars cover the mountain they name. Cities and
#: alpine huts are off too - hut labels turned out to reach ~150 km, filling a
#: Zermatt frame with refuges in France. Add "peaks" here to get the pillars back.
VISTA_LABELS = "mountain_ranges,islands,lakes,roads"

#: Vistas that also get the toggle variants below, on top of their default frame.
#: Teide is the sight to compare on: a bare cone reads very differently against a
#: blue sky and against black, and its summit ring carries a lot of peak names.
VISTA_VARIANT_STEMS = (
    "teide-tenerife.vista.mirador-chipeque",
    "teide-tenerife.vista.vilaflor",
    "teide-tenerife.vista.teno-alto",
    "teide-tenerife.vista.la-gomera",
)
#: (file suffix, sky, add peak labels). The default frame is sky on / peaks off, so
#: these are the other three corners of the two toggles.
#: Viewpoints rendered in every combination of the four toggles below, so the same
#: frame can be compared with and without each. One per kind of scene rather than all
#: of them: each combination is its own app boot, because the sky and every label
#: category live in the boot key, so 16 of them over all ~200 viewpoints would be some
#: 3200 boots - days of rendering - against 16 for one viewpoint.
TOGGLE_STEMS = (
    "mount-rainier.vista.crystal-mountain",          # a mountain in its landscape
    "mount-rainier.backdrop.adams.d35km",            # subject with a backdrop behind
    "teide-tenerife.vista.mirador-chipeque",         # volcano over the sea
    "zermatt-matterhorn.vista.domhutte",             # alpine rock
    "everest.vista.gokyo-ri",                        # high snow, little else
    "k2-karakoram.vista.concordia",                  # glacier and giants
    "lago-braies.backdrop.seekofel.d3km",            # lake under a wall
    "oeschinensee.backdrop.doldenhorn.d3km",         # lake, steeper
    "geirangerfjord.vista.dalsnibba",                # a fjord as a trench
    "lofoten.vista.reinebringen",                    # fjord opening into islands
    "naxos-cyclades.vista.zas-to-amorgos",           # an archipelago horizon
    "skye.vista.elgol",                              # Scottish island and mountain
    "brac-croatia.vista.vidova-gora",                # Adriatic islands, many labels
)

#: The four toggles, each rendered on and off: 2^4 = 16 images per viewpoint.
TOGGLE_AREA_LABELS = "mountain_ranges,islands,lakes"

VISTA_VARIANTS = (
    ("sky-off", "off", False),
    ("peaks-on", "on", True),
    ("sky-off.peaks-on", "off", True),
)
RING_DISTANCES_KM = [3, 5, 10]
RING_AZIMUTHS = [0, 90, 180, 270]
WIDTH, HEIGHT = 1600, 1000
#: Language for every rendered label. Fixed, not inherited from the machine: the app
#: follows the system language, so the same shot rendered on an Italian desktop came out
#: with the planets labelled Mercurio and Venere.
LANGUAGE = "en"
AWAIT_MS = 120_000
DOWNLOAD_MS = 900_000
KM_PER_DEG_LAT = 111.32

COLUMNS = ("file sight kind lat lon bearing pitch elevation sky labels "
           "width height await_ms command").split()


def initial_bearing(lat1, lon1, lat2, lon2):
    """Great-circle initial bearing in degrees, 0 = north, clockwise."""
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dlon = math.radians(lon2 - lon1)
    y = math.sin(dlon) * math.cos(p2)
    x = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dlon)
    return (math.degrees(math.atan2(y, x)) + 360.0) % 360.0


def ground_distance_m(lat1, lon1, lat2, lon2):
    """Haversine distance in metres."""
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def destination_point(lat, lon, bearing_deg, distance_m):
    """Where you arrive setting off from (lat, lon) on a bearing for distance_m."""
    r = 6371000.0
    d = distance_m / r
    b = math.radians(bearing_deg)
    p1, l1 = math.radians(lat), math.radians(lon)
    p2 = math.asin(math.sin(p1) * math.cos(d) + math.cos(p1) * math.sin(d) * math.cos(b))
    l2 = l1 + math.atan2(math.sin(b) * math.sin(d) * math.cos(p1),
                         math.cos(d) - math.sin(p1) * math.sin(p2))
    return round(math.degrees(p2), 5), round((math.degrees(l2) + 540) % 360 - 180, 5)


#: How far apart the subject and the backdrop should stand in the frame, in degrees.
#: NOT zero. Putting the camera exactly on the line through both peaks aligns them
#: perfectly - and a perfectly aligned backdrop is hidden BEHIND the subject, because
#: the subject is nearer and subtends more sky. The first attempt did exactly that:
#: Rainier from 35 km with Adams supposedly behind it showed no Adams at all, and the
#: peak labels in the frame were all local Rainier summits. Standing a little off the
#: line puts them side by side instead, both inside a frame about 31 degrees wide.
BACKDROP_SEPARATION_DEG = 8


def backdrop_viewpoint(slat, slon, blat, blon, distance_km, separation_deg):
    """Where to stand so subject and backdrop are separation_deg apart in the frame.

    Orbits the eye around the subject, away from the backdrop, and takes the offset
    whose resulting separation is closest to the one asked for. Returns the eye, the
    bearing that frames both, and the separation actually achieved.
    """
    away = initial_bearing(blat, blon, slat, slon)
    best = None
    for step in range(0, 161):                      # 0 to 80 degrees off the line
        alpha = step * 0.5
        elat, elon = destination_point(slat, slon, (away + alpha) % 360, distance_km * 1000)
        to_subject = initial_bearing(elat, elon, slat, slon)
        to_backdrop = initial_bearing(elat, elon, blat, blon)
        sep = abs((to_subject - to_backdrop + 180) % 360 - 180)
        score = abs(sep - separation_deg)
        if best is None or score < best[0]:
            # Look between the two so both are in frame, not at either one.
            middle = (to_backdrop + ((to_subject - to_backdrop + 180) % 360 - 180) / 2) % 360
            best = (score, elat, elon, round(middle), sep)
    return best[1], best[2], best[3], best[4]


#: Camera height for a backdrop shot: enough to clear whatever ridge the computed
#: viewpoint happens to land behind, growing with distance so the near ground does not
#: swallow the lower frame.
def backdrop_lift(distance_km):
    return int(min(5000, max(2000, 1500 + 60 * distance_km)) // 100 * 100)


#: How much of the exact summit aim to keep. Pointing dead at the summit centres it
#: and leaves the upper half of the frame empty sky; easing the tilt back lifts the
#: peak to roughly the upper third and fills the frame with the terrain leading to it.
AIM_PITCH_FRACTION = 0.65


def aim_at(eye_lat, eye_lon, eye_alt_m, tgt_lat, tgt_lon, tgt_alt_m):
    """The bearing and pitch that frame the target."""
    bearing = round(initial_bearing(eye_lat, eye_lon, tgt_lat, tgt_lon))
    dist = ground_distance_m(eye_lat, eye_lon, tgt_lat, tgt_lon)
    exact = math.degrees(math.atan2(tgt_alt_m - eye_alt_m, max(dist, 1.0)))
    return bearing, round(exact * AIM_PITCH_FRACTION)


def jar_path():
    libs = os.path.join(ROOT, "headless", "build", "libs")
    jars = sorted((os.path.join(libs, f) for f in os.listdir(libs)
                   if "headless" in f and f.endswith(".jar")),
                  key=os.path.getmtime) if os.path.isdir(libs) else []
    return jars[-1] if jars else None


def build_jar():
    subprocess.run([os.path.join(ROOT, "gradlew"), ":headless:renderJar", "-q",
                    f"-Dorg.gradle.java.home={JAVA_HOME}"], cwd=ROOT, check=True)


def standalone_command(lat, lon, sky, labels, sky_mode, constellations, shot):
    return (f'java -jar headless/build/libs/peaknav-headless-1.1.0.jar'
            f' --lat {lat} --lon {lon} --width {WIDTH} --height {HEIGHT}'
            f' --await {AWAIT_MS} --sky {sky} --sky-mode {sky_mode}'
            f' --constellations {constellations}'
            f' --horizon-compass off --language {LANGUAGE}'
            f' --coordinates off --labels "{labels}" --download {DOWNLOAD_MS}'
            f' --shot "{shot}"')


def rows():
    """Every image: (manifest row, boot key). Images sharing a boot key are
    rendered in one app boot."""
    # Parameters of the shots named in TOGGLE_STEMS, captured as they are generated so
    # the toggle matrix reuses them instead of keeping a second copy of the geometry.
    _toggle_base = {}

    for name, lat, lon, bearing, pitch, elevation, tlat, tlon in LOCATIONS:
        if tlat is None:
            tlat, tlon = lat, lon
        b_left = (int(bearing) + 330) % 360
        b_right = (int(bearing) + 30) % 360

        for sky in ("on", "off"):
            for peaks in ("on", "off"):
                for areas in ("on", "off"):
                    labels = ",".join(
                        ([PEAK_LABELS] if peaks == "on" else [])
                        + ([AREA_LABELS] if areas == "on" else []))
                    toggles = f"sky-{sky}.peaks-{peaks}.areas-{areas}"
                    boot = (lat, lon, sky, labels, "local", "on")
                    for b in (b_left, bearing, b_right):
                        for pose, p_pitch, p_elev in (
                                ("std", pitch, elevation), ("wide", -8, 0.9)):
                            f = f"{name}.{toggles}.b{b}.{pose}.png"
                            yield ([f, name, "matrix", lat, lon, b, p_pitch, p_elev,
                                    sky, labels or "(none)", WIDTH, HEIGHT, AWAIT_MS],
                                   boot, (b, p_pitch, p_elev))

        ring_labels = f"{PEAK_LABELS},{AREA_LABELS}"
        for d in RING_DISTANCES_KM:
            for az in RING_AZIMUTHS:
                rad = math.radians(az)
                vlat = round(tlat + (d / KM_PER_DEG_LAT) * math.cos(rad), 5)
                vlon = round(tlon + (d / (KM_PER_DEG_LAT
                                          * math.cos(math.radians(tlat)))) * math.sin(rad), 5)
                v_bearing = (az + 180) % 360
                up_pitch = max(2, round(36.0 / d))
                boot = (vlat, vlon, "on", ring_labels, "local", "on")
                for pose, p_pitch, p_elev in (
                        ("up", up_pitch, 0.2), ("level", -2, 0.75)):
                    f = f"{name}.ring.d{d:g}km.az{az:g}.{pose}.png"
                    yield ([f, name, "ring", vlat, vlon, v_bearing, p_pitch, p_elev,
                            "on", ring_labels, WIDTH, HEIGHT, AWAIT_MS],
                           boot, (v_bearing, p_pitch, p_elev))

    ring_labels = f"{PEAK_LABELS},{AREA_LABELS}"
    for (stem, elat, elon, ealt, tlat_a, tlon_a, talt, ebar) in AIMED:
        bearing, pitch = aim_at(elat, elon, ealt, tlat_a, tlon_a, talt)
        sight = stem.split(".")[0]
        f = stem + ".png"
        yield ([f, sight, "aimed", elat, elon, bearing, pitch, ebar,
                "on", ring_labels, WIDTH, HEIGHT, AWAIT_MS],
               (elat, elon, "on", ring_labels, "day", "off"), (bearing, pitch, ebar))

    for (stem, elat, elon, ealt, tlat_v, tlon_v, talt, lift, pitch) in VISTAS:
        bearing = round(initial_bearing(elat, elon, tlat_v, tlon_v))
        # A height in metres, passed through as metres: the renderer's --shot accepts
        # "<n>m" and calls setCameraElevationMeters. Before that existed this script
        # had to invert the bar's exp5In curve itself, and got it wrong.
        ebar = f"{lift}m"
        sight = stem.split(".")[0]
        f = stem + ".png"
        _toggle_base[stem] = (elat, elon, bearing, pitch, ebar, sight)
        yield ([f, sight, "vista", elat, elon, bearing, pitch, ebar,
                "on", VISTA_LABELS, WIDTH, HEIGHT, AWAIT_MS],
               (elat, elon, "on", VISTA_LABELS, "day", "off"), (bearing, pitch, ebar))
        if stem in VISTA_VARIANT_STEMS:
            for suffix, sky, with_peaks in VISTA_VARIANTS:
                labels = (VISTA_LABELS + "," + PEAK_LABELS) if with_peaks else VISTA_LABELS
                yield ([f"{stem}.{suffix}.png", sight, "vista", elat, elon, bearing,
                        pitch, ebar, sky, labels, WIDTH, HEIGHT, AWAIT_MS],
                       (elat, elon, sky, labels, "day", "off"), (bearing, pitch, ebar))

    for sight, subj_name, back_name, eye_base, distances, separation in BACKDROPS:
        slat, slon, salt = PEAKS[subj_name]
        blat, blon, _ = PEAKS[back_name]
        # Stand on the far side of the subject from the backdrop, along their line, so
        # that looking back at the subject looks straight through it to the backdrop.
        for d_km in distances:
            elat, elon, bearing, _sep = backdrop_viewpoint(
                slat, slon, blat, blon, d_km, separation)
            lift = backdrop_lift(d_km)
            angle = math.degrees(math.atan2(salt - (eye_base + lift), d_km * 1000))
            pitch = max(-8, min(4, round(angle) - 2))
            stem = f"{sight}.backdrop.{back_name}.d{d_km:g}km"
            _toggle_base[stem] = (elat, elon, bearing, pitch, f"{lift}m", sight)
            variants = [("", "on", False)]
            if sight in BACKDROP_PEAK_LABEL_SIGHTS:
                variants.append(("peaks-on", "on", True))
            for suffix, sky, with_peaks in variants:
                labels = (VISTA_LABELS + "," + PEAK_LABELS) if with_peaks else VISTA_LABELS
                fname = f"{stem}.{suffix}.png" if suffix else f"{stem}.png"
                yield ([fname, sight, "backdrop", elat, elon, bearing, pitch, f"{lift}m",
                        sky, labels, WIDTH, HEIGHT, AWAIT_MS],
                       (elat, elon, sky, labels, "day", "off"),
                       (bearing, pitch, f"{lift}m"))

    for stem in TOGGLE_STEMS:
        if stem not in _toggle_base:
            raise SystemExit(f"TOGGLE_STEMS names an unknown shot: {stem}")
        elat, elon, bearing, pitch, ebar, sight = _toggle_base[stem]
        for sky in ("on", "off"):
            for roads in ("on", "off"):
                for peaks in ("on", "off"):
                    for areas in ("on", "off"):
                        labels = ",".join(
                            ([PEAK_LABELS] if peaks == "on" else [])
                            + ([TOGGLE_AREA_LABELS] if areas == "on" else [])
                            + (["roads"] if roads == "on" else []))
                        f = (f"{stem}.toggles.sky-{sky}.roads-{roads}"
                             f".peaks-{peaks}.areas-{areas}.png")
                        yield ([f, sight, "toggles", elat, elon, bearing, pitch, ebar,
                                sky, labels or "(none)", WIDTH, HEIGHT, AWAIT_MS],
                               (elat, elon, sky, labels, "day", "off"),
                               (bearing, pitch, ebar))

    for stem, lat, lon, bearing, pitch, elevation in CURATED:
        sight = stem.split(".")[0]
        f = stem + ".png"
        yield ([f, sight, "curated", lat, lon, bearing, pitch, elevation,
                "on", ring_labels, WIDTH, HEIGHT, AWAIT_MS],
               (lat, lon, "on", ring_labels, "local", "on"), (bearing, pitch, elevation))


def write_manifest(all_rows):
    os.makedirs(IMAGES_DIR, exist_ok=True)
    with open(MANIFEST, "w", encoding="utf-8") as fout:
        fout.write("\t".join(COLUMNS) + "\n")
        for row, boot, shot in all_rows:
            lat, lon, sky, labels, sky_mode, constellations = boot
            shot_arg = f"{shot[0]},{shot[1]},{shot[2]},snapshots/images/{row[0]}"
            fout.write("\t".join(str(v) for v in row
                                 + [standalone_command(lat, lon, sky, labels,
                                                       sky_mode, constellations, shot_arg)])
                       + "\n")


def signature(row):
    """What a rendered image depends on: move any of these and it is out of date."""
    return [str(v) for v in (row[3], row[4], row[5], row[6], row[7],
                             row[8], row[9], row[10], row[11], LANGUAGE)]


def load_render_log():
    try:
        with open(RENDER_LOG, encoding="utf-8") as fin:
            return json.load(fin)
    except (OSError, ValueError):
        return {}


def save_render_log(log):
    with open(RENDER_LOG, "w", encoding="utf-8") as fout:
        json.dump(log, fout, indent=0, sort_keys=True)


def render(all_rows, overwrite, only):
    render_log = load_render_log()
    todo = []
    skipped = stale = 0
    for row, boot, shot in all_rows:
        fname = row[0]
        if only and not fnmatch.fnmatch(fname, only):
            continue
        if not overwrite and os.path.exists(os.path.join(IMAGES_DIR, fname)):
            recorded = render_log.get(fname)
            # No record means the image predates this bookkeeping: trust it rather than
            # re-render hundreds of good images for want of a note about them.
            if recorded is None or recorded == signature(row):
                skipped += 1
                continue
            stale += 1
        todo.append((row, boot, shot))
    if skipped:
        print(f"{skipped} image(s) already current - skipped")
    if stale:
        print(f"{stale} image(s) on disk no longer match their parameters - re-rendering")
    if not todo:
        print("nothing to render - all requested images exist")
        return 0

    if not os.environ.get("DISPLAY"):
        sys.exit("error: DISPLAY is not set; the renderer needs a session display "
                 "(the window stays hidden)")
    jar = jar_path()
    if jar is None:
        print("building the headless renderer jar...")
        build_jar()
        jar = jar_path()
    print(f"renderer: {jar}")

    # Group into boots.
    boots = {}
    for row, boot, shot in todo:
        boots.setdefault(boot, []).append((row[0], shot))

    print(f"{len(todo)} images in {len(boots)} boots")
    signatures = {row[0]: signature(row) for row, _, _ in todo}
    failed = 0
    for i, ((lat, lon, sky, labels, sky_mode, constellations), shots) in enumerate(boots.items(), 1):
        args = [os.path.join(JAVA_HOME, "bin", "java"), "-jar", jar,
                "--lat", str(lat), "--lon", str(lon),
                "--width", str(WIDTH), "--height", str(HEIGHT),
                "--await", str(AWAIT_MS), "--sky", sky,
                "--sky-mode", sky_mode, "--constellations", constellations,
                "--horizon-compass", "off", "--coordinates", "off",
                "--language", LANGUAGE,
                "--labels", labels, "--download", str(DOWNLOAD_MS)]
        for fname, (bearing, pitch, elevation) in shots:
            args += ["--shot", f"{bearing},{pitch},{elevation},"
                     + os.path.join(IMAGES_DIR, fname)]
        logfile = os.path.join(IMAGES_DIR, f"boot-{i:03d}.log")
        print(f"[{i}/{len(boots)}] ({lat}, {lon}) {len(shots)} shot(s)...", flush=True)
        with open(logfile, "w") as lf:
            result = subprocess.run(args, cwd=ROOT, stdout=lf, stderr=subprocess.STDOUT)
        if result.returncode == 0:
            os.remove(logfile)
            for fname, _ in shots:
                render_log[fname] = signatures[fname]
            save_render_log(render_log)
        else:
            failed += 1
            print(f"    FAILED (kept {logfile})", file=sys.stderr)
    print(f"done: {len(todo) - failed * 0} requested; {failed} boots failed"
          if failed else f"done: all {len(todo)} images rendered")
    return 1 if failed else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--overwrite-existing", action="store_true",
                        help="re-render images that already exist")
    parser.add_argument("--only", metavar="GLOB",
                        help="restrict to file names matching this glob")
    parser.add_argument("--manifest-only", action="store_true",
                        help="rewrite snapshots/manifest.tsv without rendering")
    args = parser.parse_args()

    all_rows = list(rows())
    write_manifest(all_rows)
    print(f"manifest: {MANIFEST} ({len(all_rows)} images listed)")
    if args.manifest_only:
        return 0
    return render(all_rows, args.overwrite_existing, args.only)


if __name__ == "__main__":
    sys.exit(main())
