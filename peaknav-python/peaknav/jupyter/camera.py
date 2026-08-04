"""The viewer's camera logic, with no notebook and no widgets anywhere in it.

Everything the buttons and sliders do lives here, as ordinary Python against an ordinary
:class:`peaknav.headless.PeakNavHeadless`. Two reasons, and the second is the important one:

* it can be tested without ipywidgets, a browser or a renderer - the tests drive it with a
  stub client and check which REST calls came out;
* it makes plain that the widget is only a face on the REST API. Every movement below is
  one or two documented HTTP calls (``/camera``, ``/position``, ``/view``, ``/wait``,
  ``/frame``); nothing here knows the renderer is Java, let alone where its jar is.
"""

__all__ = ["ViewerCamera", "wrap_bearing", "clamp_pitch"]

#: Pitch beyond which the horizon leaves the frame entirely; the app's own limit.
PITCH_LIMIT_DEG = 89.0

#: What one press of a turn button is worth. An eighth of a right angle reads as a
#: deliberate step rather than a nudge, and eight of them make a quarter turn.
TURN_STEP_DEG = 11.25

#: What one press of a tilt button is worth.
TILT_STEP_DEG = 5.0

#: Multiplier for one press of the climb/descend buttons. A viewpoint is interesting over
#: several orders of magnitude - a valley floor at 500 m, a summit at 4000, an orbit at
#: 20 000 - so height moves by ratio, not by a fixed number of metres.
CLIMB_FACTOR = 1.4

#: Height is clamped to this band. The floor keeps a climb from converging on zero (a
#: ratio can never leave it), and the ceiling is above any viewpoint the app renders from.
MIN_ALTITUDE_M = 10.0
MAX_ALTITUDE_M = 100_000.0


def wrap_bearing(degrees):
    """A compass bearing folded into 0..360.

    >>> wrap_bearing(370.0)
    10.0
    >>> wrap_bearing(-90.0)
    270.0
    >>> wrap_bearing(360.0)
    0.0
    """
    return float(degrees) % 360.0


def clamp_pitch(degrees):
    """A pitch held inside the range the camera can actually take.

    >>> clamp_pitch(-4.0)
    -4.0
    >>> clamp_pitch(120.0)
    89.0
    """
    return float(max(-PITCH_LIMIT_DEG, min(PITCH_LIMIT_DEG, degrees)))


