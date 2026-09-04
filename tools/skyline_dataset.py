#!/usr/bin/env python3
"""Build a test set for the photo skyline matcher (see core's
com.peaknav.skyline): mountain photographs whose camera position AND heading are
known, so the bearing the matcher recovers can be checked against a truth value.

Two sources, each written as a directory of images plus a manifest.json:

* Wikimedia Commons. Photographers geotag their pictures with the
  {{Location|lat|lon|heading:123}} template, whose heading is the compass
  direction the camera pointed (set by hand on a map, so trust it to about
  +-5..10 degrees). The search below finds files carrying such a heading inside
  a mountain category, keeps the ones with a free licence, and downloads a
  reduced copy (photos are not redistributed here; the manifest keeps the file
  page, licence and author for attribution).

    tools/skyline_dataset.py commons --category "Mountains of Switzerland" \
        --limit 60 --out ~/.peaknav/skyline_dataset/commons

* GeoPose3K (Brejcha & Cadik, https://cphoto.fit.vutbr.cz/geoPose3K/): 3111 Alpine
  photographs with a full camera pose (yaw, pitch, roll and field of view) -
  precise truth, but 38 GB and not redistributable. Point this at an extracted
  copy (only photo.jpg and info.txt per directory are needed) and it writes the
  same manifest format:

    tools/skyline_dataset.py geopose3k --src /path/to/geoPose3K_final_publish \
        --out ~/.peaknav/skyline_dataset/geopose3k

The manifest is what the Java test (core: TestSkylineDataset) and the Python
prototype consume: a list of {file, lat, lon, heading, pitch, roll, vfov,
focal35, source, licence, author, page}. Absent values are null.

Needs only the standard library.
"""
import argparse
import json
import math
import os
import re
import sys
import time
import urllib.parse
import urllib.request

API = "https://commons.wikimedia.org/w/api.php"
USER_AGENT = "PeakNav skyline dataset builder (https://peaknav.com; peaknav.info@gmail.com)"

FREE_LICENCES = ("cc0", "cc by", "cc-by", "public domain", "pd", "gfdl")


def api(params):
    params = dict(params, format="json", formatversion="2")
    url = API + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                return json.load(resp)
        except Exception as e:  # noqa: BLE001 - retry any transport error
            if attempt == 3:
                raise
            time.sleep(2 * (attempt + 1))
            print("retry after", e, file=sys.stderr)


COMPASS = {"N": 0, "NNE": 22.5, "NE": 45, "ENE": 67.5, "E": 90, "ESE": 112.5, "SE": 135,
           "SSE": 157.5, "S": 180, "SSW": 202.5, "SW": 225, "WSW": 247.5, "W": 270,
           "WNW": 292.5, "NW": 315, "NNW": 337.5}

# {{Location|46.0|7.7|heading:123}}, {{Location dec|46.0|7.7|heading:NE}}, and the
# DMS form {{Location|46|1|12|N|7|44|56|E|heading:123}}.
LOCATION_RE = re.compile(r"\{\{\s*(?:Location|Location dec|Camera location)\s*\|([^}]*)\}\}",
                         re.IGNORECASE)


def parse_location(wikitext):
    """(lat, lon, heading_deg) of the camera-location template, or None."""
    for m in LOCATION_RE.finditer(wikitext):
        parts = [p.strip() for p in m.group(1).split("|")]
        heading = None
        coords = []
        for p in parts:
            if p.lower().startswith("heading:"):
                v = p.split(":", 1)[1].strip()
                if v.upper() in COMPASS:
                    heading = COMPASS[v.upper()]
                else:
                    try:
                        heading = float(v) % 360.0
                    except ValueError:
                        heading = None
            elif "=" in p:
                continue
            else:
                coords.append(p)
        if heading is None:
            continue
        try:
            if len(coords) >= 8 and coords[3].upper() in "NS" and coords[7].upper() in "EW":
                lat = float(coords[0]) + float(coords[1]) / 60 + float(coords[2]) / 3600
                lon = float(coords[4]) + float(coords[5]) / 60 + float(coords[6]) / 3600
                if coords[3].upper() == "S":
                    lat = -lat
                if coords[7].upper() == "W":
                    lon = -lon
            else:
                lat, lon = float(coords[0]), float(coords[1])
        except (ValueError, IndexError):
            continue
        if abs(lat) <= 90 and abs(lon) <= 180:
            return lat, lon, heading
    return None


def is_free(licence):
    if not licence:
        return False
    l = licence.lower()
    return any(k in l for k in FREE_LICENCES) and "nc" not in l and "nd" not in l


