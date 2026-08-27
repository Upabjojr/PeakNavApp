#!/usr/bin/env python3
"""Generates PeakNav videos into snapshots/videos/.

    python3 snapshots/generate_videos.py                 # render whatever is missing
    python3 snapshots/generate_videos.py --only 'rainier*'
    python3 snapshots/generate_videos.py --overwrite-existing
    python3 snapshots/generate_videos.py --frames 120    # a quick, coarse preview
    python3 snapshots/generate_videos.py --probe 3       # log what the API reports as
                                                          # loaded while rendering

Each video is one camera path, of one of two kinds:

  * an ORBIT circles a single subject at a constant radius and altitude, camera pointed
    at it throughout, so the subject stays put and the world turns behind it;
  * a FLIGHT follows a route through a whole region, camera looking along the direction
    of travel, so the country passes beneath.

The whole path is rendered from as few app boots as possible, using the renderer's
--frame option: start-up and the first tile load dominate the cost, so a full orbit is a
handful of boots rather than one per frame.

Frames are JPEG (--format jpg): about a tenth of the PNG for the same picture, and the
video codec re-encodes them anyway. They are kept in snapshots/videos/frames/<name>/ so
a run can resume, and so a single bad frame can be deleted and redone rather than the
whole clip.

Two things a still does not have to worry about:

  * Height must be ABSOLUTE (metres above sea level), not above the ground. A camera held
    at a fixed height above the terrain rides up over every ridge and sinks into every
    valley, and the mountain it is pointed at bobs in the frame.
  * Tiles must arrive before the shutter. Each frame waits for the view to go quiet,
    which is why this is an offline render at a few seconds a frame and not a screen
    recording.
"""

import argparse
import fnmatch
import importlib.util
import math
import os
import shutil
import subprocess
import sys
import threading
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VIDEOS_DIR = os.path.join(ROOT, "snapshots", "videos")
FRAMES_DIR = os.path.join(VIDEOS_DIR, "frames")
PROBE_DIR = os.path.join(VIDEOS_DIR, "probe")
JAVA_HOME = os.environ.get("PEAKNAV_JAVA_HOME", "/usr/lib/jvm/java-17-openjdk-amd64")

# The geodesy lives in the snapshot script; one copy of it, not two.
_spec = importlib.util.spec_from_file_location(
    "peaknav_snapshots", os.path.join(ROOT, "snapshots", "generate_snapshots.py"))
_snap = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_snap)
initial_bearing = _snap.initial_bearing
destination_point = _snap.destination_point
ground_distance_m = _snap.ground_distance_m

WIDTH, HEIGHT = 1600, 900
LANGUAGE = "en"

#: Frames in a full turn of an orbit. Against 30 fps this IS the rotation speed, and it
#: is the only place to set it. The history: 360 frames (30 deg/s) spun too fast to
#: look at anything; 540 (20 deg/s) read well but the camera still stepped ~230 m of
#: ring per frame, coarse enough to jitter on close terrain; 1080 gives ten degrees a
#: second, a ~115 m step, and a thirty-six-second revolution. Slowing down costs
#: render time linearly, but the wait-skipping (see LABEL_REFRESH_SECONDS) prices the
#: added frames at capture cost only, not the full wait ritual.
ORBIT_FRAMES = 1080

#: The instant every frame is lit by, UTC. Without this the Sun is placed from the wall
#: clock, so a video is lit by whenever it happened to be rendered - and since a video
#: takes several app boots over the better part of an hour, the light drifts across it.
#: One run was interrupted overnight and came back nine hours later: the two frames on
#: either side of the join differed by three times what neighbouring frames normally do,
#: a visible seam in the middle of the clip.
#:
#: Mid-morning in high summer, chosen for the light: the Sun high enough that the valleys
#: are not in shadow, far enough from noon that the relief still has some modelling. It is
#: a fixed instant rather than "now" so that re-rendering one bad frame next month still
#: matches the frames either side of it.
SKY_TIME = "2026-07-15T09:30:00Z"

#: Seconds of VIDEO between recomputations of which labels show. The camera moves
#: every frame, and the app's own triggers would reshuffle the labels several times a
#: second of output - watchable as flicker; measured on consecutive orbit frames, the
#: label band changed ~270,000 pixels per frame under the app's own triggers and
#: ~5,500 with this cadence. Half a second keeps labels honest about what is in view
#: while letting the eye actually read them. The script converts this to a per-video
#: frame cadence (fps varies by entry) and passes it as --label-refresh.
#:
#: The cadence buys speed as well as calm, through a contract implemented in the
#: renderer's --frame loop (RenderCli.renderFrames):
#:
#:   * On REFRESH frames (every fps/2-th frame, counted by the frame's number in the
#:     whole video so interrupted runs keep the same rhythm), the renderer does the
#:     full ritual: wait for tiles and satellite imagery to go quiet, recompute which
#:     labels show (area and spot labels both), settle, then capture.
#:   * On the frames BETWEEN refreshes, it skips the quiet-wait and the settle and
#:     just moves, aims and captures - PROVIDED the last wait actually reached quiet
#:     and the camera step is under a kilometre. One signal is NEVER skipped, on any
#:     frame: the renderer always waits until the move has fully landed - the
#:     target's ground elevation measured, the camera placed on it, current
#:     coordinates equal to the target's. All the other signals shape how a frame
#:     looks; that one decides where the frame is taken FROM, and skipping it would
#:     capture frames from wherever the previous target had left the camera. A video frame moves
#:     metres (orbits ~230 m, flights ~45 m), so the terrain is already resident; a
#:     chunk's first frame jumps from the boot position and takes the full wait
#:     automatically, as does every frame after a wait that timed out.
#:
#: The trade is explicit: between checkpoints a tile or texture upgrade may land
#: mid-frame instead of being waited out, and anything it disturbs is re-synced at
#: the next checkpoint - at most half a second of video away. In exchange the
#: waiting, which measured at 3.3 s of every 3.8 s frame before any of this, is paid
#: on one frame in fifteen rather than all of them.
LABEL_REFRESH_SECONDS = 0.5

