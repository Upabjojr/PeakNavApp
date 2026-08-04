#!/usr/bin/env python3
"""Pulls every named peak out of a set of .osm.pbf extracts into one TSV.

    python3 tools/extract_osm_peaks.py peaks.tsv /path/to/pbf_dir

Needs pyosmium (pip install osmium). The TSV feeds :core:addPeaksToIndex.

Nodes tagged natural=peak or natural=volcano that carry a name. The extracts overlap at
their borders - the Matterhorn sits in both the Swiss and the Italian file - so peaks are
deduplicated by name and rounded position across the whole run, not per file.

Columns: lat, lon, ele, wikidata(0/1), name, alternates ('|'-separated).
The elevation is cleaned here, once, rather than in every later consumer: OSM ele tags
carry "4478", "4478 m", "2 700", "ca. 1200" and worse.
"""
import glob
import os
import re
import sys

import osmium

PBF_DIR = sys.argv[2] if len(sys.argv) > 2 else "."
OUT = sys.argv[1] if len(sys.argv) > 1 else "peaks.tsv"

#: The name:* languages worth carrying as search aliases - the app's own interface
#: languages. Every alias costs index bytes, so this is a whitelist, not name:*.
ALIAS_KEYS = ("name:en", "name:it", "name:fr", "name:de", "name:es", "name:pt", "name:no",
              "alt_name", "int_name")

_num = re.compile(r"(\d+(?:[.,]\d+)?)")


def clean_ele(raw):
    if not raw:
        return ""
    m = _num.search(raw.replace(" ", "").replace(" ", ""))
    if not m:
        return ""
    v = float(m.group(1).replace(",", "."))
    if "ft" in raw or "'" in raw:
        v *= 0.3048
    return str(int(v)) if 0 < v < 8900 else ""


class PeakHandler(osmium.SimpleHandler):
    def __init__(self, out):
        super().__init__()
        self.out = out
        self.seen = set()
        self.kept = 0

    def node(self, n):
        t = n.tags
        if t.get("natural") not in ("peak", "volcano"):
            return
        name = t.get("name")
        if not name:
            return
        # One entry per mountain, however many files it appears in. ~110 m grid: border
        # duplicates land on the same node id in both files, but ids are not guaranteed
        # stable between extract dates, so position+name is the safer key.
        key = (round(n.location.lat, 3), round(n.location.lon, 3), name)
        if key in self.seen:
            return
        self.seen.add(key)
        alts = []
        for k in ALIAS_KEYS:
            v = t.get(k)
            if v and v != name and v not in alts:
                alts.append(v)
        row = "\t".join((
            f"{n.location.lat:.5f}", f"{n.location.lon:.5f}",
            clean_ele(t.get("ele")),
            "1" if ("wikidata" in t or "wikipedia" in t) else "0",
            name.replace("\t", " "),
            "|".join(a.replace("\t", " ").replace("|", "/") for a in alts),
        ))
        self.out.write(row + "\n")
        self.kept += 1


def main():
    files = sorted(glob.glob(os.path.join(PBF_DIR, "*.osm.pbf")))
    with open(OUT, "w", encoding="utf-8") as out:
        handler = PeakHandler(out)
        for path in files:
            before = handler.kept
            handler.apply_file(path)
            print(f"{os.path.basename(path)}: +{handler.kept - before} "
                  f"(total {handler.kept})", flush=True)
    print(f"done: {handler.kept} peaks -> {OUT}")


if __name__ == "__main__":
    main()
