#!/usr/bin/env python3
"""Renders one full orbit of a point - a summit, usually - as a video.

    python3 snapshots/render_orbit.py --target 27.9881,86.9250,8849 \\
        --camera 27.893,86.817 --altitude 9200 --name everest

    --target   latitude,longitude[,elevation] of the point to circle and keep centred;
               without an elevation it is fetched from the elevation service
    --camera   latitude,longitude of where the orbit STARTS: its distance from the target
               is the orbit's radius, its bearing from the target the starting azimuth;
               the camera then goes round clockwise, back to where it began
    --altitude the camera's height in metres above sea level, held for the whole orbit

The camera plan is the snapshot videos' orbit (generate_videos.py orbit_frames, the same
one behind the Matterhorn and Rainier clips): a constant radius and altitude, the aim
computed in the renderer's own flat frame so the subject sits pinned while the world
turns. One revolution takes --seconds (36 by default, ten degrees a second).

Rendering, encoding and the options are shared with render_gpx.py: --vertical for a
9:16 Reel, --webm for Wikimedia Commons (which takes no MP4), --no-music and
--no-watermark for a clean upload, --labels for the layers drawn, --probe to sample the
renderer's API while it works. Frames are banked in snapshots/videos/orbit/frames/<name>/;
--output NAME encodes a differently named file from the same bank, so one render can
yield both a clean Wikimedia upload and a watermarked, scored cut for social media.
"""

import argparse
import importlib.util
import math
import os
import shutil
import subprocess
import sys
import threading

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "snapshots", "videos", "orbit")


def _load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


_gv = _load("peaknav_videos", os.path.join(ROOT, "snapshots", "generate_videos.py"))
_rg = _load("peaknav_render_gpx", os.path.join(ROOT, "snapshots", "render_gpx.py"))

ORBIT_SECONDS = 36.0
FRAMES_PER_BOOT = 120
#: The app's map lens; the snapshot orbits are shot with it, and it frames a single
#: summit the way the app does. The GPX tour's wider 62 is for following a track.
ORBIT_FIELD_OF_VIEW = 30.0
VERTICAL_ORBIT_FIELD_OF_VIEW = 45.0


def orbit_frames(target, start, altitude, seconds, fps):
    """Every frame: (lat, lon, bearing, pitch, altitude) - generate_videos.orbit_frames
    with the start azimuth and radius taken from the camera's starting position."""
    tlat, tlon, talt = target
    radius_m = _gv.ground_distance_m(tlat, tlon, start[0], start[1])
    start_az = _gv.initial_bearing(tlat, tlon, start[0], start[1])
    count = int(round(seconds * fps))
    out = []
    for i in range(count):
        azimuth = (start_az + 360.0 * i / count) % 360.0
        elat, elon = _gv.destination_point(tlat, tlon, azimuth, radius_m)
        k = math.cos(math.radians(elat))
        bearing = math.degrees(math.atan2((tlon - elon) * k, tlat - elat)) % 360.0
        dist = _gv.ground_distance_m(elat, elon, tlat, tlon)
        angle = math.degrees(math.atan2(talt - altitude, max(dist, 1.0)))
        out.append((round(elat, 6), round(elon, 6), round(bearing, 4),
                    round(angle - _gv.PITCH_DROP_DEG, 3), round(altitude)))
    return out, radius_m, start_az


def render_chunk(args, target, chunk, frame_dir, boot_number, probe_every):
    """One app boot at the target - so the mountain every frame looks at is what the
    boot streams in - rendering the frames it is given."""
    label_refresh = max(1, int(args.fps * _gv.LABEL_REFRESH_SECONDS))
    cmd = [os.path.join(_gv.JAVA_HOME, "bin", "java"), "-Xmx4g", "-jar", _gv.jar_path(),
           "--lat", str(target[0]), "--lon", str(target[1]),
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
           "--fov", str(args.fov)]
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