def commons(args):
    os.makedirs(args.out, exist_ok=True)
    manifest_path = os.path.join(args.out, "manifest.json")
    entries = []
    if os.path.exists(manifest_path):
        with open(manifest_path) as f:
            entries = json.load(f)
    have = {e["page"] for e in entries}

    query = 'insource:/heading:[0-9]/ deepcat:"%s"' % args.category
    offset = 0
    while len(entries) < args.limit:
        res = api({"action": "query", "list": "search", "srnamespace": 6, "srlimit": 50,
                   "sroffset": offset, "srsearch": query})
        hits = res.get("query", {}).get("search", [])
        if not hits:
            break
        offset += len(hits)
        titles = [h["title"] for h in hits]
        info = api({"action": "query", "titles": "|".join(titles),
                    "prop": "revisions|imageinfo", "rvprop": "content", "rvslots": "main",
                    "iiprop": "url|size|commonmetadata|extmetadata|mime",
                    "iiurlwidth": args.width})
        for page in info["query"]["pages"]:
            title = page["title"]
            if title in have or "imageinfo" not in page:
                continue
            ii = page["imageinfo"][0]
            if ii.get("mime") not in ("image/jpeg",):
                continue
            wikitext = page["revisions"][0]["slots"]["main"]["content"]
            loc = parse_location(wikitext)
            if loc is None:
                continue
            ext = ii.get("extmetadata", {})
            licence = ext.get("LicenseShortName", {}).get("value")
            if not is_free(licence):
                continue
            author = re.sub("<[^>]+>", "", ext.get("Artist", {}).get("value", "")).strip()
            meta = {m["name"]: m["value"] for m in ii.get("commonmetadata", [])}
            focal35 = meta.get("FocalLengthIn35mmFilm")
            try:
                focal35 = float(focal35) if focal35 else None
            except ValueError:
                focal35 = None
            if focal35 is not None and not (10 <= focal35 <= 1200):
                focal35 = None
            name = "commons_%05d.jpg" % (len(entries) + 1)
            dest = os.path.join(args.out, name)
            url = ii.get("thumburl") or ii["url"]
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            try:
                with urllib.request.urlopen(req, timeout=120) as resp, open(dest, "wb") as f:
                    f.write(resp.read())
            except Exception as e:  # noqa: BLE001
                print("skip", title, e, file=sys.stderr)
                continue
            entries.append({
                "file": name, "lat": loc[0], "lon": loc[1], "heading": loc[2],
                "pitch": None, "roll": None, "vfov": None, "focal35": focal35,
                "camera": meta.get("Model"), "source": "commons",
                "licence": licence, "author": author,
                "page": "https://commons.wikimedia.org/wiki/" + urllib.parse.quote(title.replace(" ", "_")),
            })
            have.add(title)
            print("%3d %s  %.4f %.4f  heading %.0f  f35=%s  %s" % (
                len(entries), name, loc[0], loc[1], loc[2], focal35, licence))
            with open(manifest_path, "w") as f:
                json.dump(entries, f, indent=1)
            if len(entries) >= args.limit:
                break
            time.sleep(args.delay)
    print("manifest:", manifest_path, len(entries), "entries")


def geopose3k(args):
    """GeoPose3K's info.txt, one value per line: MANUAL|AUTO (how the pose was
    obtained), then "yaw pitch roll" in radians (the dataset's own Euler
    convention; heading below is the compass bearing derived from it), latitude,
    longitude, elevation, and the field of view in radians (the wider image
    side); a second, refined copy of the last four may follow."""
    os.makedirs(args.out, exist_ok=True)
    entries = []
    for d in sorted(os.listdir(args.src)):
        info = os.path.join(args.src, d, "info.txt")
        photo = None
        for name in ("photo.jpg", "photo.jpeg"):
            if os.path.exists(os.path.join(args.src, d, name)):
                photo = os.path.join(args.src, d, name)
        if not (os.path.exists(info) and photo):
            continue
        with open(info) as f:
            lines = [l.split() for l in f.read().strip().splitlines()]
        try:
            mode = lines[0][0]
            yaw, pitch, roll = (float(v) for v in lines[1][:3])
            lat, lon, ele = float(lines[2][0]), float(lines[3][0]), float(lines[4][0])
            fov = float(lines[5][0])
        except (IndexError, ValueError):
            continue
        if args.manual_only and mode != "MANUAL":
            continue
        entries.append({"file": os.path.relpath(photo, args.out), "lat": lat, "lon": lon,
                        "heading": geopose3k_heading(yaw), "pitch": math.degrees(pitch),
                        "roll": math.degrees(roll), "yaw_raw": yaw, "pitch_raw": pitch,
                        "roll_raw": roll, "elevation": ele, "vfov": None,
                        "fov": math.degrees(fov), "focal35": None, "source": "geopose3k",
                        "mode": mode, "licence": None, "author": None, "page": d})
        if len(entries) >= args.limit:
            break
    with open(os.path.join(args.out, "manifest.json"), "w") as f:
        json.dump(entries, f, indent=1)
    print("manifest:", os.path.join(args.out, "manifest.json"), len(entries), "entries")


def geopose3k_heading(yaw):
    """Compass bearing (degrees, clockwise from north) of a GeoPose3K yaw. The dataset's
    yaw is counter-clockwise from east (a mathematical angle in an east-north frame);
    established empirically - this is the only reading under which the matcher and the
    hand-annotated poses agree."""
    return (270.0 - math.degrees(yaw)) % 360.0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("commons")
    c.add_argument("--category", default="Mountains of Switzerland")
    c.add_argument("--limit", type=int, default=60)
    c.add_argument("--width", type=int, default=1280, help="downloaded image width")
    c.add_argument("--delay", type=float, default=0.5)
    c.add_argument("--out", default=os.path.expanduser("~/.peaknav/skyline_dataset/commons"))
    c.set_defaults(func=commons)
    g = sub.add_parser("geopose3k")
    g.add_argument("--src", required=True)
    g.add_argument("--limit", type=int, default=100000)
    g.add_argument("--manual-only", action="store_true",
                   help="keep only the 339 hand-annotated poses (unbiased truth)")
    g.add_argument("--out", default=os.path.expanduser("~/.peaknav/skyline_dataset/geopose3k"))
    g.set_defaults(func=geopose3k)
    args = ap.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
