#!/usr/bin/env python3
"""Regenerates the "?" tutorial's screenshots and slides from the app as it is now.

The pictures are taken by the headless renderer with its widgets drawn, over the REST
API, and every marker on them is placed where the renderer says the widget is - so the
tutorial cannot drift from the app: when a button moves or a feature appears, run this
again and the pictures and circles follow.

    ./gradlew :headless:renderJar
    DISPLAY=:1 python3 tools/tutorial_screenshots.py [--photo ~/.peaknav/skyline_demo/zermatt_matterhorn_241.jpg]

Writes assets/info/image*.jpg and the slide data between the SLIDES markers of
assets/info/app_tutorial.html. Needs the elevation tiles of Zermatt (downloaded on the
spot when missing) and a geotagged photo taken near Zermatt for the photo slides.
"""
import argparse
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(ROOT, "peaknav-python"))
from peaknav.headless import PeakNavHeadless  # noqa: E402

INFO = os.path.join(ROOT, "assets", "info")
PAGE = os.path.join(INFO, "app_tutorial.html")
ZERMATT = (46.0207, 7.7491)
WIDTH, HEIGHT = 1600, 756          # a phone held sideways, 20:9 or so

# The slides, in order: picture, the widget the ring points at (None for none), the
# caption and one line of explanation.
SLIDES = [
    ("imageBase.jpg", "gyro", "Gyroscope", "The view follows the phone as you turn it."),
    ("imageBase.jpg", "elevation_bar", "Elevation", "Slide up to rise above the ground - metres to kilometres."),
    ("imageBase.jpg", "gallery", "Gallery", "Put one of your photos behind the terrain."),
    ("imageBase.jpg", "camera", "Camera", "Take a picture right here and see the peaks on it."),
    ("imageBase.jpg", "search", "Search", "Find a peak, a hut or a place by name."),
    ("imageBase.jpg", "options", "Options", "Labels, imagery, sky, sun, units."),
    ("imageOptions.jpg", None, "Options", "Everything the view shows is switched on and off here."),
    ("imageBaseSat.jpg", None, "Satellite imagery", "Any of several imagery sources, chosen in the options."),
    ("imageBase.jpg", "share", "Share", "Save or share the view. The picture keeps where and which way it looks in its EXIF."),
    ("imageBase.jpg", "here", "Where I am", "Jump to your GPS position."),
    ("imageTap.jpg", "go_to", "Tap a point", "Tap the terrain: fly there, orbit it, or open it in your maps app."),
    ("imageGpx.jpg", "gpx_play", "GPX tracks", "Load a track and fly along it."),
    ("imagePhoto.jpg", "photo_match", "Match a photo", "With a photo behind the terrain, this points the camera the way the photo looks - by its skyline."),
    ("imagePhoto.jpg", "photo_outline_bar", "Outlines", "How visible the terrain's outlines are over the photo."),
    ("imagePhotoTerrain.jpg", "terrain_bar", "Terrain over the photo", "Fade the rendered terrain in over the picture, up to opaque."),
    ("imagePhotoPin.jpg", "unpin", "Pin a point", "Double-tap to pin a summit, then drag and pinch around it. This button, or another double tap, releases it."),
    ("imagePhoto.jpg", "photo_close", "Close the photo", "Back to the plain terrain."),
]


def jpeg_size(path):
    """(width, height) of a JPEG from its SOF marker, no imaging library needed."""
    with open(path, "rb") as f:
        data = f.read()
    i = 2
    while i < len(data) - 9:
        if data[i] != 0xFF:
            i += 1
            continue
        marker = data[i + 1]
        if marker in (0xC0, 0xC1, 0xC2):
            return int.from_bytes(data[i + 7:i + 9], "big"), int.from_bytes(data[i + 5:i + 7], "big")
        if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7:
            i += 2
            continue
        i += 2 + int.from_bytes(data[i + 2:i + 4], "big")
    raise ValueError("no SOF in " + path)