# ---------------------------------------------------------------------------
# The flight routes. A route is a list of (latitude, longitude, altitude ASL)
# waypoints; the camera is flown along a smooth curve through them, not from
# one to the next in straight lines, and at constant ground speed rather than
# at constant time per leg - see path_frames.
#
# The waypoints are placed where the good things are, and are spaced widely:
# they steer the curve, so a waypoint dropped exactly on a summit makes the
# camera fly through it. Altitude is absolute, and is chosen to clear the
# highest ground on the route by roughly a kilometre - close enough that the
# peaks have presence, far enough that none of them fills the lens.
# ---------------------------------------------------------------------------

#: Monte Rosa to Mont Blanc, west along the main chain of the Pennine and Graian Alps -
#: the greatest concentration of 4000 m summits in Europe, a dozen of them within sight
#: of the route. Flown at 6000 m, which clears Mont Blanc's 4808 by twelve hundred metres.
ROUTE_WESTERN_ALPS = [
    (45.955, 8.080, 6000),   # in from the east, over the head of the Valsesia
    (45.937, 7.867, 6000),   # Monte Rosa - Dufourspitze, 4634
    (45.932, 7.750, 6000),   # Lyskamm, Castor and Pollux, the Breithorn
    (45.985, 7.640, 6000),   # passing north of the Matterhorn, 4478
    (46.010, 7.470, 6000),   # Dent d'Herens and the Dent Blanche
    (45.945, 7.300, 6000),   # the Grand Combin, 4314
    (45.895, 7.080, 6000),   # over the Valpelline, closing on the Mont Blanc massif
    (45.845, 6.900, 6000),   # Mont Blanc, 4808, with the Grandes Jorasses to the north
    (45.820, 6.760, 6000),   # out over the Beaufortain
]

#: The Dolomites, west to north-east: pale limestone towers standing clear of green
#: valleys, which is a different subject from the snow and rock of the western Alps and
#: reads better from closer in. Nothing here reaches 3350, so 4400 m is enough.
ROUTE_DOLOMITES = [
    (46.440, 11.520, 4400),  # in from the Adige valley, west of the Catinaccio
    (46.470, 11.610, 4400),  # Catinaccio / Rosengarten, 3002
    (46.515, 11.735, 4400),  # Sassolungo / Langkofel, 3181
    (46.510, 11.800, 4400),  # the Sella group
    # An extra waypoint between the Sella and Marmolada was tried, to spread the turn
    # over more ground; it tightened the curve onto Marmolada instead and made the swing
    # worse, 3.4 degrees a frame against 2.6. The turn is geography - the route has to
    # come off an eastward run onto a south-eastward one - and 2.6 is a banking turn
    # rather than a snap. Left alone.
    (46.450, 11.860, 4400),  # Marmolada, 3343, the highest of them
    (46.440, 11.980, 4400),  # Civetta and Pelmo away to the south
    (46.490, 12.100, 4400),  # the Tofane, above Cortina
    (46.560, 12.230, 4400),
    (46.620, 12.310, 4400),  # Tre Cime di Lavaredo, 2999
    (46.660, 12.370, 4400),  # out towards the Sesto valley
]

#: North through the Cyclades, from the Santorini caldera to Mykonos. These islands are
#: low - Mount Zas on Naxos, 1004 m, is the highest thing on the route - so the flight is
#: higher above the ground than the Alpine ones and looks further down: what there is to
#: see is the shape of the coasts and the water between them, not relief.
#: The line runs nearly straight north, up the channel between Paros and Naxos rather
#: than calling at each island in turn. Visiting them one by one meant doubling back at
#: Naxos to reach Paros, and the camera looks where it is going: that turn swung the view
#: through 109 degrees in a second, which is a whip pan, not a flight. Threading between
#: the two shows both, one off each wing, and the heading barely moves.
ROUTE_CYCLADES = [
    (36.330, 25.470, 3000),  # in from the south, over the open Aegean
    (36.400, 25.430, 3000),  # Thira - the drowned caldera of Santorini
    (36.560, 25.360, 3000),
    (36.720, 25.300, 3000),  # Ios
    (36.880, 25.330, 3000),
    (37.020, 25.330, 3000),  # the channel: Paros to the west, Naxos to the east
    (37.180, 25.300, 3000),
    (37.330, 25.280, 3000),
    (37.420, 25.310, 3000),  # Delos, and Mykonos beyond it
    (37.520, 25.400, 3000),  # out to the north
]

