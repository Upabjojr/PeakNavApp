#!/usr/bin/env python3
"""Pulls the world's well-known mountains out of Wikidata into a peaks TSV.

    python3 tools/fetch_wikidata_peaks.py peaks_wikidata.tsv [min_sitelinks]

Needs nothing but the standard library and a network connection. The TSV feeds
:core:addPeaksToIndex, in the same shape tools/extract_osm_peaks.py writes, plus two columns:
the number of Wikipedia articles about the peak, which the appender ranks by, and its country.

Why this exists next to the OSM extraction: that one reads .osm.pbf extracts, so the index
only holds the peaks of whichever countries were downloaded - the Alps, in practice. Everest,
Kilimanjaro, Aconcagua, Denali and Fuji were simply not in the search. Wikidata has every
mountain an encyclopedia has an article about, worldwide, with its languages' names.

"Well known" is the number of Wikipedia articles about the item (its sitelinks): ten or more
by default. Mountains, volcanoes, massifs, summits and hills are all included, by walking
the subclasses of those Wikidata classes - Kilimanjaro, for instance, is a "massif" and a
"dormant volcano" rather than a "mountain".

Columns: lat, lon, ele, wikidata(always 1), name, alternates ('|'-separated), sitelinks,
country (ISO 3166-1 alpha-2, '/'-joined for a summit on a border: "Mont Blanc (FR/IT)").
The name is the English label, or the language-neutral one where there is no English; every
other language's label and the English aliases become search aliases, so "Cervino",
"Fuji-san" and "Sagarmatha" all find their mountain.
"""
import csv
import io
import json
import sys
import time
import unicodedata
import urllib.parse
import urllib.request

ENDPOINT = "https://query.wikidata.org/sparql"
# Wikidata asks every tool to identify itself, and blocks the ones that do not.
USER_AGENT = "PeakNavIndexBuilder/1.0 (https://peaknav.com)"
#: The roots whose subclasses are all taken: mountain, volcano, summit, massif, hill,
#: mountain range and alpine group. Ranges are in because their names are what people
#: search for - "Dolomiti", "Karwendel" - and a range's point is a good place to fly to.
#: Deliberately NOT "landform": its subclasses reach islands and island countries, and an
#: earlier run indexed Japan and the United Kingdom as mountains.
ROOTS = ("Q8502", "Q8072", "Q207326", "Q1061151", "Q54050", "Q46831", "Q3777462")
#: The app's own interface languages, plus "mul" for the language-neutral label.
LANGS = ("en", "it", "de", "fr", "es", "pt", "nb", "mul")
#: Aliases that describe rather than name, which would otherwise match those words.
BAD_ALIAS_WORDS = ("world", "highest", "list of", "mountain of")
BATCH = 300


def query(sparql, attempts=5):
    """The endpoint's answer as parsed CSV rows; retries, since it rate-limits and times out."""
    data = urllib.parse.urlencode({"query": sparql}).encode()
    request = urllib.request.Request(ENDPOINT, data=data, headers={
        "Accept": "text/csv", "User-Agent": USER_AGENT,
        "Content-Type": "application/x-www-form-urlencoded"})
    for attempt in range(attempts):
        try:
            with urllib.request.urlopen(request, timeout=300) as response:
                text = response.read().decode("utf-8")
            # A CSV answer starts with its header: bare variable names, no spaces or markup.
            # Errors and rate-limit pages are HTML or prose, and a timeout one line of text.
            text = text.lstrip("﻿")
            header = text.split("\n", 1)[0].strip()
            if header and " " not in header and "<" not in header:
                return list(csv.DictReader(io.StringIO(text)))
            print("  unexpected answer: %s" % text[:120].replace("\n", " "), file=sys.stderr)
        except Exception as e:                                  # network, timeout, 429
            print("  %s" % e, file=sys.stderr)
        time.sleep(15)
    raise SystemExit("Wikidata did not answer after %d attempts" % attempts)


def classes():
    roots = " ".join("wd:" + r for r in ROOTS)
    rows = query("SELECT DISTINCT ?c WHERE { VALUES ?root { %s } ?c wdt:P279* ?root . }" % roots)
    return [r["c"].rsplit("/", 1)[1] for r in rows if r["c"].rsplit("/", 1)[1].startswith("Q")]


def mountains(class_ids, min_links):
    """Every item of those classes with coordinates and enough articles: position and height."""
    found = {}
    for i in range(0, len(class_ids), BATCH):
        values = " ".join("wd:" + c for c in class_ids[i:i + BATCH])
        rows = query("""SELECT ?item (SAMPLE(?coord) AS ?c) (MAX(?e) AS ?ele) (SAMPLE(?links) AS ?l) WHERE {
  VALUES ?class { %s }
  ?item wdt:P31 ?class ; wikibase:sitelinks ?links ; wdt:P625 ?coord .
  FILTER(?links >= %d)
  OPTIONAL { ?item p:P2044/psn:P2044/wikibase:quantityAmount ?e . }
} GROUP BY ?item""" % (values, min_links))
        for row in rows:
            found[row["item"].rsplit("/", 1)[1]] = row
        print("  classes %d/%d, %d mountains" % (min(i + BATCH, len(class_ids)), len(class_ids), len(found)))
        time.sleep(1)
    return found