def gpx_around(lat, lon):
    """A short track along the valley floor near a point, enough to draw."""
    pts = []
    for i in range(24):
        # a bend in the middle, so it reads as a walk and not a ruler
        pts.append('<trkpt lat="%.5f" lon="%.5f"></trkpt>' % (
            lat - 0.006 + 0.0007 * i, lon - 0.010 + 0.0011 * i + (0.003 if 8 <= i <= 16 else 0)))
    return ('<gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1"><trk><name>walk</name><trkseg>'
            + "".join(pts) + "</trkseg></trk></gpx>")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--photo", default=os.path.expanduser("~/.peaknav/skyline_demo/zermatt_matterhorn_241.jpg"),
                    help="a geotagged photo taken near Zermatt, for the photo slides")
    ap.add_argument("--jar", default=None)
    args = ap.parse_args()

    bounds = {}   # picture name -> widgets json

    def shot(nav, name):
        nav.wait(settle_ms=800)
        bounds[name] = nav.widgets()
        nav.save_frame(os.path.join(INFO, name), ui=True)
        print("wrote", name)

    with PeakNavHeadless(*ZERMATT, jar=args.jar, width=WIDTH, height=HEIGHT) as nav:
        nav.move_to(*ZERMATT, download_timeout_ms=900_000, await_tiles_ms=180_000)
        nav.set_view(sky=True, sky_mode="day", constellations=False, star_names=False, sky_labels=False,
                     labels=["peaks", "place_names", "alpine_huts", "lakes"], horizon_compass=True)
        nav.look(bearing_deg=232, pitch_deg=6)
        nav.set_elevation_above_ground(40)
        nav.wait(tiles_timeout_ms=120_000, settle_ms=1_500)
        shot(nav, "imageBase.jpg")

        nav.set_view(options_pane=True)
        shot(nav, "imageOptions.jpg")
        nav.set_view(options_pane=False)

        providers = nav.providers()
        print("imagery providers:", providers)
        preferred = ["esri", "world imagery", "usgs", "sentinel", "google", "bing"]
        satellite = None
        for key in preferred:
            satellite = next((p["id"] for p in providers if key in (p["id"] + " " + p["name"]).lower()), None)
            if satellite:
                break
        if satellite is None and len(providers) > 1:
            satellite = providers[1]["id"]
        if satellite:
            nav.set_satellite(provider_id=satellite)
            quiet = nav.wait(tiles_timeout_ms=400_000, settle_ms=2_000)
            print("satellite", satellite, "quiet", quiet)
            if quiet.get("quiet", True):
                shot(nav, "imageBaseSat.jpg")
            else:
                print("warning: the satellite tiles never arrived; no satellite slide", file=sys.stderr)
            nav.set_satellite(provider_id=providers[0]["id"])
            nav.wait(tiles_timeout_ms=300_000, settle_ms=1_000)

        # The photo slides: the photo goes to its own position, the matcher points the camera.
        if os.path.exists(args.photo):
            info = nav.load_photo(args.photo, download_timeout_ms=900_000, await_tiles_ms=180_000)
            match = nav.match_photo()
            print("photo", info.get("location"), "match", match.get("bearing_deg"), match.get("confident"))
            nav.set_photo_overlay(outline_alpha=0.9, terrain_alpha=0)
            shot(nav, "imagePhoto.jpg")
            nav.set_photo_overlay(terrain_alpha=0.55)
            shot(nav, "imagePhotoTerrain.jpg")
            nav.set_photo_overlay(terrain_alpha=0)
            nav.pin_photo(WIDTH * 0.55, HEIGHT * 0.42)
            shot(nav, "imagePhotoPin.jpg")
            nav.unpin_photo()
            nav.clear_photo()
            nav.move_to(*ZERMATT, await_tiles_ms=120_000)
            nav.look(bearing_deg=232, pitch_deg=6)
            nav.set_elevation_above_ground(40)
        else:
            print("no photo at", args.photo, "- photo slides skipped", file=sys.stderr)

        # The track runs north-east across the valley; look along it from behind its start.
        nav.load_gpx(xml=gpx_around(*ZERMATT))
        nav.move_to(ZERMATT[0] - 0.014, ZERMATT[1] - 0.020, await_tiles_ms=120_000)
        nav.look(bearing_deg=48, pitch_deg=-22)
        nav.set_elevation_above_ground(700)
        nav.wait(settle_ms=1_500)
        shot(nav, "imageGpx.jpg")
        nav.move_to(*ZERMATT, await_tiles_ms=120_000)

        nav.look(bearing_deg=232, pitch_deg=6)
        nav.set_elevation_above_ground(40)
        nav.wait(settle_ms=800)
        nav.tap(int(WIDTH * 0.5), int(HEIGHT * 0.62))
        shot(nav, "imageTap.jpg")

    # Slide data: the ring's centre and size from the widget's bounds, as fractions of the
    # picture, so the page needs no idea of the window size.
    slides = []
    for image, widget, text, detail in SLIDES:
        if image not in bounds:
            continue
        b = bounds[image]
        # a picture over a photo is cropped to the photo's drawn size, centred in the window
        iw, ih = jpeg_size(os.path.join(INFO, image))
        ox, oy = (b["width"] - iw) / 2, (b["height"] - ih) / 2
        marker = None
        if widget is not None:
            w = b["widgets"].get(widget)
            if w is None:
                print("warning: no widget", widget, "in", image, "- slide without a marker", file=sys.stderr)
            else:
                cx, cy = w["x"] + w["w"] / 2 - ox, w["y"] + w["h"] / 2 - oy
                if not (0 <= cx <= iw and 0 <= cy <= ih):
                    print("warning:", widget, "lies outside the cropped", image, file=sys.stderr)
                unit = min(b["width"], b["height"]) / 10   # the app's widget unit
                # a ring a little larger than a button; a bar gets an ellipse along it
                rw = max(0.85 * w["w"], 0.8 * unit) if w["w"] <= 2 * unit else 0.6 * w["w"]
                rh = max(0.85 * w["h"], 0.8 * unit) if w["h"] <= 2 * unit else 0.6 * w["h"]
                marker = {"cx": round(cx / iw, 4), "cy": round(cy / ih, 4),
                          "rw": round(rw / iw, 4), "rh": round(rh / ih, 4)}
        slides.append({"image": image, "text": text, "detail": detail, "marker": marker})
    print("widget bounds:", json.dumps(bounds)[:2000])
    page = open(PAGE, encoding="utf-8").read()
    block = "// SLIDES-BEGIN (written by tools/tutorial_screenshots.py)\nconst SLIDES = " \
            + json.dumps(slides, indent=2, ensure_ascii=False) + ";\n// SLIDES-END"
    new_page, n = re.subn(r"// SLIDES-BEGIN.*?// SLIDES-END", block, page, flags=re.S)
    if n != 1:
        raise SystemExit("no SLIDES markers in " + PAGE)
    open(PAGE, "w", encoding="utf-8", newline="\n").write(new_page)
    print("wrote", len(slides), "slides into", PAGE)
    print("images:", sorted(set(s["image"] for s in slides)))


if __name__ == "__main__":
    main()