# ---------------------------------------------------------------------------
# The videos. An orbit circles the target at a constant radius and a constant
# altitude, with the camera pointed at it the whole way round, so the mountain
# stays put in the frame and the world turns behind it.
#
#   pitch is computed, not chosen: at a fixed radius and altitude the angle to
#   the summit never changes, so one value holds for the whole orbit.
#
# A flight instead follows one of the routes above, looking where it is going.
# Flights run longer than an orbit's 540 frames, and their counts have been
# doubled twice: 600 frames moved ~180 m each - close terrain sliding that far
# between frames reads as jumping, not flowing - and 1200 (~90 m) still jittered
# over the nearest ground. At 2400/2880 frames the step is ~45 m, the ground
# speed ~1.3 km/s, and a route takes 80-96 seconds of video. Doubling frames
# doubles render work per route; the wait-skipping described below is what makes
# that affordable.
# ---------------------------------------------------------------------------
VIDEOS = [
    {
        "name": "mount-rainier.orbit.peak-labels",
        "kind": "orbit",
        # Rainier's summit, and a ring wide enough to hold the whole mountain in
        # frame - the same 20 km that the good stills were taken from.
        "target": (46.8523, -121.7603, 4392),
        "radius_km": 20,
        "altitude_m": 5300,
        "sky": "on",
        "labels": "peaks,roads",
        "fps": 30,
        "frames": ORBIT_FRAMES,
    },
    {
        "name": "mount-rainier.orbit.no-labels",
        "kind": "orbit",
        "target": (46.8523, -121.7603, 4392),
        "radius_km": 20,
        "altitude_m": 5300,
        "sky": "on",
        # Highways stay on, as asked; what goes is every label pillar.
        "labels": "roads",
        "fps": 30,
        "frames": ORBIT_FRAMES,
    },
    {
        "name": "zermatt-matterhorn.orbit.peak-labels",
        "kind": "orbit",
        # The Matterhorn is a spike rather than a dome, so it is circled closer and from a
        # little above the summit: the profile changes completely around the ring, which is
        # the whole reason to orbit this one.
        "target": (45.9763, 7.6586, 4478),
        "radius_km": 12,
        "altitude_m": 5000,
        "sky": "on",
        "labels": "peaks,roads",
        "fps": 30,
        "frames": ORBIT_FRAMES,
    },
    {
        "name": "zermatt-matterhorn.orbit.no-labels",
        "kind": "orbit",
        "target": (45.9763, 7.6586, 4478),
        "radius_km": 12,
        "altitude_m": 5000,
        "sky": "on",
        "labels": "roads",
        "fps": 30,
        "frames": ORBIT_FRAMES,
    },
    {
        # Area labels without peak labels: the named ranges, lakes, towns and islands, which
        # sit still on their features instead of shuffling with the camera the way the peak
        # pillars do - so this is the variant that survives motion best.
        "name": "zermatt-matterhorn.orbit.area-labels",
        "kind": "orbit",
        "target": (45.9763, 7.6586, 4478),
        "radius_km": 12,
        "altitude_m": 5000,
        "sky": "on",
        "labels": "mountain_ranges,islands,lakes,cities,roads",
        "fps": 30,
        "frames": ORBIT_FRAMES,
    },
    {
        "name": "mount-rainier.orbit.area-labels",
        "kind": "orbit",
        "target": (46.8523, -121.7603, 4392),
        "radius_km": 20,
        "altitude_m": 5300,
        "sky": "on",
        "labels": "mountain_ranges,islands,lakes,cities,roads",
        "fps": 30,
        "frames": ORBIT_FRAMES,
    },
    {
        "name": "santorini-greece.orbit.no-labels",
        # Santorini is a drowned caldera about 12 km across: the ring of islands only reads
        # as a ring from above, so this orbits closer and higher-angled than a mountain does.
        "kind": "orbit",
        "target": (36.4000, 25.4300, 566),
        "radius_km": 20,
        "altitude_m": 3200,
        "sky": "on",
        "labels": "roads",
        "fps": 30,
        "frames": ORBIT_FRAMES,
    },
    {
        "name": "santorini-greece.orbit.peak-labels",
        "kind": "orbit",
        "target": (36.4000, 25.4300, 566),
        "radius_km": 20,
        "altitude_m": 3200,
        "sky": "on",
        "labels": "peaks,roads",
        "fps": 30,
        "frames": ORBIT_FRAMES,
    },
    {
        "name": "santorini-greece.orbit.area-labels",
        "kind": "orbit",
        "target": (36.4000, 25.4300, 566),
        "radius_km": 20,
        "altitude_m": 3200,
        "sky": "on",
        "labels": "mountain_ranges,islands,lakes,cities,roads",
        "fps": 30,
        "frames": ORBIT_FRAMES,
    },
    {
        "name": "hvar-croatia.orbit.no-labels",
        # Hvar is long and thin - 68 km by 10 - so the ring is wide enough to keep the whole
        # island in view as it turns, with Brac, Vis and Korcula coming round behind it.
        "kind": "orbit",
        "target": (43.1400, 16.6200, 628),
        "radius_km": 30,
        "altitude_m": 5000,
        "sky": "on",
        "labels": "roads",
        "fps": 30,
        "frames": ORBIT_FRAMES,
    },
    {
        "name": "hvar-croatia.orbit.peak-labels",
        "kind": "orbit",
        "target": (43.1400, 16.6200, 628),
        "radius_km": 30,
        "altitude_m": 5000,
        "sky": "on",
        "labels": "peaks,roads",
        "fps": 30,
        "frames": ORBIT_FRAMES,
    },
    {
        "name": "hvar-croatia.orbit.area-labels",
        "kind": "orbit",
        "target": (43.1400, 16.6200, 628),
        "radius_km": 30,
        "altitude_m": 5000,
        "sky": "on",
        "labels": "mountain_ranges,islands,lakes,cities,roads",
        "fps": 30,
        "frames": ORBIT_FRAMES,
    },
    {
        "name": "yosemite-valley.orbit.no-labels",
        # Yosemite Valley is a trench about 11 km long with walls a kilometre high. The
        # first cut orbited at 3500 m aiming at the valley floor (1200 m): from 18 km
        # out that plunges the view into the trench and fills the frame with the near
        # rim. Raised and aimed at rim height instead - 2400 m, about Glacier Point -
        # the walls, Half Dome and the high country beyond all stay in frame while the
        # floor still shows.
        "kind": "orbit",
        "target": (37.7450, -119.5900, 2400),
        "radius_km": 18,
        "altitude_m": 4300,
        "sky": "on",
        "labels": "roads",
        "fps": 30,
        "frames": ORBIT_FRAMES,
    },
    {
        "name": "yosemite-valley.orbit.peak-labels",
        "kind": "orbit",
        "target": (37.7450, -119.5900, 2400),
        "radius_km": 18,
        "altitude_m": 4300,
        "sky": "on",
        "labels": "peaks,roads",
        "fps": 30,
        "frames": ORBIT_FRAMES,
    },
    {
        "name": "yosemite-valley.orbit.area-labels",
        "kind": "orbit",
        "target": (37.7450, -119.5900, 2400),
        "radius_km": 18,
        "altitude_m": 4300,
        "sky": "on",
        "labels": "mountain_ranges,islands,lakes,cities,roads",
        "fps": 30,
        "frames": ORBIT_FRAMES,
    },
    {
        "name": "western-alps.flight.no-labels",
        "kind": "path",
        "route": ROUTE_WESTERN_ALPS,
        # Sixteen degrees down, settled by rendering the same place at -7, -12, -16 and
        # -20 and looking at the four.
        #
        # The Alps are thick with named summits, and at 6000 m the horizon is 270 km
        # away, so a shallow angle fills the top third of the frame with a solid band of
        # overlapping label pillars - unreadable, and it buries the mountains. Pitching
        # down cuts the horizon distance and thins the labels to the point where they can
        # be read. At -20 the sky is gone altogether and it reads like a map; -16 keeps a
        # strip of sky, the ridge line, and legible labels.
        "pitch_deg": -16,
        "sky": "on",
        "labels": "roads",
        "fps": 30,
        "frames": 2400,
    },
    {
        "name": "western-alps.flight.peak-labels",
        "kind": "path",
        "route": ROUTE_WESTERN_ALPS,
        "pitch_deg": -16,
        "sky": "on",
        "labels": "peaks,roads",
        "fps": 30,
        "frames": 2400,
    },
    {
        "name": "western-alps.flight.area-labels",
        "kind": "path",
        "route": ROUTE_WESTERN_ALPS,
        "pitch_deg": -16,
        "sky": "on",
        "labels": "mountain_ranges,islands,lakes,cities,roads",
        "fps": 30,
        "frames": 2400,
    },
    {
        "name": "dolomites.flight.no-labels",
        "kind": "path",
        "route": ROUTE_DOLOMITES,
        "pitch_deg": -16,
        "sky": "on",
        "labels": "roads",
        "fps": 30,
        "frames": 2400,
    },
    {
        "name": "dolomites.flight.peak-labels",
        "kind": "path",
        "route": ROUTE_DOLOMITES,
        "pitch_deg": -16,
        "sky": "on",
        "labels": "peaks,roads",
        "fps": 30,
        "frames": 2400,
    },
    {
        "name": "dolomites.flight.area-labels",
        "kind": "path",
        "route": ROUTE_DOLOMITES,
        "pitch_deg": -16,
        "sky": "on",
        "labels": "mountain_ranges,islands,lakes,cities,roads",
        "fps": 30,
        "frames": 2400,
    },
    {
        "name": "cyclades.flight.no-labels",
        "kind": "path",
        "route": ROUTE_CYCLADES,
        # Ten degrees down rather than seven: over water the interest is all below the
        # camera, and a flatter angle would fill half the frame with empty Aegean haze.
        "pitch_deg": -10,
        "sky": "on",
        "labels": "roads",
        "fps": 30,
        "frames": 2880,
    },
    {
        "name": "cyclades.flight.peak-labels",
        "kind": "path",
        "route": ROUTE_CYCLADES,
        "pitch_deg": -10,
        "sky": "on",
        "labels": "peaks,roads",
        "fps": 30,
        "frames": 2880,
    },
    {
        "name": "cyclades.flight.area-labels",
        "kind": "path",
        "route": ROUTE_CYCLADES,
        # The variant this route is really for: the islands are what the video is about,
        # and their names sit still on them while the camera moves.
        "pitch_deg": -10,
        "sky": "on",
        "labels": "mountain_ranges,islands,lakes,cities,roads",
        "fps": 30,
        "frames": 2880,
    },
]

