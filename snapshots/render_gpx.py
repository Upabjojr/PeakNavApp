#!/usr/bin/env python3
"""Renders a GPX track as a video, flown the way the app's own GPX tour flies it.

    python3 snapshots/render_gpx.py track.gpx                    # -> snapshots/videos/gpx/track.mp4
    python3 snapshots/render_gpx.py track.gpx --name grigne --fly-seconds 60
    python3 snapshots/render_gpx.py track.gpx --fps 2            # a quick, coarse preview
    python3 snapshots/render_gpx.py track.gpx --probe 3          # log what the API reports as loaded
    python3 snapshots/render_gpx.py track.gpx --vertical         # 9:16 for Reels / Stories

The camera plan is a port of MapViewerScreen.startGpxFlythrough, the tour the app plays
when you press the fly button on a loaded track, and the numbers are its numbers:

  * the track is resampled to even spacing along its length and low-pass filtered (twice,
    at two densities), so the camera flies the trend of the route at constant speed and the
    receiver's zig-zag is averaged away;
  * the camera rides above and behind the smoothed path - height and setback scaled from
    the track's extent, within the app's bounds - with a heading measured over a long
    baseline, and aims at a point further along the route so it leads into curves;
  * at the end it makes one decelerating orbit of the finish, keeping it centred;
  * the lens is the tour's 62 degrees, so the mountains either side of the track stay in
    shot, and the track itself is drawn on the terrain.

What differs is only what a video needs and a phone does not: the frames are captured off
screen at a fixed rate (1080p30 by default, what YouTube and most players expect), the
ease-in from the previous view is dropped (there is no previous view), the tour's 22
seconds can be stretched with --fly-seconds, and labels are refreshed on the videos' usual
half-second cadence so they do not flicker.

Frames are banked in snapshots/videos/gpx/frames/<name>/ so an interrupted run resumes;
--overwrite starts over. A "peaknav.com" watermark is drawn in the bottom-right corner at
encode time (--no-watermark leaves it off), so the banked frames themselves stay clean, and
a soundtrack (Kevin MacLeod's "Isolated", CC BY - credit line in snapshots/music/README.md)
is laid under the picture, trimmed and faded to its length (--music for another file,
--no-music for silence).
"""

import argparse
import importlib.util
import math
import os
import shutil
import subprocess
import sys
import threading
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "snapshots", "videos", "gpx")

# The renderer plumbing - jar discovery, sky time, label cadence, the API probe - is the
# snapshot videos'; one copy of it.
_spec = importlib.util.spec_from_file_location(
    "peaknav_videos", os.path.join(ROOT, "snapshots", "generate_videos.py"))
_gv = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_gv)

# --- The app's tour constants (MapViewerScreen), verbatim -------------------------------
TOUR_HEIGHT_FRACTION = 0.45     # of the track's bounding span
TOUR_HEIGHT_MIN_M = 2600.0
TOUR_HEIGHT_MAX_M = 8000.0
TOUR_BACK_FRACTION = 0.65
TOUR_BACK_MIN_M = 4000.0
TOUR_BACK_MAX_M = 12000.0
TOUR_FIELD_OF_VIEW = 62.0
#: The lens for a portrait (Reel / Story, 9:16) render. The tour's 62 degrees is a
#: VERTICAL field of view; turned on its side it leaves only ~37 degrees across, a keyhole
#: with the mountains either side of the track cut off. 80 gives ~50 across - still
#: narrower than the landscape's ~94, which no rectilinear portrait lens can match, but
#: enough to keep the ridges in shot without the corners stretching.
VERTICAL_FIELD_OF_VIEW = 80.0
#: Portrait output, and where Instagram's own overlays sit on it: the top ~13% (title,
#: camera) and bottom ~20% (caption, account) of a Reel are covered, as is the right
#: edge (like/comment column). The watermark goes to the left edge just above the
#: caption band: the top of a portrait frame is the label band - the peak pillars fill
#: it - and a mark there sat on top of the names; three quarters down it sits on terrain.
VERTICAL_WIDTH, VERTICAL_HEIGHT = 1080, 1920
VERTICAL_WATERMARK_Y_FRACTION = 0.76
TOUR_SECONDS = 22.0
TOUR_SAMPLES = 160
SMOOTH_WINDOW_FRACTION = 0.10
SMOOTH_PASSES = 2
LOOKAHEAD_FRACTION = 0.05
ORBIT_RADIUS_FACTOR = 1.0
ORBIT_HEIGHT_FACTOR = 0.7
ORBIT_STEPS = 36
ORBIT_STEP_SECONDS = 0.34
ORBIT_SLOWDOWN = 2.2
ORBIT_LOOK_AT_LIFT_M = 60.0