def labels(item_ids):
    """Each item's labels in LANGS and its English aliases."""
    out = {}
    langs = " ".join('"%s"' % l for l in LANGS)
    for i in range(0, len(item_ids), 400):
        values = " ".join("wd:" + q for q in item_ids[i:i + 400])
        rows = query("""SELECT ?item ?lang ?kind ?text WHERE {
  VALUES ?item { %s }
  VALUES ?lang { %s }
  { ?item rdfs:label ?text . BIND("label" AS ?kind) }
  UNION { ?item skos:altLabel ?text . BIND("alt" AS ?kind) FILTER(LANG(?text) = "en") }
  FILTER(LANG(?text) = ?lang)
}""" % (values, langs))
        for row in rows:
            item = row["item"].rsplit("/", 1)[1]
            key = row["kind"] + ":" + row["lang"]
            out.setdefault(item, {}).setdefault(key, []).append(row["text"].replace("\t", " ").strip())
        print("  labels %d/%d" % (min(i + 400, len(item_ids)), len(item_ids)))
        time.sleep(1)
    return out


def countries(item_ids):
    """Each item's country codes, in order, as one '/'-joined string."""
    out = {}
    for i in range(0, len(item_ids), 400):
        values = " ".join("wd:" + q for q in item_ids[i:i + 400])
        rows = query("""SELECT ?item ?iso WHERE {
  VALUES ?item { %s }
  ?item wdt:P17 ?country .
  ?country wdt:P297 ?iso .
}""" % values)
        for row in rows:
            item = row["item"].rsplit("/", 1)[1]
            code = row["iso"].strip().upper()
            if code and code not in out.setdefault(item, []):
                out[item].append(code)
        print("  countries %d/%d" % (min(i + 400, len(item_ids)), len(item_ids)))
        time.sleep(1)
    return {item: "/".join(sorted(codes)) for item, codes in out.items()}


def has_letters(text):
    return any(unicodedata.category(c)[0] in "LN" for c in text)


def usable_alias(text):
    low = text.lower()
    return has_letters(text) and not any(w in low for w in BAD_ALIAS_WORDS)


def point(wkt):
    """"Point(lon lat)" as (lat, lon), or None."""
    if not wkt.startswith("Point("):
        return None
    lon, _, lat = wkt[6:-1].partition(" ")
    try:
        return float(lat), float(lon)
    except ValueError:
        return None


def main():
    out_path = sys.argv[1] if len(sys.argv) > 1 else "peaks_wikidata.tsv"
    min_links = int(sys.argv[2]) if len(sys.argv) > 2 else 10
    print("subclasses of mountain, volcano, summit, massif, hill, range and alpine group")
    class_ids = classes()
    print("  %d classes" % len(class_ids))
    print("mountains with at least %d Wikipedia articles" % min_links)
    found = mountains(class_ids, min_links)
    print("names")
    named = labels(sorted(found))
    print("countries")
    in_country = countries(sorted(found))

    written = skipped = 0
    rows = []
    for item, row in found.items():
        position = point(row["c"])
        if position is None:
            skipped += 1
            continue
        ele = int(float(row["ele"])) if row["ele"] else 0
        if not 0 < ele < 8900:      # a bad or missing height is left empty, not guessed
            ele = 0
        by_key = named.get(item, {})
        names = []
        for key in ("label:en", "label:mul") + tuple("label:" + l for l in LANGS if l not in ("en", "mul")):
            names += [n for n in by_key.get(key, []) if has_letters(n)]
        if not names:
            skipped += 1
            continue
        aliases = [a for a in by_key.get("alt:en", []) if usable_alias(a)]
        display = names[0]
        extra = []
        for name in names[1:] + aliases:
            if name != display and name not in extra:
                extra.append(name)
        rows.append((int(row["l"]), position[0], position[1], ele, display, extra,
                     in_country.get(item, "")))
        written += 1

    rows.sort(key=lambda r: -r[0])      # best known first, so a truncated run still has them
    with open(out_path, "w", encoding="utf-8", newline="\n") as out:
        for links, lat, lon, ele, display, extra, country in rows:
            out.write("%.5f\t%.5f\t%s\t1\t%s\t%s\t%d\t%s\n"
                      % (lat, lon, ele or "", display, "|".join(extra), links, country))
    print("wrote %d peaks to %s (skipped %d without a name or a position)" % (written, out_path, skipped))


if __name__ == "__main__":
    main()