#: How far below the exact line to the summit the camera looks. The subject sits a little
#: above the middle of the frame with the land it stands on below it, rather than dead
#: centre with half the frame empty sky - the same correction the stills use.
PITCH_DROP_DEG = 2

#: Frames per app boot. A boot does not survive an unbounded number of them: two runs died
#: without an exception, one at 216 frames and one at 219, the log simply stopping mid-work,
#: which is what an outside kill looks like rather than a crash. Something grows with each
#: frame - most likely native texture memory, since every frame moves the camera and streams
#: fresh tiles. Splitting the path across boots side-steps it entirely: a boot costs start-up
#: plus one tile load, which against 120 frames is noise, and each chunk resumes from the
#: frames already on disk.
FRAMES_PER_BOOT = 120


def orbit_frames(video, frame_count):
    """Every frame of an orbit: (lat, lon, bearing, pitch, altitude).

    The aim is computed in the RENDERER'S OWN flat frame - x is longitude scaled by
    the cosine of the frame's reference latitude (the landed eye) - and at full
    precision. It used to be the geodesic initial bearing rounded to 0.01 degrees;
    the quantisation stepped the subject a third of a pixel at irregular frames and
    the geodesic-vs-planar mismatch drifted it a little more, which together showed
    as the subject jiggling left-right instead of sitting pinned while the world
    turns. Measured on rendered frames: +-1 px irregular stepping before, subject
    steady after.
    """
    tlat, tlon, talt = video["target"]
    radius_m = video["radius_km"] * 1000
    altitude = video["altitude_m"]
    out = []
    for i in range(frame_count):
        azimuth = 360.0 * i / frame_count
        elat, elon = destination_point(tlat, tlon, azimuth, radius_m)
        # The renderer yaws in its flat world frame; aim exactly there, exactly at
        # the summit, no rounding anywhere in the chain.
        k = math.cos(math.radians(elat))
        bearing = math.degrees(math.atan2((tlon - elon) * k, tlat - elat)) % 360.0
        dist = ground_distance_m(elat, elon, tlat, tlon)
        angle = math.degrees(math.atan2(talt - altitude, max(dist, 1.0)))
        out.append((elat, elon, bearing, angle - PITCH_DROP_DEG, altitude))
    return out


