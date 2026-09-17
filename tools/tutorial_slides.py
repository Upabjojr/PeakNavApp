#!/usr/bin/env python3
"""Builds the "?" tutorial from pictures of the running app.

The pictures are screenshots of the app on a phone, each with the places of the named widgets
as the app itself reports them (MapViewerScreen.widgetBoundsJson, written next to the picture
as <name>.widgets.json). This script scales them into assets/info/ and writes the SLIDES block
of assets/info/app_tutorial.html, so every ring lands on its widget wherever that widget has
moved to, and the tutorial cannot drift from the app.

    python3 tools/tutorial_slides.py <captures dir> [--width 620]

A capture directory holds, per view below, <view>.png and <view>.widgets.json. Any missing view
is skipped with a warning, and the slides that use it are left out. The captions are the app's
own translations, keyed by TutorialStrings.KEYS - this script only says which key goes with
which picture and which widget it points at.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
INFO = os.path.join(ROOT, "assets", "info")
PAGE = os.path.join(INFO, "app_tutorial.html")

# caption key, the view it is shown on, the named widget the ring points at (None for none).
# The order is the order of the slides, and must match TutorialStrings.KEYS.
SLIDES = [
    ("Tutorial_welcome",        "base",          None),
    ("Tutorial_gyroscope",      "base",          "gyro"),
    ("Tutorial_elevation",      "base",          "elevation_bar"),
    ("Tutorial_search",         "base",          "search"),
    ("Tutorial_here",           "base",          "here"),
    ("Tutorial_share",          "base",          "share"),
    ("Tutorial_options",        "base",          "options"),
    ("Tutorial_options_pane",   "options",       None),
    ("Tutorial_satellite",      "satellite",     None),
    ("Tutorial_trails",         "trails",        None),
    ("Tutorial_pistes",         "pistes",        None),
    ("Tutorial_sky",            "sky",           None),
    ("Tutorial_tap",            "tap",           "go_to"),
    ("Tutorial_orbit",          "tap",           "orbit"),
    ("Tutorial_open_maps",      "tap",           "open_coordinate"),
    ("Tutorial_route",          "tap",           "route_to"),
    ("Tutorial_route_result",   "route",         None),
    ("Tutorial_gpx",            "gpx",           "gpx_play"),
    ("Tutorial_gpx_stats",      "gpx",           "gpx_info_size"),
    # the share button is on the route view: while a tour runs the bar shows the tour's own buttons
    ("Tutorial_gpx_share",      "route",         "gpx_share"),
    ("Tutorial_gallery",        "base",          "gallery"),
    ("Tutorial_camera",         "base",          "camera"),
    ("Tutorial_photo_match",    "photo",         "photo_match"),
    ("Tutorial_photo_outlines", "photo",         "photo_outline_bar"),
    ("Tutorial_photo_terrain",  "photo_terrain", "terrain_bar"),
    ("Tutorial_photo_pin",      "photo_pin",     "unpin"),
    ("Tutorial_photo_close",    "photo",         "photo_close"),
    ("Tutorial_help",           "base",          "help"),
]


def scale_to_jpeg(src, dst, width):
    """The picture at `width` pixels wide as a JPEG - Pillow where there is one, sips on a Mac."""
    try:
        from PIL import Image
        image = Image.open(src).convert("RGB")
        height = round(image.height * width / image.width)
        image.resize((width, height), Image.LANCZOS).save(dst, "JPEG", quality=82, optimize=True)
        return
    except ImportError:
        pass
    if not shutil.which("sips"):
        raise SystemExit("need either Pillow or sips to scale " + src)
    shutil.copyfile(src, dst)
    subprocess.run(["sips", "--resampleWidth", str(width), "-s", "format", "jpeg",
                    "-s", "formatOptions", "82", dst, "--out", dst],
                   check=True, capture_output=True)


def marker_for(bounds, widget, name):
    """The ring's centre and half-size, in fractions of the picture, from the widget's place."""
    widgets = bounds.get("widgets", {})
    if widget not in widgets:
        print("warning: no widget %s in %s - slide without a ring" % (widget, name), file=sys.stderr)
        return None
    w = widgets[widget]
    iw, ih = float(bounds["width"]), float(bounds["height"])
    cx, cy = w["x"] + w["w"] / 2, w["y"] + w["h"] / 2
    unit = min(iw, ih) / 10          # the app's own widget unit
    # A little larger than a button; a bar gets an ellipse along it.
    rw = max(0.85 * w["w"], 0.8 * unit) if w["w"] <= 2 * unit else 0.6 * w["w"]
    rh = max(0.85 * w["h"], 0.8 * unit) if w["h"] <= 2 * unit else 0.6 * w["h"]
    return {"cx": round(cx / iw, 4), "cy": round(cy / ih, 4),
            "rw": round(rw / iw, 4), "rh": round(rh / ih, 4)}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("captures", help="directory with <view>.png and <view>.widgets.json")
    ap.add_argument("--width", type=int, default=620, help="width of the pictures written into the app")
    args = ap.parse_args()

    views = sorted({view for _, view, _ in SLIDES})
    have = {}
    for view in views:
        png = os.path.join(args.captures, view + ".png")
        if not os.path.exists(png):
            print("warning: no picture for view " + view, file=sys.stderr)
            continue
        name = "tutorial_%s.jpg" % view
        scale_to_jpeg(png, os.path.join(INFO, name), args.width)
        bounds_file = os.path.join(args.captures, view + ".widgets.json")
        bounds = json.load(open(bounds_file)) if os.path.exists(bounds_file) else {}
        have[view] = (name, bounds)
        print("wrote", name)

    slides = []
    for key, view, widget in SLIDES:
        if view not in have:
            continue
        name, bounds = have[view]
        marker = None
        if widget is not None:
            if not bounds:
                print("warning: no widget places for " + view, file=sys.stderr)
            else:
                marker = marker_for(bounds, widget, name)
        slides.append({"image": name, "key": key, "marker": marker})

    page = open(PAGE, encoding="utf-8").read()
    block = ("// SLIDES-BEGIN (written by tools/tutorial_slides.py)\nconst SLIDES = "
             + json.dumps(slides, indent=2, ensure_ascii=False) + ";\n// SLIDES-END")
    page, n = re.subn(r"// SLIDES-BEGIN.*?// SLIDES-END", block, page, flags=re.S)
    if n != 1:
        raise SystemExit("no SLIDES markers in " + PAGE)
    open(PAGE, "w", encoding="utf-8", newline="\n").write(page)
    print("wrote %d slides over %d pictures into %s" % (len(slides), len(have), PAGE))
    missing = [k for k, v, _ in SLIDES if v not in have]
    if missing:
        print("slides left out:", ", ".join(missing), file=sys.stderr)


if __name__ == "__main__":
    main()
