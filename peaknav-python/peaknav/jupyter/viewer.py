"""An interactive PeakNav view inside a Jupyter notebook.

    from peaknav.headless import PeakNavHeadless
    from peaknav.jupyter import PeakNavViewer

    nav = PeakNavHeadless(46.0207, 7.7491)        # or .attach("http://127.0.0.1:8080")
    PeakNavViewer(nav, bearing_deg=230, pitch_deg=-4, altitude_m=3200)

The last line is the whole widget: pan and tilt buttons, a height control, a place to type
coordinates, the display toggles, and the rendered view.

**It is a client of the REST API and nothing else.** Every control below turns into a
documented HTTP call on the renderer's own server - ``POST /camera``, ``POST /position``,
``POST /view``, ``GET /frame`` - made through :class:`peaknav.headless.PeakNavHeadless`.
The widget never starts a renderer, never looks for a jar and never touches a subprocess:
hand it a client and it drives whatever that client is connected to, which may be a
renderer on this machine or one someone else started and left running.

Needs ``ipywidgets``, which is not a dependency of the package - ``pip install
peaknav[jupyter]``. The camera logic itself is in :mod:`peaknav.jupyter.camera` and needs
nothing at all.
"""

from .camera import CLIMB_FACTOR, TILT_STEP_DEG, TURN_STEP_DEG, ViewerCamera

__all__ = ["PeakNavViewer", "show", "LABEL_KINDS", "VIEW_TOGGLES"]

#: Every label kind the renderer accepts, and whether the widget starts with it on. The
#: names are the ones /view takes; the list matches PeakNavRenderer.Label on the Java side,
#: and "roads" living here is why there is no separate roads switch - it IS a label kind.
LABEL_KINDS = [
    ("peaks", True),
    ("place_names", True),
    ("cities", True),
    ("mountain_ranges", True),
    ("islands", True),
    ("lakes", True),
    ("alpine_huts", False),
    ("roads", True),
    ("pistes", False),
    ("navigation", False),
]

#: The boolean display options on /view, as (field, caption, default). Sky mode is not
#: here because it is one of three values rather than on/off - it gets a dropdown.
VIEW_TOGGLES = [
    ("sky", "sky", True),
    ("constellations", "constellations", False),
    ("star_names", "star names", False),
    ("sky_labels", "sky labels", False),
    ("sky_grid", "sky grid", False),
    ("ecliptic", "ecliptic", False),
    ("sun_shading", "sun shading", True),
    ("horizon_compass", "horizon compass", True),
    ("coordinates", "coordinates", True),
    ("corner_compass", "corner compass", True),
]

_MISSING_IPYWIDGETS = (
    "the Jupyter viewer needs ipywidgets, which peaknav does not install by itself: "
    "pip install 'peaknav[jupyter]'  (the rest of peaknav.headless works without it)"
)


def _widgets():
    """ipywidgets, or an explanation of how to get it.

    Imported here rather than at module scope so that reading the documentation, or
    importing :mod:`peaknav.jupyter` for the camera alone, does not require a notebook
    stack to be installed.
    """
    try:
        import ipywidgets
    except ImportError as missing:
        raise ImportError(_MISSING_IPYWIDGETS) from missing
    return ipywidgets


def show(client, image_format="png"):
    """Displays one frame in the notebook, with no controls and no ipywidgets.

    For a script or a report that wants a picture rather than an instrument. Returns an
    ``IPython.display.Image``, which a notebook renders when it is the value of a cell.

    >>> show(nav)                                           # doctest: +SKIP
    """
    from IPython.display import Image
    data = client.frame(image_format)
    return Image(data=data, format="jpeg" if image_format in ("jpg", "jpeg") else "png")