#: Points measured along each leg of a route before the curve is resampled at constant
#: speed. This must be DENSE relative to the frame step: frames are placed by linear
#: interpolation between these samples, so the direction of travel is constant within a
#: segment and kinks at its ends. At 200 per leg the segments were 50-100 m against
#: 45 m frame steps - a heading kink almost every frame, watchable as left-right
#: micro-zigzag. At 4000 the segments are a few metres, the kinks fractions of a pixel,
#: and the flight reads as one continuous motion. Still just arithmetic; generation
#: stays instant.
PATH_SUBSAMPLES = 4000

#: How many frames either side are averaged into a flight's bearing. The curve's own
#: tangent is already continuous, so this is not fixing a kink; it takes the last of the
#: swing out of the sharper turns, where a frame-to-frame tangent still reads as a flick
#: of the head.
BEARING_SMOOTHING = 5


def _catmull_rom(p0, p1, p2, p3, t):
    """The point at t in 0..1 on the Catmull-Rom curve through p1 and p2.

    Chosen because it passes THROUGH its control points rather than merely being pulled
    towards them, which is what lets a waypoint mean "fly over this" instead of "lean
    vaguely this way", while still arriving with a continuous tangent - the camera has no
    corners to turn.
    """
    t2, t3 = t * t, t * t * t
    return tuple(
        0.5 * ((2 * a1)
               + (-a0 + a2) * t
               + (2 * a0 - 5 * a1 + 4 * a2 - a3) * t2
               + (-a0 + 3 * a1 - 3 * a2 + a3) * t3)
        for a0, a1, a2, a3 in zip(p0, p1, p2, p3))


def _spline_samples(route):
    """Dense (lat, lon, altitude) samples along a smooth curve through the waypoints."""
    # A degree of longitude is shorter than a degree of latitude by this much, and only
    # by fitting the curve in ground units does it bend the way it looks on a map. The
    # routes are a couple of degrees wide, so one factor for the whole route is plenty.
    mid = sum(p[0] for p in route) / len(route)
    k = math.cos(math.radians(mid))
    pts = [(p[0], p[1] * k, p[2]) for p in route]
    # A Catmull-Rom segment needs a neighbour on each side; duplicating the end points
    # gives the first and last segments one, so the curve starts and ends exactly on the
    # first and last waypoints rather than short of them.
    pts = [pts[0]] + pts + [pts[-1]]

    out = []
    for i in range(len(pts) - 3):
        for s in range(PATH_SUBSAMPLES):
            lat, lon_scaled, alt = _catmull_rom(
                pts[i], pts[i + 1], pts[i + 2], pts[i + 3], s / PATH_SUBSAMPLES)
            out.append((lat, lon_scaled / k, alt))
    out.append(route[-1])
    return out


def path_frames(video, frame_count):
    """Every frame of a flight: (lat, lon, bearing, pitch, altitude)."""
    samples = _spline_samples(video["route"])
    pitch = video.get("pitch_deg", -7)

    # Distance along the curve, so that frames can be spaced by ground covered rather
    # than by curve parameter. Without this the camera sprints through the widely spaced
    # waypoints and crawls through the close ones, since each leg gets the same share of
    # the frames however long it is.
    cumulative = [0.0]
    for a, b in zip(samples, samples[1:]):
        cumulative.append(cumulative[-1] + ground_distance_m(a[0], a[1], b[0], b[1]))
    total = cumulative[-1]

    positions = []
    j = 0
    for f in range(frame_count):
        want = total * f / max(frame_count - 1, 1)
        while j < len(cumulative) - 2 and cumulative[j + 1] < want:
            j += 1
        span = cumulative[j + 1] - cumulative[j]
        u = 0.0 if span <= 0 else (want - cumulative[j]) / span
        a, b = samples[j], samples[j + 1]
        positions.append((a[0] + (b[0] - a[0]) * u,
                          a[1] + (b[1] - a[1]) * u,
                          a[2] + (b[2] - a[2]) * u))

    # Look where you are going: the bearing from the previous frame's place to the next,
    # which is centred on this one and so does not lag the turn the way a
    # here-to-next bearing does.
    raw = []
    for f in range(len(positions)):
        before = positions[max(f - 1, 0)]
        after = positions[min(f + 1, len(positions) - 1)]
        raw.append(initial_bearing(before[0], before[1], after[0], after[1]))

    # Undo the wrap at north before averaging: 359 and 1 degrees average to 180 - due
    # south, the opposite of the truth - unless the sequence is made continuous first.
    unwrapped = [raw[0]]
    for bearing in raw[1:]:
        previous = unwrapped[-1]
        unwrapped.append(previous + ((bearing - previous + 180) % 360) - 180)

    half = BEARING_SMOOTHING // 2
    out = []
    for f, (lat, lon, alt) in enumerate(positions):
        lo, hi = max(0, f - half), min(len(unwrapped), f + half + 1)
        bearing = sum(unwrapped[lo:hi]) / (hi - lo)
        out.append((round(lat, 6), round(lon, 6), round(bearing % 360, 4),
                    round(pitch, 2), round(alt)))
    return out