#: A chunk of frames rendered from one app boot. The boot streams terrain around its
#: first frame; the world stays anchored there for the whole chunk (see
#: PeakNavRenderer.placeCamera), so a chunk must not travel too far from it. The snapshot
#: flights move ~45 m a frame and use 120-frame chunks; a tour flies much faster, so the
#: chunk is sized by distance and capped by count.
CHUNK_MAX_TRAVEL_M = 6000.0
CHUNK_MAX_FRAMES = 120

M_PER_DEG_LAT = 111320.0


# ---------------------------------------------------------------------------- the track
def read_gpx(path):
    """The longest track (or route) of a GPX file as [(lat, lon, ele)], ele None if absent.

    The app picks its longest track too, so a file carrying an outline plus the route
    flies the route.
    """
    tree = ET.parse(path)
    root = tree.getroot()

    def local(tag):
        return tag.split("}", 1)[-1]

    tracks = []
    for trk in root.iter():
        if local(trk.tag) not in ("trk", "rte"):
            continue
        points = []
        for pt in trk.iter():
            if local(pt.tag) not in ("trkpt", "rtept"):
                continue
            ele = None
            for child in pt:
                if local(child.tag) == "ele":
                    try:
                        ele = float(child.text)
                    except (TypeError, ValueError):
                        ele = None
            points.append((float(pt.get("lat")), float(pt.get("lon")), ele))
        if len(points) >= 2:
            tracks.append(points)
    if not tracks:
        sys.exit(f"error: no track or route with two or more points in {path}")
    return max(tracks, key=len)


class Frame:
    """The flat metric frame the tour is planned in: x east, y north, z up, in metres.

    The app plans in its own world units with longitude scaled by the cosine of the
    reference latitude; metres with the same scaling are the same geometry, and let the
    tour's constants (which are in metres) be used directly.
    """

    def __init__(self, lat0, lon0):
        self.lat0 = lat0
        self.lon0 = lon0
        self.k = math.cos(math.radians(lat0))

    def to_world(self, lat, lon, ele):
        return ((lon - self.lon0) * M_PER_DEG_LAT * self.k,
                (lat - self.lat0) * M_PER_DEG_LAT,
                ele)

    def to_geo(self, x, y):
        return (self.lat0 + y / M_PER_DEG_LAT,
                self.lon0 + x / (M_PER_DEG_LAT * self.k))


#: Open-Meteo's elevation service (90 m Copernicus DEM, free, no key), for tracks that
#: carry no usable elevations. The tour needs the track's height only to place the camera
#: above it and aim at it, so a few hundred samples along the route are plenty; they are
#: fetched in batches of a hundred and the points between are interpolated.
ELEVATION_SERVICE = "https://api.open-meteo.com/v1/elevation"
ELEVATION_SERVICE_SAMPLES = 400
ELEVATION_SERVICE_BATCH = 100


