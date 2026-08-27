"""Boots a real renderer over REST and reads a frame back.

Needs the fat jar (./gradlew :headless:renderJar) and a session display; skips itself
cleanly when either is missing, so it can sit in CI without lying about coverage there.
"""

import os
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from peaknav.headless import PeakNavError, PeakNavHeadless
from peaknav.headless.client import _find_jar

ZERMATT = (46.0207, 7.7491)


def _available():
    if not os.environ.get("DISPLAY"):
        return False
    try:
        _find_jar()
    except PeakNavError:
        return False
    return True


pytestmark = pytest.mark.skipif(not _available(), reason="needs the jar and a display")


def test_boot_render_shutdown(tmp_path):
    with PeakNavHeadless(*ZERMATT) as nav:
        assert nav.status()["ok"] is True

        # The server describes itself; the description names the endpoints used below,
        # so a drifted spec fails here rather than misleading a future client.
        spec = nav.openapi()
        for path in ("/position", "/camera", "/view", "/wait", "/frame", "/shutdown"):
            assert path in spec["paths"], path

        nav.move_to(*ZERMATT, await_tiles_ms=120_000)
        nav.look(bearing_deg=230, pitch_deg=-4)
        nav.set_altitude_asl(3200)
        nav.set_view(sky=True, sky_mode="day", labels=["peaks"],
                     coordinates=False, horizon_compass=False, corner_compass=False)
        nav.wait(tiles_timeout_ms=60_000, settle_ms=800)

        png = nav.frame("png")
        assert png[:8] == b"\x89PNG\r\n\x1a\n", "the response body must be the PNG itself"
        assert len(png) > 50_000, "a real terrain frame is not this small: %d" % len(png)

        # The loaded objects come back structured, with where their labels sit.
        objects = nav.objects()
        assert "/objects" in spec["paths"]
        peaks = [o for o in objects if o["kind"] == "peak"]
        assert peaks, "Zermatt has peaks loaded once its tiles are in"
        assert all(k in peaks[0] for k in ("name", "lat", "lon", "elevation_m", "drawn"))
        assert nav.peaks() == peaks
        gpx = ('<gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1"><trk><trkseg>'
               '<trkpt lat="46.0207" lon="7.7491"><ele>1608</ele></trkpt>'
               '<trkpt lat="45.9833" lon="7.7853"><ele>3089</ele></trkpt>'
               '</trkseg></trk></gpx>')
        assert nav.load_gpx(xml=gpx) == 1
        nav.set_view(fov=62)
        drawn = nav.objects(drawn_only=True)
        assert all(o["drawn"] for o in drawn)
        assert all("screen" in o for o in drawn)
        assert len(nav.objects(scope="all")) >= len(objects)

        jpg_path = tmp_path / "view.jpg"
        nav.save_frame(str(jpg_path))
        head = jpg_path.read_bytes()[:3]
        assert head == b"\xff\xd8\xff", "suffix .jpg must produce a JPEG"

        # Errors surface as exceptions carrying the server's message, not as None.
        with pytest.raises(PeakNavError):
            nav.set_view(labels=["no_such_kind"])
        with pytest.raises(PeakNavError):
            nav._request("POST", "/camera",
                         {"altitude_asl_m": 3000, "elevation_bar": 0.5})

    # Leaving the block shut the JVM down; its exit is the proof.
    assert nav._proc is None