def frames_for(video, frame_count):
    """The camera placements for one video, whichever kind it is."""
    if video["kind"] == "orbit":
        return orbit_frames(video, frame_count)
    if video["kind"] == "path":
        return path_frames(video, frame_count)
    sys.exit(f"error: {video['name']}: unknown kind {video['kind']!r}")


def jar_path():
    libs = os.path.join(ROOT, "headless", "build", "libs")
    jars = sorted((os.path.join(libs, f) for f in os.listdir(libs)
                   if "headless" in f and f.endswith(".jar")),
                  key=os.path.getmtime) if os.path.isdir(libs) else []
    return jars[-1] if jars else None


def generation_stamp():
    """What must be IDENTICAL for two frames to belong in the same video.

    The frame bank makes resuming cheap, and that cheapness bit hard once: frames
    survived across renderer rebuilds during a day of fixes, and the encoded videos
    stitched together frames from broken and fixed jars - stale camera positions
    beside correct ones, watchable as the picture jumping between places. A bank may
    only be resumed by the exact renderer generation that started it: the jar's bytes
    and every flag that shapes a frame. Anything else wipes the bank and starts the
    video clean - re-rendering is hours, shipping a spliced video is worse.
    """
    import hashlib
    h = hashlib.sha256()
    with open(jar_path(), "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    flags = f"{SKY_TIME}|{LABEL_REFRESH_SECONDS}|{WIDTH}x{HEIGHT}|{LANGUAGE}"
    return h.hexdigest()[:16] + "|" + flags


def geometry_source(kind):
    """The source text of the functions that place this kind's camera.

    Part of the per-video generation stamp: editing the camera MATHS (as opposed to a
    video's numbers) must invalidate exactly the banks it invalidates - an orbit-aim
    fix once left banks resumable because only a function body had changed, and the
    stamp of dictionaries and jar bytes could not see it.
    """
    import inspect
    if kind == "orbit":
        funcs = (orbit_frames, destination_point, ground_distance_m)
    else:
        funcs = (path_frames, _spline_samples, _catmull_rom,
                 initial_bearing, destination_point, ground_distance_m)
    return "".join(inspect.getsource(f) for f in funcs)


def render_frames(video, frames, frame_dir, probe_every=None):
    """Renders every missing frame of one video, in as many boots as it takes."""
    os.makedirs(frame_dir, exist_ok=True)
    import hashlib as _h
    stamp = (generation_stamp() + "|" + repr(sorted(
        (k, v) for k, v in video.items() if k != "name"))
        + "|" + _h.sha256(geometry_source(video["kind"]).encode()).hexdigest()[:12])
    stamp_file = os.path.join(frame_dir, "GENERATION")
    on_disk = None
    if os.path.exists(stamp_file):
        with open(stamp_file) as f:
            on_disk = f.read().strip()
    if on_disk != stamp:
        if on_disk is not None:
            print(f"  generation changed; discarding {len(os.listdir(frame_dir))-1} banked files")
        for name in os.listdir(frame_dir):
            os.remove(os.path.join(frame_dir, name))
        with open(stamp_file, "w") as f:
            f.write(stamp)
    missing = [(i, f) for i, f in enumerate(frames)
               if not os.path.exists(os.path.join(frame_dir, f"f{i:05d}.jpg"))]
    if not missing:
        print(f"  all {len(frames)} frames already rendered")
        return True

    chunks = [missing[i:i + FRAMES_PER_BOOT]
              for i in range(0, len(missing), FRAMES_PER_BOOT)]
    print(f"  {len(missing)} of {len(frames)} frames to render, "
          f"in {len(chunks)} boot(s) of up to {FRAMES_PER_BOOT}")
    for n, chunk in enumerate(chunks, 1):
        print(f"  boot {n}/{len(chunks)}: frames {chunk[0][0]}-{chunk[-1][0]}", flush=True)
        if not render_chunk(video, chunk, frame_dir, probe_every, n):
            return False
    return True


def boot_position(video, missing):
    """Where the app should start up for this chunk of frames.

    An orbit boots at its target, so the first download covers the mountain every frame
    is going to look at. A flight has no such point - by the last frame the target would
    be a hundred kilometres behind - so it boots where the chunk begins and streams the
    route from there.
    """
    if video["kind"] == "orbit":
        return video["target"][0], video["target"][1]
    first = missing[0][1]
    return first[0], first[1]


PROBE_COLUMNS = ["utc", "boot", "frames_done", "loading", "areasLoaded", "roadWork",
                 "textureJoins", "peaks", "peaks_drawn", "peaks_unnamed", "huts",
                 "huts_drawn", "places", "places_drawn", "pistes", "pistes_drawn",
                 "areas", "areas_drawn", "areas_unnamed", "drawn_names"]


def _probe_row(base_url, boot, frames_done):
    """One sample of what the renderer says it has loaded: /status plus /objects, reduced
    to counts per kind. The names actually on the picture are kept too, so a run can be
    read afterwards for WHICH labels came and went, not just how many."""
    import json as _json
    with urllib.request.urlopen(base_url + "/status", timeout=30) as r:
        status = _json.loads(r.read().decode("utf-8"))
    with urllib.request.urlopen(base_url + "/objects?scope=displayable", timeout=60) as r:
        objects = _json.loads(r.read().decode("utf-8"))["objects"]
    row = {"utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
           "boot": boot, "frames_done": frames_done,
           "loading": status.get("loading"), "areasLoaded": status.get("areasLoaded"),
           "roadWork": status.get("roadWork"), "textureJoins": status.get("textureJoins")}
    for kind, column in (("peak", "peaks"), ("alpine_hut", "huts"), ("place", "places"),
                         ("piste", "pistes"), ("area", "areas")):
        of_kind = [o for o in objects if o["kind"] == kind]
        row[column] = len(of_kind)
        row[column + "_drawn"] = sum(1 for o in of_kind if o.get("drawn"))
        if column in ("peaks", "areas"):
            row[column + "_unnamed"] = sum(1 for o in of_kind if not o.get("name"))
    row["drawn_names"] = "|".join(sorted(o.get("name") or "?" for o in objects
                                         if o.get("drawn")))
    return row


def _probe_loop(log, out_path, boot, missing, frame_dir, every, stop):
    """The probe thread: finds the renderer's port in its log, then samples until the
    boot ends. Errors are written into the table rather than raised - the probe is an
    observer, and an observer that can kill the render is no use."""
    base_url = None
    deadline = time.time() + 900
    while base_url is None and not stop.is_set() and time.time() < deadline:
        try:
            with open(log) as f:
                for line in f:
                    if line.startswith("PEAKNAV_SERVE port="):
                        base_url = "http://127.0.0.1:%d" % int(line.strip().split("=", 1)[1])
                        break
        except OSError:
            pass
        if base_url is None:
            stop.wait(1.0)
    if base_url is None:
        return
    new_file = not os.path.exists(out_path)
    with open(out_path, "a") as out:
        if new_file:
            out.write("\t".join(PROBE_COLUMNS) + "\n")
        while not stop.is_set():
            done = sum(1 for i, _ in missing
                       if os.path.exists(os.path.join(frame_dir, f"f{i:05d}.jpg")))
            try:
                row = _probe_row(base_url, boot, done)
                out.write("\t".join(str(row.get(c, "")) for c in PROBE_COLUMNS) + "\n")
            except Exception as e:  # noqa: BLE001 - observer, see above
                out.write("\t".join([time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                                     str(boot), str(done), "error: " + str(e).replace("\n", " ")])
                          + "\n")
            out.flush()
            stop.wait(every)


def render_chunk(video, missing, frame_dir, probe_every=None, boot=0):
    """One app boot, rendering the frames it is given.

    With probe_every set, the renderer also serves its REST API for the duration of the
    boot and a thread samples /status and /objects every probe_every seconds into
    snapshots/videos/probe/<video>.tsv - a record of what the app reported as loaded and
    drawn while the frames were being taken, for checking that labels keep pace with the
    camera. The samples are taken between frames on the render thread and change nothing;
    the frames are the same with or without the probe.
    """
    blat, blon = boot_position(video, missing)
    # The heap is capped because by default the JVM offers itself a quarter of the
    # machine - 7.9 GB here - and two workers plus a Gradle build put the whole box
    # under memory pressure; the entire worker tree died silently once, mid-boot, in
    # exactly the way an OOM group-kill looks. 4 GB is roomy for a 1600x900 render:
    # the observed working set is under 3.5 GB with the tile caches full.
    args = [os.path.join(JAVA_HOME, "bin", "java"), "-Xmx4g", "-jar", jar_path(),
            "--lat", str(blat), "--lon", str(blon),
            "--width", str(WIDTH), "--height", str(HEIGHT),
            "--await", "120000", "--download", "900000",
            "--sky", video["sky"], "--sky-mode", "day",
            # Nothing lettered or ruled across the sky. Every one of these is stated rather
            # than left to the stored preferences: headless reads the app's settings and only
            # keeps its changes in memory, so an option left unset would follow whatever the
            # desktop app happened to be set to, and a video would differ by machine.
            "--constellations", "off", "--star-names", "off",
            "--sky-grid", "off", "--ecliptic", "off",
            # The master switch over everything written on the sky - constellation, star and
            # planet names, and the Sun and Moon. The three flags above are kept as well: they
            # are what a reader of this command would look for, and they cost nothing.
            "--sky-labels", "off",
            "--sky-time", SKY_TIME,
            # Pinning the time makes the app show a date-and-time pill, which is right on
            # screen - it is the only sign the sky is not the live one - and wrong in a video.
            "--sky-time-label", "off",
            # Consecutive frames are a fraction of a degree apart, so nearly every tile is
            # already resident when the shutter waits. The defaults (2000 quiet, 1200 settle)
            # are sized for a lone still after a long jump and spent 3.3 s of every 3.8 s
            # frame waiting for nothing - measured, and the frames were pixel-compared at both
            # settings before trusting the short ones. The guard against capturing a
            # half-loaded view is unchanged: quiet time only starts counting once nothing is
            # actively loading.
            "--frame-quiet", "400", "--frame-settle", "250",
            # Which labels show is recomputed only this often (in frames of THIS video's
            # rate) - the anti-flicker cadence; see LABEL_REFRESH_SECONDS.
            "--label-refresh", str(max(1, int(video["fps"] * LABEL_REFRESH_SECONDS))),
            "--horizon-compass", "off", "--coordinates", "off", "--corner-compass", "off",
            "--language", LANGUAGE, "--format", "jpg",
            "--labels", video["labels"]]
    if probe_every:
        args += ["--serve", "0"]
    for i, (lat, lon, bearing, pitch, altitude) in missing:
        args += ["--frame", f"{lat},{lon},{bearing},{pitch},{altitude}asl,"
                 + os.path.join(frame_dir, f"f{i:05d}.jpg")]

    log = os.path.join(frame_dir, "render.log")
    stop = threading.Event()
    probe = None
    if probe_every:
        os.makedirs(PROBE_DIR, exist_ok=True)
        out_path = os.path.join(PROBE_DIR, video["name"] + ".tsv")
        probe = threading.Thread(target=_probe_loop, name="probe", daemon=True,
                                 args=(log, out_path, boot, missing, frame_dir,
                                       probe_every, stop))
    with open(log, "w") as lf:
        proc = subprocess.Popen(args, cwd=ROOT, stdout=lf, stderr=subprocess.STDOUT)
        if probe:
            probe.start()
        proc.wait()
    stop.set()
    if probe:
        probe.join(timeout=120)
    written = sum(1 for i, _ in missing
                  if os.path.exists(os.path.join(frame_dir, f"f{i:05d}.jpg")))
    if proc.returncode != 0 or written < len(missing):
        print(f"  boot ended early: {written}/{len(missing)} frames "
              f"(exit {proc.returncode}); kept {log}", file=sys.stderr)
        return False
    os.remove(log)
    return True


def encode(video, frame_dir, output):
    """Frames to an H.264 mp4 that plays anywhere."""
    if shutil.which("ffmpeg") is None:
        sys.exit("error: ffmpeg is not installed; the frames are rendered, "
                 "encode them yourself from " + frame_dir)
    cmd = ["ffmpeg", "-y", "-loglevel", "error",
           "-framerate", str(video["fps"]),
           "-i", os.path.join(frame_dir, "f%05d.jpg"),
           "-c:v", "libx264", "-preset", "slow", "-crf", "18",
           # yuv420p, or the file will not play in browsers and most players.
           "-pix_fmt", "yuv420p",
           output]
    subprocess.run(cmd, check=True)
    size = os.path.getsize(output) / 1e6
    seconds = video["frames"] / video["fps"]
    print(f"  wrote {output} ({seconds:.1f}s, {size:.1f} MB)")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--only", metavar="GLOB", help="restrict to matching video names")
    parser.add_argument("--workers", type=int, default=1, metavar="N",
                        help="render N videos at once, each in its own renderer process. "
                             "Safe because no two workers ever touch the same video - the "
                             "frame directories are disjoint by construction - and the "
                             "downloaded map data is shared through a database and tile "
                             "files built to take concurrent writers.")
    parser.add_argument("--worker-slice", type=int, metavar="K", help=argparse.SUPPRESS)
    parser.add_argument("--overwrite-existing", action="store_true",
                        help="re-encode videos that already exist (frames are still reused; "
                             "delete a frame to have it re-rendered)")
    parser.add_argument("--frames", type=int, metavar="N",
                        help="override the frame count, for a quick coarse preview")
    parser.add_argument("--probe", type=float, metavar="SECONDS",
                        help="while rendering, sample the renderer's REST API (/status and "
                             "/objects) every SECONDS into snapshots/videos/probe/<name>.tsv "
                             "- what it reported as loaded and drawn, frame by frame")
    args = parser.parse_args()

    if not os.environ.get("DISPLAY"):
        sys.exit("error: DISPLAY is not set; the renderer needs a session display "
                 "(the window stays hidden)")
    if jar_path() is None:
        sys.exit("error: no headless jar; run ./gradlew :headless:renderJar")

    os.makedirs(VIDEOS_DIR, exist_ok=True)

    # More than one worker: this process becomes the foreman. Each worker is this same
    # script, told which slice of the table is its own - video i belongs to worker
    # i mod N - so no two workers ever render, or encode, the same video. The videos are
    # dealt round-robin rather than in blocks so every worker gets a mix of long flights
    # and short orbits instead of one worker drawing all the long ones.
    if args.workers > 1 and args.worker_slice is None:
        procs = []
        for k in range(args.workers):
            cmd = [sys.executable, os.path.abspath(__file__),
                   "--workers", str(args.workers), "--worker-slice", str(k)]
            if args.only:
                cmd += ["--only", args.only]
            if args.overwrite_existing:
                cmd += ["--overwrite-existing"]
            if args.frames:
                cmd += ["--frames", str(args.frames)]
            if args.probe:
                cmd += ["--probe", str(args.probe)]
            procs.append(subprocess.Popen(cmd))
        codes = [p.wait() for p in procs]
        return 1 if any(codes) else 0

    failed = 0
    for index, video in enumerate(VIDEOS):
        if args.worker_slice is not None and index % args.workers != args.worker_slice:
            continue
        if args.only and not fnmatch.fnmatch(video["name"], args.only):
            continue
        count = args.frames or video["frames"]
        video = dict(video, frames=count)
        output = os.path.join(VIDEOS_DIR, video["name"] + ".mp4")
        if os.path.exists(output) and not args.overwrite_existing:
            print(f"{video['name']}: already made")
            continue

        print(f"{video['name']}: {count} frames at {video['fps']} fps "
              f"({count / video['fps']:.1f}s), labels [{video['labels']}]")
        frames = frames_for(video, count)
        # The frame count is part of the directory: frame 1 of a 360-frame orbit is one
        # degree round, and frame 1 of a 12-frame preview is thirty. Sharing a directory
        # between the two would resume a run with frames from a different orbit.
        frame_dir = os.path.join(FRAMES_DIR, f"{video['name']}.{count}f")
        if not render_frames(video, frames, frame_dir, args.probe):
            failed += 1
            continue
        encode(video, frame_dir, output)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
