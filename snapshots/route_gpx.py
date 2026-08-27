#!/usr/bin/env python3
"""Builds a GPX track through named waypoints, routed over OpenStreetMap's paths.

    python3 snapshots/route_gpx.py out.gpx "Name of the route" \\
        "Whitney Portal=36.5868,-118.2397" "Trail Camp=36.5641,-118.2744" \\
        "Mount Whitney=36.5786,-118.2921"

For each consecutive pair of waypoints the mapped paths, tracks and footways in a box
around them are fetched from Overpass, the waypoints are snapped to the nearest path
node, and the shortest path between them is taken. Where no path connects them - the
glacier legs of a big-mountain route are seldom mapped - the leg is a straight line,
subdivided every ~50 m so that the flight (snapshots/render_gpx.py) still gets evenly
spaced points. The result is honest about this: the GPX's description lists the legs and
which kind each is.

Elevations are not written; render_gpx.py fetches them from the elevation service and
caches them next to the file.

Geometry: (c) OpenStreetMap contributors, ODbL.
"""

import heapq
import json
import math
import sys
import time
import urllib.parse
import urllib.request

OVERPASS = "https://overpass-api.de/api/interpreter"
USER_AGENT = "PeakNav route_gpx/1.0 (https://peaknav.com)"
SNAP_MAX_M = 400.0        # a waypoint further than this from any path is off the network
DETOUR_MAX = 3.0          # a routed leg more than this times the straight line is a detour
STRAIGHT_STEP_M = 50.0
M_PER_DEG = 111320.0


def dist(a, b, k):
    return math.hypot((a[0] - b[0]) * M_PER_DEG, (a[1] - b[1]) * M_PER_DEG * k)


def overpass(query):
    data = urllib.parse.urlencode({"data": query}).encode()
    for attempt in range(6):
        try:
            req = urllib.request.Request(OVERPASS, data=data, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=240) as r:
                result = json.load(r)
            # A throttled or timed-out query comes back as a well-formed answer with a
            # remark and no elements; taken at face value it reads as "no paths here"
            # and turns a mapped trail into a straight line.
            if "remark" in result and not result.get("elements"):
                raise RuntimeError(result["remark"][:120])
            return result
        except Exception as e:  # noqa: BLE001 - retried, then reported
            print(f"  overpass: {e}; retrying", file=sys.stderr)
            time.sleep(20 * (attempt + 1))
    sys.exit("error: Overpass kept failing")


def path_graph(a, b, k):
    """Adjacency of the path network in a box around two points, padded by a third of
    their distance (at least 2 km) so the route can swing around ridges."""
    pad_deg = max(2000.0, dist(a, b, k) / 3.0) / M_PER_DEG
    south, north = min(a[0], b[0]) - pad_deg, max(a[0], b[0]) + pad_deg
    west, east = min(a[1], b[1]) - pad_deg / k, max(a[1], b[1]) + pad_deg / k
    q = (f'[out:json][timeout:180];way["highway"~"path|track|footway|steps|bridleway"]'
         f'({south:.5f},{west:.5f},{north:.5f},{east:.5f});out geom;')
    adj = {}
    for w in overpass(q).get("elements", []):
        nodes = [(n["lat"], n["lon"]) for n in w.get("geometry", [])]
        for p, q2 in zip(nodes, nodes[1:]):
            d = dist(p, q2, k)
            adj.setdefault(p, []).append((q2, d))
            adj.setdefault(q2, []).append((p, d))
    return adj


def shortest(adj, start, goal):
    best = {start: 0.0}
    prev = {}
    heap = [(0.0, start)]
    while heap:
        c, n = heapq.heappop(heap)
        if n == goal:
            break
        if c > best.get(n, float("inf")):
            continue
        for m, w in adj.get(n, ()):
            nc = c + w
            if nc < best.get(m, float("inf")):
                best[m] = nc
                prev[m] = n
                heapq.heappush(heap, (nc, m))
    if goal not in best:
        return None, None
    path = [goal]
    while path[-1] != start:
        path.append(prev[path[-1]])
    return path[::-1], best[goal]


def straight(a, b, k):
    n = max(1, int(dist(a, b, k) / STRAIGHT_STEP_M))
    return [(a[0] + (b[0] - a[0]) * i / n, a[1] + (b[1] - a[1]) * i / n) for i in range(n + 1)]


def route(waypoints):
    """[(name, lat, lon)] -> (points, legs) with legs as (from, to, kind, length_m)."""
    k = math.cos(math.radians(sum(w[1] for w in waypoints) / len(waypoints)))
    points = []
    legs = []
    for (na, la, lo_a), (nb, lb, lo_b) in zip(waypoints, waypoints[1:]):
        a, b = (la, lo_a), (lb, lo_b)
        print(f"  {na} -> {nb}: ", end="", file=sys.stderr, flush=True)
        adj = path_graph(a, b, k)
        leg = None
        if adj:
            sa = min(adj, key=lambda n: dist(n, a, k))
            sb = min(adj, key=lambda n: dist(n, b, k))
            if dist(sa, a, k) <= SNAP_MAX_M and dist(sb, b, k) <= SNAP_MAX_M:
                path, length = shortest(adj, sa, sb)
                if path and length <= DETOUR_MAX * max(dist(a, b, k), 1.0):
                    leg = [a] + path + [b]
                    legs.append((na, nb, "paths", length))
                    print(f"{length / 1000:.1f} km over mapped paths", file=sys.stderr)
        if leg is None:
            leg = straight(a, b, k)
            legs.append((na, nb, "straight", dist(a, b, k)))
            print(f"{dist(a, b, k) / 1000:.1f} km straight (no mapped path)", file=sys.stderr)
        if points:
            leg = leg[1:]
        points.extend(leg)
        time.sleep(6)  # be gentle with Overpass
    return points, legs


def write_gpx(path, name, points, legs, waypoints):
    desc = ("Route through waypoints, over OpenStreetMap paths where mapped (geometry (c) "
            "OpenStreetMap contributors, ODbL), straight between them elsewhere. Legs: "
            + "; ".join(f"{a} -> {b}: {kind}, {m / 1000:.1f} km" for a, b, kind, m in legs)
            + ". No elevations - render_gpx.py fetches them.")
    out = ['<?xml version="1.0" encoding="UTF-8"?>',
           '<gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="PeakNav route_gpx">',
           f"<metadata><name>{name}</name><desc>{desc}</desc></metadata>"]
    for wname, lat, lon in waypoints:
        out.append(f'<wpt lat="{lat:.6f}" lon="{lon:.6f}"><name>{wname}</name></wpt>')
    out.append(f"<trk><name>{name}</name><trkseg>")
    for lat, lon in points:
        out.append(f'<trkpt lat="{lat:.7f}" lon="{lon:.7f}"></trkpt>')
    out += ["</trkseg></trk></gpx>"]
    with open(path, "w") as f:
        f.write("\n".join(out) + "\n")


def main(argv):
    if len(argv) < 4:
        sys.exit(__doc__)
    out, name = argv[1], argv[2]
    waypoints = []
    for spec in argv[3:]:
        wname, coords = spec.split("=", 1)
        lat, lon = (float(x) for x in coords.split(","))
        waypoints.append((wname, lat, lon))
    points, legs = route(waypoints)
    write_gpx(out, name, points, legs, waypoints)
    total = sum(m for _, _, _, m in legs)
    print(f"{out}: {len(points)} points, {total / 1000:.1f} km, "
          f"{sum(1 for l in legs if l[2] == 'paths')}/{len(legs)} legs over mapped paths")


if __name__ == "__main__":
    main(sys.argv)