class ViewerCamera:
    """Where the camera is, and the REST calls that put it there.

    Holds the current bearing, pitch, altitude and position, so a widget can show them
    and move them one step at a time. Every mutator sends its change to the renderer
    immediately and returns the new state; :meth:`frame` asks for the picture.

    The client is only ever used through its public REST methods, so anything that speaks
    them will do - a spawned renderer, one attached to over HTTP, or a stub in a test:

    >>> class Fake:
    ...     def __init__(self): self.calls = []
    ...     def look(self, bearing_deg, pitch_deg): self.calls.append(("look", bearing_deg, pitch_deg))
    ...     def set_altitude_asl(self, meters): self.calls.append(("alt", meters))
    ...     def frame(self, image_format="png"): return b"\\x89PNG"
    >>> camera = ViewerCamera(Fake(), bearing_deg=0, pitch_deg=-4, altitude_m=3000)
    >>> camera.turn(-TURN_STEP_DEG)["bearing_deg"]
    348.75
    >>> camera.client.calls[-1]
    ('look', 348.75, -4.0)
    """

    def __init__(self, client, *, lat=None, lon=None, bearing_deg=0.0, pitch_deg=-4.0,
                 altitude_m=3000.0, image_format="jpg"):
        self.client = client
        self.lat = lat
        self.lon = lon
        self.bearing_deg = wrap_bearing(bearing_deg)
        self.pitch_deg = clamp_pitch(pitch_deg)
        self.altitude_m = float(altitude_m)
        self.image_format = image_format

    # ------------------------------------------------------------------- reading

    def state(self):
        """The camera as a plain dict - what a widget shows in its readouts.

        >>> camera = ViewerCamera(None, lat=46.0207, lon=7.7491, bearing_deg=230)
        >>> camera.state()["bearing_deg"], camera.state()["lat"]
        (230.0, 46.0207)
        """
        return {
            "lat": self.lat,
            "lon": self.lon,
            "bearing_deg": self.bearing_deg,
            "pitch_deg": self.pitch_deg,
            "altitude_m": self.altitude_m,
        }

    def frame(self):
        """The current view as image bytes, straight from ``GET /frame``."""
        return self.client.frame(self.image_format)

    # ------------------------------------------------------------------- moving

    def aim(self, bearing_deg=None, pitch_deg=None):
        """Points the camera; either argument may be left alone. One ``POST /camera``."""
        if bearing_deg is not None:
            self.bearing_deg = wrap_bearing(bearing_deg)
        if pitch_deg is not None:
            self.pitch_deg = clamp_pitch(pitch_deg)
        self.client.look(bearing_deg=self.bearing_deg, pitch_deg=self.pitch_deg)
        return self.state()

    def turn(self, degrees):
        """Turns by ``degrees``, positive clockwise."""
        return self.aim(bearing_deg=self.bearing_deg + degrees)

    def tilt(self, degrees):
        """Tilts by ``degrees``, positive upward."""
        return self.aim(pitch_deg=self.pitch_deg + degrees)

    def set_altitude(self, meters):
        """Puts the camera at a height above sea level. One ``POST /camera``."""
        self.altitude_m = float(max(MIN_ALTITUDE_M, min(MAX_ALTITUDE_M, meters)))
        self.client.set_altitude_asl(self.altitude_m)
        return self.state()

    def climb(self, factor=CLIMB_FACTOR):
        """Multiplies the height - ``climb()`` to rise, ``climb(1 / CLIMB_FACTOR)`` to drop."""
        return self.set_altitude(self.altitude_m * factor)

    def go_to(self, lat, lon, *, await_tiles_ms=20_000, settle_ms=0):
        """Moves the viewpoint and lets the new terrain arrive before the next frame.

        ``POST /position`` with a bounded wait, then the camera is re-aimed: moving does
        not carry the aim with it, and a widget whose sliders said one thing while the
        view showed another would be lying about its own state. Deliberately no
        ``download_timeout_ms`` - fetching a region can take many minutes, and a notebook
        cell that appears to hang is worse than a view with holes in it. Call
        ``client.move_to(..., download_timeout_ms=...)`` yourself when you want that.
        """
        self.lat = float(lat)
        self.lon = float(lon)
        self.client.move_to(self.lat, self.lon, await_tiles_ms=await_tiles_ms)
        if settle_ms:
            self.client.wait(settle_ms=settle_ms)
        self.client.look(bearing_deg=self.bearing_deg, pitch_deg=self.pitch_deg)
        self.client.set_altitude_asl(self.altitude_m)
        return self.state()

    def set_satellite(self, provider_id=None, *, template=None, name="Custom",
                      attribution=""):
        """Chooses the imagery under the terrain - a known source, or any XYZ template."""
        if template:
            return self.set_view(satellite_template=template, satellite_name=name,
                                 satellite_attribution=attribution)
        return self.set_view(satellite_provider=provider_id)

    def set_view(self, **options):
        """Display options, passed through to ``POST /view``.

        Changing which labels are shown needs a second call, and the order is not a matter
        of taste: setting the preference does not recompute what is visible, so the labels
        arrive only once something asks for a pass. Measured - ``labels=["peaks",
        "place_names"]`` on its own leaves 0 labels drawn, and the same view after
        ``refresh_labels`` draws 47.

        It cannot be folded into one request either: the server acts on ``refresh_labels``
        BEFORE it applies ``labels``, so a combined call refreshes against the old set and
        the new one appears only after some later, unrelated pass.
        """
        self.client.set_view(**options)
        if "labels" in options:
            self.client.set_view(refresh_labels=True)
        return self.state()