class PeakNavViewer:
    """An interactive view of a running renderer, driven over its REST API.

    :param client: a :class:`peaknav.headless.PeakNavHeadless`, or a base URL string to
        attach to one already running (``"http://127.0.0.1:8080"``).
    :param lat, lon: where to move before the first frame; omit to leave the renderer
        wherever it already is.
    :param bearing_deg, pitch_deg, altitude_m: the starting camera.
    :param image_format: ``"jpg"`` by default - a frame is sent to the browser on every
        control press, and JPEG is several times smaller than PNG over that round trip.
    :param auto_render: render on every change. Turn it off for a slow link and press the
        refresh button instead.

    The widget is the return value, so a bare ``PeakNavViewer(nav)`` at the end of a cell
    displays it. Its :attr:`camera` is a :class:`~peaknav.jupyter.camera.ViewerCamera`,
    usable from other cells - anything done there shows up at the next render.
    """

    def __init__(self, client, *, lat=None, lon=None, bearing_deg=0.0, pitch_deg=-4.0,
                 altitude_m=3000.0, image_format="jpg", auto_render=True,
                 width_px=800):
        if isinstance(client, str):
            from peaknav.headless import PeakNavHeadless
            client = PeakNavHeadless.attach(client)
        self.client = client
        self.camera = ViewerCamera(client, lat=lat, lon=lon, bearing_deg=bearing_deg,
                                   pitch_deg=pitch_deg, altitude_m=altitude_m,
                                   image_format=image_format)
        self.auto_render = auto_render
        self._width_px = width_px
        self._building = False
        self._widget = self._build()
        if lat is not None and lon is not None:
            self._run(lambda: self.camera.go_to(lat, lon))
        else:
            self._run(lambda: self.camera.aim())

    # --------------------------------------------------------------------- display

    def _repr_mimebundle_(self, **kwargs):
        """Displayed exactly as the widget it wraps.

        This delegation is the whole of it, and it has to be this and not
        ``_ipython_display_``. That hook takes precedence over every other display method
        when IPython finds it, and doing the job by calling ``display()`` on the box from
        inside it produced no mimebundle at all - so the notebook fell back to the plain
        text, and a viewer appeared as ``HBox(children=(Image(value=b'\\xff\\xd8...``
        instead of the controls. Handing back the widget's own bundle puts the widget view
        in front of a frontend that can render it, and leaves the text for one that cannot.
        """
        return self._widget._repr_mimebundle_(**kwargs)

    @property
    def widget(self):
        """The underlying ipywidgets box, for embedding in a layout of your own."""
        return self._widget

    # --------------------------------------------------------------------- building

    def _build(self):
        w = _widgets()
        self._image = w.Image(format="jpeg" if self.camera.image_format in ("jpg", "jpeg")
                              else "png", width=self._width_px)
        self._status = w.HTML(value="")

        turn_left = w.Button(description="◀", tooltip="turn left",
                             layout=w.Layout(width="44px"))
        turn_right = w.Button(description="▶", tooltip="turn right",
                              layout=w.Layout(width="44px"))
        tilt_up = w.Button(description="▲", tooltip="look up",
                           layout=w.Layout(width="44px"))
        tilt_down = w.Button(description="▼", tooltip="look down",
                             layout=w.Layout(width="44px"))
        climb = w.Button(description="＋", tooltip="climb",
                         layout=w.Layout(width="44px"))
        descend = w.Button(description="−", tooltip="descend",
                           layout=w.Layout(width="44px"))
        refresh = w.Button(description="Render", icon="refresh",
                           tooltip="fetch the current view")

        turn_left.on_click(lambda _: self._run(lambda: self.camera.turn(-TURN_STEP_DEG)))
        turn_right.on_click(lambda _: self._run(lambda: self.camera.turn(TURN_STEP_DEG)))
        tilt_up.on_click(lambda _: self._run(lambda: self.camera.tilt(TILT_STEP_DEG)))
        tilt_down.on_click(lambda _: self._run(lambda: self.camera.tilt(-TILT_STEP_DEG)))
        climb.on_click(lambda _: self._run(lambda: self.camera.climb(CLIMB_FACTOR)))
        descend.on_click(lambda _: self._run(lambda: self.camera.climb(1.0 / CLIMB_FACTOR)))
        refresh.on_click(lambda _: self.render())

        # continuous_update=False: a slider drag would otherwise fire a render per pixel,
        # and each render is a round trip to a renderer that is drawing a real scene.
        self._bearing = w.FloatSlider(value=self.camera.bearing_deg, min=0, max=360,
                                      step=1, description="bearing", continuous_update=False,
                                      readout_format=".0f")
        self._pitch = w.FloatSlider(value=self.camera.pitch_deg, min=-89, max=89, step=1,
                                    description="pitch", continuous_update=False,
                                    readout_format=".0f")
        self._altitude = w.FloatLogSlider(value=self.camera.altitude_m, base=10,
                                          min=1, max=5, step=0.01, description="height m",
                                          continuous_update=False, readout_format=".0f")
        self._bearing.observe(self._on_bearing, names="value")
        self._pitch.observe(self._on_pitch, names="value")
        self._altitude.observe(self._on_altitude, names="value")

        self._lat = w.FloatText(value=self.camera.lat if self.camera.lat is not None else 0.0,
                                description="lat", step=0.001)
        self._lon = w.FloatText(value=self.camera.lon if self.camera.lon is not None else 0.0,
                                description="lon", step=0.001)
        go = w.Button(description="Go", tooltip="move the viewpoint here")
        go.on_click(lambda _: self._run(
            lambda: self.camera.go_to(self._lat.value, self._lon.value)))

        # Labels are a WHITELIST on /view: naming any turns every other kind off. So one
        # checkbox per kind, and each change sends the whole set - which is also why a
        # single "labels on/off" box was wrong, since it silently discarded the other nine.
        self._label_boxes = {}
        for kind, on_by_default in LABEL_KINDS:
            box = w.Checkbox(value=on_by_default, description=kind.replace("_", " "),
                             indent=False, layout=w.Layout(width="150px"))
            box.observe(self._on_labels_changed, names="value")
            self._label_boxes[kind] = box

        self._view_boxes = {}
        for field, caption, on_by_default in VIEW_TOGGLES:
            box = w.Checkbox(value=on_by_default, description=caption, indent=False,
                             layout=w.Layout(width="150px"))
            box.observe(self._make_view_observer(field), names="value")
            self._view_boxes[field] = box

        # Imagery. The dropdown is filled from the renderer itself rather than a list
        # hard-coded here, so a source added to the app - or by the template box below -
        # appears without this file knowing anything about it. A renderer too old to serve
        # /providers leaves the dropdown empty rather than breaking the whole widget.
        try:
            self._providers = self.client.providers()
        except Exception:                                  # noqa: BLE001
            self._providers = []
        self._imagery = w.Dropdown(
            options=[(p["name"] or p["id"], p["id"]) for p in self._providers] or [("(none)", "")],
            description="imagery", layout=w.Layout(width="320px"))
        self._imagery.observe(self._on_imagery_changed, names="value")

        self._template = w.Text(placeholder="https://tile.openstreetmap.org/{z}/{x}/{y}.png",
                                description="or URL", layout=w.Layout(width="320px"))
        apply_template = w.Button(description="Use tiles", tooltip="any XYZ tile server")
        apply_template.on_click(lambda _: self._apply_template())

        self._sky_mode = w.Dropdown(options=["local", "day", "night"], value="day",
                                    description="sky", layout=w.Layout(width="200px"))
        self._sky_mode.observe(lambda change: self._run(
            lambda: self.camera.set_view(sky_mode=change["new"])), names="value")

        labels_grid = w.VBox([
            w.HTML("<b>Labels</b>"),
            w.Box(list(self._label_boxes.values()),
                  layout=w.Layout(display="flex", flex_flow="row wrap", width="320px")),
            w.HTML("<b>View</b>"),
            w.Box(list(self._view_boxes.values()),
                  layout=w.Layout(display="flex", flex_flow="row wrap", width="320px")),
            self._sky_mode,
            w.HTML("<b>Imagery</b>"),
            self._imagery,
            w.HBox([self._template, apply_template]),
        ])

        pad = w.VBox([
            w.HBox([w.Label(layout=w.Layout(width="44px")), tilt_up,
                    w.Label(layout=w.Layout(width="44px"))]),
            w.HBox([turn_left, tilt_down, turn_right]),
            w.HBox([descend, w.Label(layout=w.Layout(width="44px")), climb]),
        ])
        controls = w.VBox([
            pad,
            self._bearing, self._pitch, self._altitude,
            w.HBox([self._lat, self._lon]),
            w.HBox([go, refresh]),
            labels_grid,
            self._status,
        ])
        return w.HBox([self._image, controls])

    # --------------------------------------------------------------------- wiring

    def _on_imagery_changed(self, change):
        if self._building or not change["new"]:
            return
        self._run(lambda: self.camera.set_satellite(change["new"]))

    def _apply_template(self):
        template = self._template.value.strip()
        if not template:
            self._say("type an XYZ tile URL first, with {x} {y} {z} in it", error=True)
            return
        self._run(lambda: self.camera.set_satellite(
            template=template, name="Custom",
            attribution="© the tile provider - check their usage policy"))
        # The renderer keeps custom sources, so offer the new one in the dropdown too.
        try:
            self._providers = self.client.providers()
            self._building = True
            self._imagery.options = [(p["name"] or p["id"], p["id"]) for p in self._providers]
        except Exception:                                  # noqa: BLE001
            pass
        finally:
            self._building = False

    def set_satellite(self, provider_id=None, *, template=None, **kwargs):
        """Chooses the imagery from code; the dropdown follows."""
        self._run(lambda: self.camera.set_satellite(provider_id, template=template, **kwargs))

    def _on_labels_changed(self, change):
        if self._building:
            return
        self._run(lambda: self.camera.set_view(labels=self.labels()))

    def _make_view_observer(self, field):
        def observer(change):
            if self._building:
                return
            self._run(lambda: self.camera.set_view(**{field: bool(change["new"])}))
        return observer

    def labels(self):
        """The label kinds currently ticked, as /view wants them."""
        return [kind for kind, box in self._label_boxes.items() if box.value]

    def set_labels(self, kinds):
        """Ticks exactly these kinds and sends them; anything unnamed is switched off."""
        self._building = True
        try:
            for kind, box in self._label_boxes.items():
                box.value = kind in kinds
        finally:
            self._building = False
        self._run(lambda: self.camera.set_view(labels=self.labels()))

    def _on_bearing(self, change):
        self._run(lambda: self.camera.aim(bearing_deg=change["new"]))

    def _on_pitch(self, change):
        self._run(lambda: self.camera.aim(pitch_deg=change["new"]))

    def _on_altitude(self, change):
        self._run(lambda: self.camera.set_altitude(change["new"]))

    def _run(self, action):
        """Performs one control's action, renders, and reports rather than raises.

        A widget callback runs inside the kernel's comm handler, where an exception is
        swallowed with at best a traceback in the browser console - so a renderer that has
        gone away would leave the buttons silently dead. The message goes into the widget's
        own status line instead, where the person pressing the button will see it.
        """
        if self._building:
            return
        try:
            action()
        except Exception as failed:               # noqa: BLE001 - shown, not swallowed
            self._say("%s: %s" % (type(failed).__name__, failed), error=True)
            return
        # The sliders are readouts as well as controls: an arrow press moves the camera,
        # so the numbers beside it have to follow. Without this the bearing slider still
        # read 230 after eight presses of "turn left", and the next drag of it jumped the
        # camera back to where the stale number said it was.
        self._sync_controls()
        if self.auto_render:
            self.render()
        else:
            self._say_state()

    def render(self):
        """Fetches the current view and shows it. ``GET /frame``."""
        try:
            self._image.value = self.camera.frame()
        except Exception as failed:               # noqa: BLE001
            self._say("%s: %s" % (type(failed).__name__, failed), error=True)
            return
        self._say_state()

    def _say_state(self):
        state = self.camera.state()
        where = ("%.4f, %.4f" % (state["lat"], state["lon"])
                 if state["lat"] is not None else "wherever the renderer was")
        self._say("%s &nbsp;·&nbsp; bearing %.0f° &nbsp;·&nbsp; pitch %.0f° "
                  "&nbsp;·&nbsp; %.0f m"
                  % (where, state["bearing_deg"], state["pitch_deg"], state["altitude_m"]))

    def _say(self, message, error=False):
        colour = "#b00" if error else "#666"
        self._status.value = ("<span style='color:%s;font-family:monospace;font-size:90%%'>"
                              "%s</span>" % (colour, message))

    # --------------------------------------------------------------------- syncing

    def _sync_controls(self):
        """Writes the camera's state into the controls without acting on it.

        Setting a slider fires its observer, which would move the camera to the value just
        written - harmless when they agree, but it turns every button press into a second
        REST call, and rounds the camera to the slider's step as a parting gift. The flag
        is what stops that, and it is why the observers check it first.
        """
        self._building = True
        try:
            state = self.camera.state()
            self._bearing.value = state["bearing_deg"]
            self._pitch.value = state["pitch_deg"]
            self._altitude.value = state["altitude_m"]
            if state["lat"] is not None:
                self._lat.value = state["lat"]
                self._lon.value = state["lon"]
        finally:
            self._building = False

    def sync_from_camera(self):
        """Pulls the sliders back into line with :attr:`camera`.

        For when another cell moved the camera directly: the widget cannot know that
        happened, and sliders showing one thing while the picture shows another is the
        kind of small lie that wastes an afternoon.
        """
        self._sync_controls()
        self.render()