def fetch_elevations(coords, cache_path=None):
    """Elevations for [(lat, lon)] from the service, or None if it cannot be reached.

    Answers are kept in a sidecar JSON next to the GPX, so a re-render (a different
    length, a different orientation) asks nothing twice. The service rate-limits bursts
    with a 429; batches are paced and retried with a growing pause.
    """
    import json
    import time
    import urllib.error
    import urllib.parse
    import urllib.request
    cache = {}
    if cache_path and os.path.exists(cache_path):
        with open(cache_path) as f:
            cache = {tuple(k.split(",")): v for k, v in json.load(f).items()}
    key = lambda lat, lon: (f"{lat:.5f}", f"{lon:.5f}")
    todo = [c for c in coords if key(*c) not in cache]
    for start in range(0, len(todo), ELEVATION_SERVICE_BATCH):
        batch = todo[start:start + ELEVATION_SERVICE_BATCH]
        query = urllib.parse.urlencode({
            "latitude": ",".join(f"{lat:.5f}" for lat, _ in batch),
            "longitude": ",".join(f"{lon:.5f}" for _, lon in batch)})
        got = None
        for attempt in range(5):
            try:
                with urllib.request.urlopen(ELEVATION_SERVICE + "?" + query, timeout=60) as r:
                    got = json.load(r)["elevation"]
                break
            except urllib.error.HTTPError as e:
                if e.code != 429:
                    print(f"  warning: elevation service failed ({e})", file=sys.stderr)
                    return None
                time.sleep(5 * (attempt + 1))
            except Exception as e:  # noqa: BLE001 - any other failure means "no service"
                print(f"  warning: elevation service failed ({e})", file=sys.stderr)
                return None
        if got is None:
            print("  warning: elevation service kept rate-limiting; giving up", file=sys.stderr)
            return None
        for c, ele in zip(batch, got):
            cache[key(*c)] = ele
        time.sleep(1.0)
    if cache_path and todo:
        with open(cache_path, "w") as f:
            json.dump({",".join(k): v for k, v in cache.items()}, f)
    return [cache[key(*c)] for c in coords]