def parse_latlon(text, what):
    parts = [float(x) for x in text.split(",")]
    if len(parts) not in (2, 3):
        sys.exit(f"error: {what} wants lat,lon[,elevation]; got {text!r}")
    return parts


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--target", required=True, metavar="LAT,LON[,ELE]",
                        help="the point to orbit; elevation in metres, fetched if omitted")
    parser.add_argument("--camera", required=True, metavar="LAT,LON",
                        help="where the camera starts; sets the radius and the first azimuth")
    parser.add_argument("--altitude", required=True, type=float, metavar="METERS",
                        help="camera height above sea level, held throughout")
    parser.add_argument("--name", help="video name (default: orbit-<lat>-<lon>)")
    parser.add_argument("--output", metavar="NAME",
                        help="file name for the encoded video (default: the video name); "
                             "the frames stay banked under --name")
    parser.add_argument("--seconds", type=float, default=ORBIT_SECONDS,
                        help=f"one revolution takes this long (default {ORBIT_SECONDS:.0f})")
    parser.add_argument("--fps", type=int, default=30)
    parser.add_argument("--width", type=int, default=None, help="default 1920 (1080 with --vertical)")
    parser.add_argument("--height", type=int, default=None, help="default 1080 (1920 with --vertical)")
    parser.add_argument("--vertical", action="store_true", help="a portrait 9:16 render")
    parser.add_argument("--fov", type=float, default=None, metavar="DEG",
                        help=f"vertical field of view (default {ORBIT_FIELD_OF_VIEW:.0f}, the "
                             f"app's map lens; {VERTICAL_ORBIT_FIELD_OF_VIEW:.0f} with --vertical)")
    parser.add_argument("--labels", default="peaks,mountain_ranges,roads",
                        help="label layers to draw (default peaks,mountain_ranges,roads)")
    parser.add_argument("--music", metavar="FILE", default=_rg.DEFAULT_MUSIC)
    parser.add_argument("--no-music", dest="music", action="store_const", const=None)
    parser.add_argument("--no-watermark", dest="watermark", action="store_false")
    parser.add_argument("--webm", action="store_true", help="also write a VP9 WebM (Wikimedia Commons)")
    parser.add_argument("--probe", type=float, metavar="SECONDS")
    parser.add_argument("--no-elevation-service", dest="elevation_service", action="store_false")
    parser.add_argument("--overwrite", action="store_true")
    parser.add_argument("--plan-only", action="store_true")
    args = parser.parse_args()

    target = parse_latlon(args.target, "--target")
    camera = parse_latlon(args.camera, "--camera")[:2]
    if len(target) == 2:
        if not args.elevation_service:
            sys.exit("error: --target needs an elevation when the elevation service is off")
        fetched = _rg.fetch_elevations([(target[0], target[1])])
        if not fetched:
            sys.exit("error: could not fetch the target's elevation; give it as lat,lon,ele")
        target.append(float(fetched[0]))
    args.name = args.name or f"orbit-{target[0]:.4f}-{target[1]:.4f}"
    if args.vertical:
        args.name += "-vertical"
        if args.output:
            args.output += "-vertical"
    args.output = args.output or args.name
    args.width = args.width or (_rg.VERTICAL_WIDTH if args.vertical else 1920)
    args.height = args.height or (_rg.VERTICAL_HEIGHT if args.vertical else 1080)
    args.fov = args.fov or (VERTICAL_ORBIT_FIELD_OF_VIEW if args.vertical else ORBIT_FIELD_OF_VIEW)

    frames, radius_m, start_az = orbit_frames(target, camera, args.altitude, args.seconds, args.fps)
    print(f"{args.name}: orbiting {target[0]:.4f},{target[1]:.4f} ({target[2]:.0f} m) at "
          f"{radius_m / 1000:.1f} km, {args.altitude:.0f} m ASL, from azimuth {start_az:.0f}; "
          f"{len(frames)} frames at {args.fps} fps ({args.seconds:.0f}s), {args.width}x{args.height}")
    if args.plan_only:
        for f in frames[::max(1, len(frames) // 8)]:
            print("  ", f)
        return 0
    if not os.environ.get("DISPLAY"):
        sys.exit("error: DISPLAY is not set; the renderer needs a session display")
    if _gv.jar_path() is None:
        sys.exit("error: no headless jar; run ./gradlew :headless:renderJar")

    frame_dir = os.path.join(OUT_DIR, "frames", args.name)
    if args.overwrite and os.path.isdir(frame_dir):
        shutil.rmtree(frame_dir)
    os.makedirs(frame_dir, exist_ok=True)
    output = os.path.join(OUT_DIR, args.output + ".mp4")
    missing = [(i, f) for i, f in enumerate(frames)
               if not os.path.exists(os.path.join(frame_dir, f"f{i:05d}.jpg"))]
    if missing:
        chunks = [missing[i:i + FRAMES_PER_BOOT] for i in range(0, len(missing), FRAMES_PER_BOOT)]
        print(f"  {len(missing)} of {len(frames)} frames to render, in {len(chunks)} boot(s)")
        for n, chunk in enumerate(chunks, 1):
            print(f"  boot {n}/{len(chunks)}: frames {chunk[0][0]}-{chunk[-1][0]}", flush=True)
            if not render_chunk(args, target, chunk, frame_dir, n, args.probe):
                return 1
    else:
        print(f"  all {len(frames)} frames already rendered")
    if args.music and not os.path.exists(args.music):
        sys.exit(f"error: music file not found: {args.music}")
    _rg.encode(frame_dir, output, args.fps, len(frames), args.width, args.height,
               args.vertical, args.watermark, args.music, args.webm)
    return 0


if __name__ == "__main__":
    sys.exit(main())
