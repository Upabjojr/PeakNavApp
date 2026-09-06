"""A Python client for the PeakNav headless renderer.

The renderer is the real application booted off-screen; this client either starts one
(`PeakNavHeadless(lat, lon)`) or attaches to one already running
(`PeakNavHeadless.attach("http://127.0.0.1:8080")`). Under the hood it is plain HTTP
against the server documented at /openapi.json - nothing here that curl could not do,
which is the point: the protocol is the API, this file is only convenience.

    from peaknav.headless import PeakNavHeadless

    with PeakNavHeadless(46.0207, 7.7491) as nav:      # Zermatt
        nav.move_to(46.0207, 7.7491, download_timeout_ms=600_000, await_tiles_ms=120_000)
        nav.look(bearing_deg=230, pitch_deg=-4)
        nav.set_altitude_asl(3200)
        nav.set_view(sky=True, sky_mode="day", labels=["peaks", "roads"])
        nav.wait(tiles_timeout_ms=60_000, settle_ms=1_000)
        nav.save_frame("matterhorn.png")

Requires only the standard library. The spawned JVM needs a session display (the window
is created hidden, but GLFW needs a display connection to make the GL context).
"""

import base64
import json
import os
import queue
import subprocess
import threading
import time
import urllib.error
import urllib.request

from . import jar as jar_module

__all__ = ["PeakNavHeadless", "PeakNavError"]


class PeakNavError(RuntimeError):
    """An error reported by the renderer, carrying its message verbatim."""


def _find_jar(explicit=None):
    """The renderer jar: a local build, the cache, or the published release.

    The whole search, and how to steer it, is documented in :mod:`peaknav.headless.jar`.
    The jar is 75 MB, so it is not shipped inside the wheel - it is fetched once, on
    first use, and cached.
    """
    try:
        return jar_module.resolve_jar(explicit)
    except jar_module.JarNotFound as missing:
        # One exception type for callers of this package, whatever went wrong.
        raise PeakNavError(str(missing)) from missing


def _find_java():
    home = os.environ.get("PEAKNAV_JAVA_HOME", "/usr/lib/jvm/java-17-openjdk-amd64")
    candidate = os.path.join(home, "bin", "java")
    return candidate if os.path.exists(candidate) else "java"