def fill_elevations(points, use_service=True, cache_path=None):
    """Every point given an elevation.

    A missing <ele>, or a zero - which in a track that runs anywhere else is an exporter's
    "no data", not a point at sea level (the Adamello Ultra Trail's official file is all
    zeros) - is filled from the elevation service when allowed, and the remaining gaps
    are interpolated linearly between the nearest known points. The app samples its own
    terrain for these; a script has none to hand. Warns, because every fill is a guess.
    """
    points = [(p[0], p[1], None if p[2] is None or p[2] == 0.0 else p[2]) for p in points]
    missing = [i for i, p in enumerate(points) if p[2] is None]
    if missing and use_service:
        step = max(1, len(missing) // ELEVATION_SERVICE_SAMPLES)
        sample = missing[::step]
        if sample[-1] != missing[-1]:
            sample.append(missing[-1])
        print(f"  {len(missing)} of {len(points)} points lack an elevation; asking "
              f"{ELEVATION_SERVICE} for {len(sample)} of them", file=sys.stderr)
        fetched = fetch_elevations([(points[i][0], points[i][1]) for i in sample], cache_path)
        if fetched is not None:
            for i, ele in zip(sample, fetched):
                points[i] = (points[i][0], points[i][1], float(ele))
            missing = [i for i, p in enumerate(points) if p[2] is None]
    known = [i for i, p in enumerate(points) if p[2] is not None]
    if not known:
        print("  warning: no elevations at all; flying as if the track were at sea level",
              file=sys.stderr)
        return [(p[0], p[1], 0.0) for p in points]
    if not missing:
        return points
    if not use_service:
        print(f"  warning: {len(missing)} of {len(points)} points lack an elevation; "
              f"interpolating between their neighbours", file=sys.stderr)
    out = []
    k = 0
    for i, p in enumerate(points):
        if p[2] is not None:
            out.append(p)
            continue
        while k + 1 < len(known) and known[k + 1] < i:
            k += 1
        lo = known[k]
        hi = known[k + 1] if k + 1 < len(known) else lo
        if hi == lo or i < lo:
            ele = points[lo][2] if i >= lo else points[known[0]][2]
        else:
            t = (i - lo) / (hi - lo)
            ele = points[lo][2] + (points[hi][2] - points[lo][2]) * t
        out.append((p[0], p[1], ele))
    return out


# ------------------------------------------------------------ the app's path smoothing
def dist(a, b):
    return math.sqrt(sum((p - q) ** 2 for p, q in zip(a, b)))


def resample_by_length(src, n):
    """n points evenly spaced along the polyline - gpxResampleByLength."""
    cum = [0.0]
    for a, b in zip(src, src[1:]):
        cum.append(cum[-1] + dist(a, b))
    total = cum[-1]
    if total < 1e-9:
        return [src[0], src[-1]]
    out = []
    seg = 0
    for i in range(n):
        want = total * i / (n - 1)
        while seg < len(src) - 2 and cum[seg + 1] < want:
            seg += 1
        span = cum[seg + 1] - cum[seg]
        t = 0.0 if span < 1e-9 else (want - cum[seg]) / span
        a, b = src[seg], src[seg + 1]
        out.append(tuple(p + (q - p) * t for p, q in zip(a, b)))
    return out


def moving_average(src, window, passes):
    """Centred box filter, shrinking symmetrically at the ends - gpxMovingAverage."""
    cur = src
    half = window // 2
    for _ in range(passes):
        out = []
        for i in range(len(cur)):
            lo, hi = max(0, i - half), min(len(cur) - 1, i + half)
            reach = min(i - lo, hi - i)
            pts = cur[i - reach:i + reach + 1]
            out.append(tuple(sum(c) / len(pts) for c in zip(*pts)))
        cur = out
    return cur


def smooth_window(n):
    return max(3, int(n * SMOOTH_WINDOW_FRACTION) | 1)


def span_meters(path):
    xs = [p[0] for p in path]
    ys = [p[1] for p in path]
    return math.hypot(max(xs) - min(xs), max(ys) - min(ys))


# ------------------------------------------------------------------- the camera plan
def tour_keyframes(world_points, fly_seconds, orbit):
    """(cam, aim, seconds) keyframes of the tour - startGpxFlythrough, minus the intro."""
    raw = []
    for p in world_points:
        if not raw or dist(raw[-1], p) > 1e-3:
            raw.append(p)
    if len(raw) < 2:
        sys.exit("error: the track has no extent")

    # De-noise, then space evenly, then polish - in that order (see the app).
    path = resample_by_length(raw, TOUR_SAMPLES * 3)
    path = moving_average(path, smooth_window(len(path)), SMOOTH_PASSES)
    path = resample_by_length(path, TOUR_SAMPLES)
    window = smooth_window(len(path))
    path = moving_average(path, window, SMOOTH_PASSES)
    m = len(path)

    span = span_meters(path)
    height = min(max(TOUR_HEIGHT_FRACTION * span, TOUR_HEIGHT_MIN_M), TOUR_HEIGHT_MAX_M)
    back = min(max(TOUR_BACK_FRACTION * span, TOUR_BACK_MIN_M), TOUR_BACK_MAX_M)

    lookahead = max(2, round(m * LOOKAHEAD_FRACTION))
    cam_path = []
    for i in range(m):
        cur = path[i]
        a = path[max(0, i - lookahead)]
        b = path[min(m - 1, i + lookahead)]
        fx, fy = b[0] - a[0], b[1] - a[1]
        norm = math.hypot(fx, fy)
        if norm < 1e-9:
            fx, fy = 0.0, 1.0
        else:
            fx, fy = fx / norm, fy / norm
        cam_path.append((cur[0] - fx * back, cur[1] - fy * back, cur[2] + height))
    cam_path = moving_average(cam_path, window, SMOOTH_PASSES)

    keys = []
    step = fly_seconds / max(1, m - 1)
    for i in range(m):
        keys.append((cam_path[i], path[min(m - 1, i + lookahead)], step))

    if orbit:
        end = path[m - 1]
        last = cam_path[m - 1]
        radius = back * ORBIT_RADIUS_FACTOR
        orbit_h = height * ORBIT_HEIGHT_FACTOR
        look_at = (end[0], end[1], end[2] + ORBIT_LOOK_AT_LIFT_M)
        start = math.atan2(last[1] - end[1], last[0] - end[0])
        for s in range(1, ORBIT_STEPS + 1):
            ang = start + 2.0 * math.pi * s / ORBIT_STEPS
            pos = (end[0] + math.cos(ang) * radius, end[1] + math.sin(ang) * radius,
                   end[2] + orbit_h)
            t = s / ORBIT_STEPS
            keys.append((pos, look_at, ORBIT_STEP_SECONDS * (1.0 + (ORBIT_SLOWDOWN - 1.0) * t * t)))
    return keys, {"span_m": span, "height_m": height, "back_m": back, "samples": m}


def _catmull(p0, p1, p2, p3, t):
    return _gv._catmull_rom(p0, p1, p2, p3, t)


def frames_from_keyframes(keys, fps, frame):
    """Every frame of the video: (lat, lon, bearing, pitch, altitude ASL).

    The app hands its keyframes to the camera mover, which glides between them; here
    each frame's time picks its place on a Catmull-Rom curve through the keyframes -
    camera and aim point alike - so the motion is continuous through them rather than
    kinked at each. Each keyframe's own duration (the orbit's lengthen towards the end)
    sets how much of the timeline it spans.
    """
    times = [0.0]
    for _, _, seconds in keys[:-1]:
        times.append(times[-1] + seconds)
    total = times[-1]
    count = int(round(total * fps)) + 1
    cams = [k[0] for k in keys]
    aims = [k[1] for k in keys]

    def at(seq, i):
        return seq[min(max(i, 0), len(seq) - 1)]

    out = []
    seg = 0
    for f in range(count):
        t = min(total, f / fps)
        while seg < len(times) - 2 and times[seg + 1] < t:
            seg += 1
        span = times[seg + 1] - times[seg]
        u = 0.0 if span <= 0 else (t - times[seg]) / span
        cam = _catmull(at(cams, seg - 1), at(cams, seg), at(cams, seg + 1), at(cams, seg + 2), u)
        aim = _catmull(at(aims, seg - 1), at(aims, seg), at(aims, seg + 1), at(aims, seg + 2), u)
        dx, dy, dz = aim[0] - cam[0], aim[1] - cam[1], aim[2] - cam[2]
        bearing = math.degrees(math.atan2(dx, dy)) % 360.0
        pitch = math.degrees(math.atan2(dz, math.hypot(dx, dy)))
        lat, lon = frame.to_geo(cam[0], cam[1])
        out.append((round(lat, 6), round(lon, 6), round(bearing, 4), round(pitch, 3),
                    round(cam[2])))
    return out


# --------------------------------------------------------------------- the rendering
def chunks_of(missing, frames):
    """Splits the frames still to render into boots, each within CHUNK_MAX_TRAVEL_M."""
    chunks = []
    current = []
    for index, placement in missing:
        if current:
            first = frames[current[0][0]]
            travel = _gv.ground_distance_m(first[0], first[1], placement[0], placement[1])
            if len(current) >= CHUNK_MAX_FRAMES or travel > CHUNK_MAX_TRAVEL_M:
                chunks.append(current)
                current = []
        current.append((index, placement))
    if current:
        chunks.append(current)
    return chunks


def render_chunk(args, gpx_path, chunk, frame_dir, boot_number, probe_every):
    """One app boot, rendering the frames it is given - the snapshot videos' recipe plus
    the track drawn and the tour's lens."""
    first = chunk[0][1]
    label_refresh = max(1, int(args.fps * _gv.LABEL_REFRESH_SECONDS))
    cmd = [os.path.join(_gv.JAVA_HOME, "bin", "java"), "-Xmx4g", "-jar", _gv.jar_path(),
           "--lat", str(first[0]), "--lon", str(first[1]),
           "--width", str(args.width), "--height", str(args.height),
           "--await", "120000", "--download", "900000",
           "--sky", "on", "--sky-mode", "day",
           "--constellations", "off", "--star-names", "off",
           "--sky-grid", "off", "--ecliptic", "off", "--sky-labels", "off",
           "--sky-time", _gv.SKY_TIME, "--sky-time-label", "off",
           "--frame-quiet", "400", "--frame-settle", "250",
           "--label-refresh", str(label_refresh),
           "--horizon-compass", "off", "--coordinates", "off", "--corner-compass", "off",
           "--language", _gv.LANGUAGE, "--format", "jpg",
           "--labels", args.labels,
           "--fov", str(args.fov),
           "--gpx", gpx_path]
    if probe_every:
        cmd += ["--serve", "0"]
    for index, (lat, lon, bearing, pitch, altitude) in chunk:
        cmd += ["--frame", f"{lat},{lon},{bearing},{pitch},{altitude}asl,"
                + os.path.join(frame_dir, f"f{index:05d}.jpg")]

    log = os.path.join(frame_dir, "render.log")
    stop = threading.Event()
    probe = None
    if probe_every:
        probe_path = os.path.join(OUT_DIR, args.name + ".probe.tsv")
        probe = threading.Thread(target=_gv._probe_loop, name="probe", daemon=True,
                                 args=(log, probe_path, boot_number, chunk, frame_dir,
                                       probe_every, stop))
    with open(log, "w") as lf:
        proc = subprocess.Popen(cmd, cwd=ROOT, stdout=lf, stderr=subprocess.STDOUT)
        if probe:
            probe.start()
        proc.wait()
    stop.set()
    if probe:
        probe.join(timeout=120)
    written = sum(1 for index, _ in chunk
                  if os.path.exists(os.path.join(frame_dir, f"f{index:05d}.jpg")))
    if proc.returncode != 0 or written < len(chunk):
        print(f"  boot ended early: {written}/{len(chunk)} frames "
              f"(exit {proc.returncode}); kept {log}", file=sys.stderr)
        return False
    os.remove(log)
    return True


#: The watermark, burnt in at encode time rather than rendered into the frames: the banked
#: frames stay clean, and changing the mark - or dropping it - is a re-encode, not a
#: re-render. Bottom-right corner, sized and inset relative to the frame height so it
#: reads the same at 720p and 4K; white with a dark outline and a soft shadow, so it
#: holds on sky, snow and rock alike.
WATERMARK_TEXT = "peaknav.com"
WATERMARK_FONTS = ["/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
                   "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf",
                   "/usr/share/fonts/TTF/DejaVuSans-Bold.ttf",
                   "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
                   "C:/Windows/Fonts/arialbd.ttf"]


def watermark_filter(width, height, vertical):
    """The ffmpeg drawtext filter for the watermark, or None if no font can be found.

    Landscape: bottom-right, the usual place. Portrait: left, above the band a Reel's own
    overlays cover - bottom-right would sit under the caption and the icon column, and the
    top is the label band.
    """
    font = next((f for f in WATERMARK_FONTS if os.path.exists(f)), None)
    if font is None:
        return None
    short = min(width, height)
    size = max(18, round(short / 30))
    inset = max(8, round(short / 45))
    # An outline rather than a shadow alone: white on snow or on a pale plate lost its
    # edge, and a dark border keeps the letters legible on any ground.
    border = max(2, round(size / 10))
    # drawtext's own escaping: ':' and '\\' are special inside the option, so a font path
    # with either must be escaped; the text has neither.
    font = font.replace("\\", "\\\\").replace(":", "\\:")
    if vertical:
        position = f"x={inset}:y={round(height * VERTICAL_WATERMARK_Y_FRACTION)}"
    else:
        position = f"x=w-tw-{inset}:y=h-th-{inset}"
    return (f"drawtext=fontfile='{font}':text='{WATERMARK_TEXT}':fontsize={size}"
            f":fontcolor=white:borderw={border}:bordercolor=black@0.8"
            f":shadowcolor=black@0.35:shadowx=2:shadowy=2:{position}")


#: The soundtrack: "Isolated" by Kevin MacLeod, CC BY 3.0 - credit him in the video's
#: description (the exact line is in snapshots/music/README.md, with the alternatives).
#: Trimmed to the video and faded out at the end; faded in briefly too, since the cut
#: starts wherever the file starts.
DEFAULT_MUSIC = os.path.join(ROOT, "snapshots", "music", "kevin-macleod-isolated.ogg")
MUSIC_FADE_IN_S = 1.5
MUSIC_FADE_OUT_S = 3.0


def encode(frame_dir, output, fps, count, width, height, vertical, watermark, music,
           webm=False):
    """Frames to an H.264 mp4 that plays anywhere and uploads as-is: yuv420p for players,
    faststart so streaming starts before the download ends, the watermark drawn and the
    music laid on the way through."""
    if shutil.which("ffmpeg") is None:
        sys.exit("error: ffmpeg is not installed; the frames are rendered, "
                 "encode them yourself from " + frame_dir)
    seconds = count / fps
    cmd = ["ffmpeg", "-y", "-loglevel", "error",
           "-framerate", str(fps),
           "-i", os.path.join(frame_dir, "f%05d.jpg")]
    if music:
        # Looped, so a flight longer than the piece is not left silent at the end; the
        # fade-out below still lands on the video's last seconds.
        cmd += ["-stream_loop", "-1", "-i", music]
    if watermark:
        drawtext = watermark_filter(width, height, vertical)
        if drawtext is None:
            print("  warning: no font found for the watermark; encoding without it "
                  "(add one to WATERMARK_FONTS)", file=sys.stderr)
        else:
            cmd += ["-vf", drawtext]
    cmd += ["-c:v", "libx264", "-preset", "slow", "-crf", "18",
            "-pix_fmt", "yuv420p", "-movflags", "+faststart"]
    if music:
        fade_out_at = max(0.0, seconds - MUSIC_FADE_OUT_S)
        cmd += ["-af", f"atrim=0:{seconds:.3f},afade=t=in:st=0:d={MUSIC_FADE_IN_S},"
                       f"afade=t=out:st={fade_out_at:.3f}:d={MUSIC_FADE_OUT_S}",
                "-c:a", "aac", "-b:a", "192k", "-shortest"]
    cmd += [output]
    subprocess.run(cmd, check=True)
    if webm:
        # Wikimedia Commons takes no MP4/H.264 (patent-encumbered): a VP9 WebM, with Opus
        # if there is music, transcoded from the finished mp4 so both carry the same
        # picture. Two-pass would be a little smaller; one pass at this quality is fine.
        webm_out = os.path.splitext(output)[0] + ".webm"
        cmd = ["ffmpeg", "-y", "-loglevel", "error", "-i", output,
               "-c:v", "libvpx-vp9", "-b:v", "0", "-crf", "30", "-row-mt", "1",
               "-pix_fmt", "yuv420p"]
        cmd += ["-c:a", "libopus", "-b:a", "128k"] if music else ["-an"]
        cmd += [webm_out]
        subprocess.run(cmd, check=True)
        print(f"  wrote {webm_out} ({os.path.getsize(webm_out) / 1e6:.1f} MB, VP9 for Wikimedia Commons)")
    size = os.path.getsize(output) / 1e6
    print(f"  wrote {output} ({count / fps:.1f}s at {fps} fps, {size:.1f} MB)")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("gpx", help="the GPX file; its longest track (or route) is flown")
    parser.add_argument("--name", help="video name (default: the GPX file's stem)")
    parser.add_argument("--fly-seconds", type=float, default=TOUR_SECONDS, metavar="S",
                        help=f"seconds spent flying along the track (the app's tour takes "
                             f"{TOUR_SECONDS:.0f}); the closing orbit adds about 17")
    parser.add_argument("--no-orbit", action="store_true",
                        help="end at the finish instead of circling it")
    parser.add_argument("--fps", type=int, default=30,
                        help="frames per second (default 30; 60 for a smoother upload, "
                             "at twice the render time)")
    parser.add_argument("--width", type=int, default=None, help="default 1920 (1080 with --vertical)")
    parser.add_argument("--height", type=int, default=None, help="default 1080 (1920 with --vertical)")
    parser.add_argument("--vertical", action="store_true",
                        help="a portrait 9:16 render for Reels and Stories: 1080x1920, a wider "
                             f"{VERTICAL_FIELD_OF_VIEW:.0f}-degree lens, the watermark clear of "
                             "Instagram's overlays; written as <name>-vertical.mp4 with its own "
                             "frame bank")
    parser.add_argument("--fov", type=float, default=None, metavar="DEG",
                        help=f"vertical field of view (default: the tour's {TOUR_FIELD_OF_VIEW:.0f}, "
                             f"or {VERTICAL_FIELD_OF_VIEW:.0f} with --vertical)")
    parser.add_argument("--labels", default="peaks,roads",
                        help="label layers to draw, comma-separated (default peaks,roads)")
    parser.add_argument("--probe", type=float, metavar="SECONDS",
                        help="sample the renderer's REST API while rendering, into "
                             "<name>.probe.tsv next to the video")
    parser.add_argument("--music", metavar="FILE", default=DEFAULT_MUSIC,
                        help="soundtrack, trimmed to the video and faded out (default: Kevin "
                             "MacLeod's 'Isolated', CC BY - see snapshots/music/README.md for "
                             "the credit line the licence asks for)")
    parser.add_argument("--no-music", dest="music", action="store_const", const=None,
                        help="leave the video silent")
    parser.add_argument("--webm", action="store_true",
                        help="also write a VP9 WebM next to the mp4 - the format Wikimedia "
                             "Commons accepts (it takes no MP4)")
    parser.add_argument("--no-watermark", dest="watermark", action="store_false",
                        help=f"leave the '{WATERMARK_TEXT}' mark off the bottom-right corner")
    parser.add_argument("--no-elevation-service", dest="elevation_service",
                        action="store_false",
                        help="never ask Open-Meteo for the elevations a track lacks; "
                             "interpolate or assume sea level instead")
    parser.add_argument("--overwrite", action="store_true",
                        help="discard banked frames and start over")
    parser.add_argument("--plan-only", action="store_true",
                        help="print the camera plan and exit without rendering")
    args = parser.parse_args()

    if not args.plan_only:
        if not os.environ.get("DISPLAY"):
            sys.exit("error: DISPLAY is not set; the renderer needs a session display "
                     "(the window stays hidden)")
        if _gv.jar_path() is None:
            sys.exit("error: no headless jar; run ./gradlew :headless:renderJar")

    gpx_path = os.path.abspath(args.gpx)
    args.name = args.name or os.path.splitext(os.path.basename(gpx_path))[0]
    if args.vertical:
        args.name += "-vertical"
    args.width = args.width or (VERTICAL_WIDTH if args.vertical else 1920)
    args.height = args.height or (VERTICAL_HEIGHT if args.vertical else 1080)
    args.fov = args.fov or (VERTICAL_FIELD_OF_VIEW if args.vertical else TOUR_FIELD_OF_VIEW)

    points = fill_elevations(read_gpx(gpx_path), use_service=args.elevation_service,
                             cache_path=gpx_path + ".elevations.json")
    lat0 = sum(p[0] for p in points) / len(points)
    lon0 = sum(p[1] for p in points) / len(points)
    frame = Frame(lat0, lon0)
    world = [frame.to_world(*p) for p in points]
    keys, plan = tour_keyframes(world, args.fly_seconds, not args.no_orbit)
    frames = frames_from_keyframes(keys, args.fps, frame)
    seconds = (len(frames) - 1) / args.fps
    print(f"{args.name}: {len(points)} points, span {plan['span_m'] / 1000:.1f} km -> "
          f"camera {plan['height_m']:.0f} m above and {plan['back_m']:.0f} m behind the track; "
          f"{len(frames)} frames at {args.fps} fps ({seconds:.1f}s), {args.width}x{args.height}")
    if args.plan_only:
        for f in frames[::max(1, len(frames) // 12)]:
            print("  ", f)
        return 0

    frame_dir = os.path.join(OUT_DIR, "frames", args.name)
    if args.overwrite and os.path.isdir(frame_dir):
        shutil.rmtree(frame_dir)
    os.makedirs(frame_dir, exist_ok=True)
    output = os.path.join(OUT_DIR, args.name + ".mp4")

    missing = [(i, f) for i, f in enumerate(frames)
               if not os.path.exists(os.path.join(frame_dir, f"f{i:05d}.jpg"))]
    if missing:
        chunks = chunks_of(missing, frames)
        print(f"  {len(missing)} of {len(frames)} frames to render, in {len(chunks)} boot(s)")
        for n, chunk in enumerate(chunks, 1):
            print(f"  boot {n}/{len(chunks)}: frames {chunk[0][0]}-{chunk[-1][0]}", flush=True)
            if not render_chunk(args, gpx_path, chunk, frame_dir, n, args.probe):
                return 1
    else:
        print(f"  all {len(frames)} frames already rendered")
    if args.music and not os.path.exists(args.music):
        sys.exit(f"error: music file not found: {args.music}")
    encode(frame_dir, output, args.fps, len(frames), args.width, args.height, args.vertical,
           args.watermark, args.music, args.webm)
    return 0


if __name__ == "__main__":
    sys.exit(main())