class PeakNavHeadless:
    """One off-screen PeakNav instance, spawned and owned, or merely attached to.

    Spawning is the normal way: the JVM's lifetime is tied to this object, so a crashed
    or interrupted script cannot leave renderers running (the process also dies with its
    parent's pipes). Attach mode is for a server someone else started - it is never
    shut down by ``close()`` unless ``shutdown()`` is called explicitly.
    """

    def __init__(self, lat, lon, *, jar=None, java=None, width=1600, height=900,
                 language="en", max_heap="4g", image_format="png",
                 extra_args=(), boot_timeout_s=240):
        self._proc = None
        self._owned = True
        # Set before anything can fail: the cleanup path calls close(), which used to
        # reach for a base_url that did not exist yet and raise AttributeError - burying
        # the real reason the renderer never came up under a nonsense error.
        self.base_url = None
        args = [java or _find_java(), "-Xmx" + max_heap, "-jar", _find_jar(jar),
                "--lat", str(lat), "--lon", str(lon),
                "--width", str(width), "--height", str(height),
                "--language", language, "--format", image_format,
                "--serve", "0", *extra_args]
        self._proc = subprocess.Popen(args, stdout=subprocess.PIPE,
                                      stderr=subprocess.STDOUT, text=True)
        self.base_url = "http://127.0.0.1:%d" % self._await_port(boot_timeout_s)

    @classmethod
    def attach(cls, base_url):
        """A client for a server already running at ``base_url``."""
        self = cls.__new__(cls)
        self._proc = None
        self._owned = False
        self.base_url = base_url.rstrip("/")
        return self

    def _await_port(self, timeout_s):
        """Reads the child's output until the PEAKNAV_SERVE marker names the port.

        After the marker, a thread keeps draining the pipe forever: the app logs
        throughout its life, and an undrained pipe eventually fills and blocks the
        JVM mid-log - a hang with no error anywhere.

        The reading happens on its own thread rather than by iterating the pipe here,
        because the deadline has to hold even when the renderer says NOTHING. Iterating
        blocks inside ``readline`` until a line arrives, so a renderer that printed its
        banner and then wedged - a graphics driver that will not give out a GL context,
        say - was waited on indefinitely, whatever timeout the caller asked for.
        """
        lines = queue.Queue()
        transcript = []

        def read_output():
            try:
                for line in self._proc.stdout:
                    lines.put(line)
            except Exception:
                pass
            finally:
                lines.put(None)      # end of the child's output

        threading.Thread(target=read_output, daemon=True).start()

        deadline = time.time() + timeout_s
        while True:
            remaining = deadline - time.time()
            if remaining <= 0:
                self._boot_failed("did not start within %gs" % timeout_s, transcript)
            try:
                line = lines.get(timeout=min(remaining, 0.5))
            except queue.Empty:
                if self._proc.poll() is not None:
                    self._boot_failed("exited with status %s before it served anything"
                                      % self._proc.returncode, transcript)
                continue
            if line is None:
                self._boot_failed("stopped printing and never served; status %s"
                                  % self._proc.poll(), transcript)
            transcript.append(line.rstrip())
            if line.startswith("PEAKNAV_SERVE port="):
                port = int(line.strip().split("=", 1)[1])
                threading.Thread(target=self._drain, daemon=True).start()
                return port

    def _boot_failed(self, reason, transcript):
        """Stops the half-started renderer and reports it, with what it managed to say.

        The output is the diagnosis - a stack trace, a missing file, or (the case that
        prompted this) nothing at all after the banner, which says the renderer never got
        as far as its own code. Discarding it left "renderer did not start" and no clue.
        """
        if self._proc is not None:
            try:
                self._proc.kill()
                self._proc.wait(timeout=10)
            except Exception:
                pass
        tail = "\n".join(transcript[-12:])
        raise PeakNavError("the renderer %s.%s" % (
            reason, ("\nIt printed:\n" + tail) if tail else " It printed nothing."))

    def _drain(self):
        try:
            for _ in self._proc.stdout:
                pass
        except Exception:
            pass

    # ------------------------------------------------------------------ transport

    def _request(self, method, path, payload=None, timeout=900):
        data = None if payload is None else json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(self.base_url + path, data=data, method=method)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                body = resp.read()
                kind = resp.headers.get("Content-Type", "")
        except urllib.error.HTTPError as e:
            try:
                message = json.loads(e.read()).get("error", str(e))
            except Exception:
                message = str(e)
            raise PeakNavError(message) from None
        if kind.startswith("application/json"):
            return json.loads(body)
        return body

    # ------------------------------------------------------------------ the API

    def status(self):
        return self._request("GET", "/status")

    def move_to(self, lat, lon, *, download_timeout_ms=None, await_tiles_ms=None):
        """Moves the viewpoint; optionally downloads the area and waits for quiet."""
        payload = {"lat": lat, "lon": lon}
        if download_timeout_ms is not None:
            payload["download_timeout_ms"] = download_timeout_ms
        if await_tiles_ms is not None:
            payload["await_tiles_ms"] = await_tiles_ms
        return self._request("POST", "/position", payload)

    def look(self, bearing_deg, pitch_deg):
        """Faces the camera: bearing 0 is north, negative pitch looks down."""
        return self._request("POST", "/camera",
                             {"bearing_deg": bearing_deg, "pitch_deg": pitch_deg})

    def set_altitude_asl(self, meters):
        """Absolute height above sea level - what a video wants."""
        return self._request("POST", "/camera", {"altitude_asl_m": meters})

    def set_elevation_above_ground(self, meters):
        return self._request("POST", "/camera", {"elevation_above_ground_m": meters})

    def set_elevation_bar(self, fraction):
        """The UI's elevation bar, 0..1; the scale is exponential, like the app's."""
        return self._request("POST", "/camera", {"elevation_bar": fraction})

    def load_gpx(self, path=None, *, xml=None):
        """Draws the paths of a GPX file (or an inline GPX document) on the terrain, as the
        app draws a loaded track - without flying the camera to frame it; you place the
        camera. Returns how many paths the document held. The file is read here and sent
        inline, so it need not be visible to the renderer's process."""
        if xml is None:
            if path is None:
                raise ValueError("give a path or xml")
            with open(path, encoding="utf-8") as f:
                xml = f.read()
        return self._request("POST", "/gpx", {"xml": xml})["paths"]

    def set_view(self, **options):
        """Display options, named as in /openapi.json: sky=True, sky_mode="day",
        labels=["peaks", "roads"], sky_time="2026-07-15T09:30:00Z", ..."""
        return self._request("POST", "/view", options)

    def wait(self, *, tiles_timeout_ms=None, settle_ms=None):
        """Lets streaming finish before a frame; returns {"quiet": False} on timeout."""
        payload = {}
        if tiles_timeout_ms is not None:
            payload["tiles_timeout_ms"] = tiles_timeout_ms
        if settle_ms is not None:
            payload["settle_ms"] = settle_ms
        return self._request("POST", "/wait", payload)

    def frame(self, image_format="png", *, ui=False):
        """The current view, as PNG or JPEG bytes; ``ui=True`` draws the widgets too (a
        screenshot of the app rather than a picture of the view)."""
        return self._request("GET", "/frame?format=" + image_format + ("&ui=true" if ui else ""))

    def save_frame(self, path, *, ui=False):
        """Renders to ``path``, choosing PNG or JPEG from its suffix."""
        image_format = "jpg" if path.lower().endswith((".jpg", ".jpeg")) else "png"
        data = self.frame(image_format, ui=ui)
        with open(path, "wb") as f:
            f.write(data)
        return path

    def widgets(self):
        """Where the visible named widgets are: {"width", "height", "widgets": {name: {x, y,
        w, h}}} in window pixels, y downwards. Names: gallery, camera, search, options, share,
        here, help, gyro, elevation_bar, photo_match, photo_outline_bar, photo_close,
        terrain_bar, unpin, go_to, orbit, open_coordinate, go_to_cancel, gpx_play, gpx_clear."""
        return self._request("GET", "/widgets")

    def tap(self, x, y):
        """A finger's tap at window pixels (y downwards): picks a point on the terrain."""
        return self._request("POST", "/tap", {"x": int(x), "y": int(y)})

    def pin_photo(self, x, y):
        """Pins the terrain under window pixel (x, y) to that spot of the photo (the red ring)."""
        return self._request("POST", "/photo/pin", {"x": x, "y": y})

    def unpin_photo(self):
        return self._request("DELETE", "/photo/pin")

    # ------------------------------------------------------------------ photographs

    def load_photo(self, path=None, *, image=None, go_to_exif=True,
                   download_timeout_ms=None, await_tiles_ms=None):
        """Puts a photograph behind the terrain, as the app's gallery button does. ``path``
        is read here and sent inline (``image`` gives the JPEG/PNG bytes directly), so it
        need not be visible to the renderer's process. With ``go_to_exif`` and a GPS
        position in the file, the viewpoint moves there first - give the two timeouts to
        download and wait for the terrain as ``move_to`` does. Returns the server's
        summary: width, height, vertical_fov_deg, location, moved, downloaded, quiet."""
        if image is None:
            if path is None:
                raise ValueError("give a path or image bytes")
            with open(path, "rb") as f:
                image = f.read()
        payload = {"image_base64": base64.b64encode(image).decode("ascii"),
                   "go_to_exif": bool(go_to_exif)}
        if download_timeout_ms is not None:
            payload["download_timeout_ms"] = download_timeout_ms
        if await_tiles_ms is not None:
            payload["await_tiles_ms"] = await_tiles_ms
        return self._request("POST", "/photo", payload)

    def match_photo(self, *, attempts=3):
        """Matches the loaded photo's skyline against the terrain around the current position
        and turns the camera to the best pose. Returns the match: bearing_deg, pitch_deg,
        vertical_fov_deg, roll_deg, cost, ratio, relief_deg and ``confident`` - the
        verdict the app uses before asking the user; or ``{"matched": False, ...}``."""
        return self._request("POST", "/photo/match", {"attempts": attempts})

    def set_photo_overlay(self, *, outline_alpha=None, terrain_alpha=None):
        """How the terrain is drawn over the photo: the outlines' opacity and the rendered
        terrain's (0 = outlines only, the default), both 0..1."""
        payload = {}
        if outline_alpha is not None:
            payload["outline_alpha"] = outline_alpha
        if terrain_alpha is not None:
            payload["terrain_alpha"] = terrain_alpha
        return self._request("POST", "/photo/overlay", payload)

    def clear_photo(self):
        """Takes the photograph down."""
        return self._request("DELETE", "/photo")

    def tag_photo(self, path, out_path, *, labels=("peaks",), download_timeout_ms=600_000,
                  await_tiles_ms=120_000, settle_ms=1_000, attempts=3, outline_alpha=None,
                  terrain_alpha=None):
        """The whole thing in one call: loads the photo, goes to where it was taken (its EXIF
        position), waits for the terrain, matches the skyline, and saves the photo with the
        terrain's labels drawn over it to ``out_path`` - a picture that also carries the
        matched pose in its EXIF block. Returns the match dict (check ``confident``).
        Raises PeakNavError when the photo has no position or nothing could be matched."""
        info = self.load_photo(path, go_to_exif=True, download_timeout_ms=download_timeout_ms,
                               await_tiles_ms=await_tiles_ms)
        if not info.get("location"):
            raise PeakNavError("the photo carries no GPS position; move_to() where it was "
                               "taken, then load_photo(go_to_exif=False) and match_photo()")
        if labels is not None:
            self.set_view(labels=list(labels))
        match = self.match_photo(attempts=attempts)
        if not match.get("matched"):
            raise PeakNavError(match.get("error", "no match"))
        if outline_alpha is not None or terrain_alpha is not None:
            self.set_photo_overlay(outline_alpha=outline_alpha, terrain_alpha=terrain_alpha)
        self.wait(settle_ms=settle_ms)
        self.save_frame(out_path)
        return match

    def providers(self):
        """The imagery sources this renderer can be pointed at: [{"id", "name"}, ...].

        Needs a renderer new enough to serve ``GET /providers``; older ones 404, which is
        reported as a :class:`PeakNavError` like any other refusal.
        """
        return self._request("GET", "/providers")["providers"]

    def set_satellite(self, provider_id=None, *, template=None, name="Custom",
                      attribution=""):
        """Chooses the imagery draped on the terrain.

        Either a known source by id (see :meth:`providers`) or any XYZ tile server by URL
        template, with ``{x}``, ``{y}`` and ``{z}`` placeholders::

            nav.set_satellite(template="https://tile.openstreetmap.org/{z}/{x}/{y}.png",
                              name="OpenStreetMap",
                              attribution="© OpenStreetMap contributors")

        Every tile is re-fetched, so follow it with a wait before asking for a frame.
        """
        if template:
            return self.set_view(satellite_template=template, satellite_name=name,
                                 satellite_attribution=attribution)
        if provider_id:
            return self.set_view(satellite_provider=provider_id)
        raise ValueError("name a provider_id or a template")

    def objects(self, *, kinds=None, drawn_only=False, scope="displayable"):
        """The objects loaded around the viewer: peaks, places, alpine huts, pistes and
        area names (mountain ranges, islands, lakes, towns), as a list of dicts.

        Each carries ``kind`` (``peak``, ``place``, ``alpine_hut``, ``piste`` or ``area``),
        ``name``, ``lat``, ``lon``, ``elevation_m`` and ``drawn`` - whether its label was on
        screen in the last frame. POIs add ``tags`` (the map's key/value pairs),
        ``prominence_m`` where known, ``hidden`` (which gate blanked the label) and
        ``screen`` ``{x, y}``, the anchor the label line points at (it can lie above the
        frame when the summit is higher than the view), and ``label`` ``{x, y}``, the
        bottom-left of the drawn name, always on the frame. Areas add ``type``
        (``range``, ``island``, ``lake``, ``city``, ...), ``candidate`` - it survived the
        geometric culls of the last frame and only the de-overlap round kept it off the
        picture - ``hidden.by_mountains`` (the verdict cached across label decisions; also
        true for an area not yet tested) and, when drawn, ``screen``
        ``{x, y, width, height}`` for the label plate. Screen coordinates are pixels of
        the image :meth:`frame` returns, origin top-left.

        ``kinds`` filters to some of those kinds; ``drawn_only`` keeps what the last frame
        drew; ``scope="all"`` widens from the labelling candidates to every loaded POI.
        Labels lag the terrain after a move, so :meth:`wait` first.
        """
        path = "/objects?scope=%s&drawn=%s" % (scope, "true" if drawn_only else "false")
        found = self._request("GET", path)["objects"]
        if kinds is not None:
            wanted = {kinds} if isinstance(kinds, str) else set(kinds)
            found = [o for o in found if o["kind"] in wanted]
        return found

    def peaks(self, **kw):
        """The loaded peaks; see :meth:`objects` for the keyword arguments."""
        return self.objects(kinds="peak", **kw)

    def areas(self, **kw):
        """The loaded area names (ranges, islands, lakes, towns); see :meth:`objects`."""
        return self.objects(kinds="area", **kw)

    def openapi(self):
        """The server's own API description, as a dict."""
        return self._request("GET", "/openapi.json")

    # ------------------------------------------------------------------ lifecycle

    def shutdown(self):
        """Asks the server to exit - also stops servers this client only attached to."""
        try:
            self._request("POST", "/shutdown", {}, timeout=10)
        except (PeakNavError, OSError):
            pass  # it may already be gone, which is what was wanted

    def close(self):
        """Shuts down a spawned renderer and reaps the process. Attached servers are
        left running - stopping something this client did not start is shutdown()'s
        job, not a context-manager side effect."""
        if self._proc is not None and self._owned:
            self.shutdown()
            try:
                self._proc.wait(timeout=30)
            except subprocess.TimeoutExpired:
                self._proc.kill()
                self._proc.wait()
            self._proc = None

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    def __del__(self):
        try:
            self.close()
        except Exception:
            pass
