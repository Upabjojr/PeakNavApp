"""The notebook viewer, checked without a notebook.

The point of these is the boundary: the widget is supposed to be a REST client and nothing
else, so the tests drive its camera with a stub that records the calls and assert what came
out. A regression that reached past the API - poking at the jar, the subprocess, the
renderer's internals - shows up here as a call that never arrived.
"""

import pytest

from peaknav.jupyter.camera import (CLIMB_FACTOR, MAX_ALTITUDE_M, MIN_ALTITUDE_M,
                                    PITCH_LIMIT_DEG, TURN_STEP_DEG, ViewerCamera,
                                    clamp_pitch, wrap_bearing)


class FakeClient:
    """Speaks the client's REST methods and remembers what it was asked to do."""

    def __init__(self):
        self.calls = []

    def look(self, bearing_deg, pitch_deg):
        self.calls.append(("look", round(bearing_deg, 4), round(pitch_deg, 4)))

    def set_altitude_asl(self, meters):
        self.calls.append(("altitude", round(meters, 4)))

    def move_to(self, lat, lon, **options):
        self.calls.append(("move_to", lat, lon, options))

    def wait(self, **options):
        self.calls.append(("wait", options))

    def set_view(self, **options):
        self.calls.append(("view", options))

    def frame(self, image_format="png"):
        self.calls.append(("frame", image_format))
        return b"\xff\xd8\xff-jpeg-bytes"

    def kinds(self):
        return [call[0] for call in self.calls]


@pytest.fixture
def camera():
    return ViewerCamera(FakeClient(), lat=46.0207, lon=7.7491, bearing_deg=230,
                        pitch_deg=-4, altitude_m=3200)


# ------------------------------------------------------------------------ aiming

def test_turning_sends_one_camera_call(camera):
    state = camera.turn(TURN_STEP_DEG)
    assert state["bearing_deg"] == pytest.approx(241.25)
    assert camera.client.calls == [("look", 241.25, -4.0)]


def test_turning_past_north_wraps_rather_than_running_off(camera):
    camera.aim(bearing_deg=355)
    camera.turn(10)
    assert camera.bearing_deg == pytest.approx(5.0)
    # And the renderer is told the wrapped value, not 365.
    assert camera.client.calls[-1] == ("look", 5.0, -4.0)


def test_tilting_stops_at_the_vertical(camera):
    for _ in range(50):
        camera.tilt(10)
    assert camera.pitch_deg == PITCH_LIMIT_DEG
    for _ in range(100):
        camera.tilt(-10)
    assert camera.pitch_deg == -PITCH_LIMIT_DEG


def test_aiming_one_axis_leaves_the_other_alone(camera):
    camera.aim(bearing_deg=90)
    assert camera.pitch_deg == -4.0
    camera.aim(pitch_deg=-20)
    assert camera.bearing_deg == 90.0
    assert camera.client.calls[-1] == ("look", 90.0, -20.0)


# ------------------------------------------------------------------------ height

def test_climbing_is_a_ratio_not_a_step(camera):
    camera.climb()
    assert camera.altitude_m == pytest.approx(3200 * CLIMB_FACTOR)
    camera.climb(1 / CLIMB_FACTOR)
    assert camera.altitude_m == pytest.approx(3200)
    assert camera.client.kinds() == ["altitude", "altitude"]


def test_height_is_held_inside_its_band(camera):
    for _ in range(200):
        camera.climb(1 / CLIMB_FACTOR)
    # A ratio can never reach zero on its own; the floor is what stops it converging.
    assert camera.altitude_m == MIN_ALTITUDE_M
    for _ in range(200):
        camera.climb(CLIMB_FACTOR)
    assert camera.altitude_m == MAX_ALTITUDE_M


# ------------------------------------------------------------------------ moving

def test_moving_waits_for_terrain_and_restores_the_aim(camera):
    camera.aim(bearing_deg=120, pitch_deg=-10)
    camera.client.calls.clear()
    camera.go_to(45.9763, 7.6586)

    assert camera.client.kinds() == ["move_to", "look", "altitude"]
    move = camera.client.calls[0]
    assert move[1] == pytest.approx(45.9763) and move[2] == pytest.approx(7.6586)
    # Bounded: a notebook cell must not hang for the many minutes a download can take.
    assert move[3]["await_tiles_ms"] == 20_000
    assert "download_timeout_ms" not in move[3]
    # The aim survives the move, so the sliders keep telling the truth.
    assert camera.client.calls[1] == ("look", 120.0, -10.0)


def test_a_settle_is_only_requested_when_asked_for(camera):
    camera.go_to(45.9763, 7.6586, settle_ms=250)
    assert ("wait", {"settle_ms": 250}) in camera.client.calls
    camera.client.calls.clear()
    camera.go_to(45.9763, 7.6586)
    assert "wait" not in camera.client.kinds()


# ------------------------------------------------------------------------ the view

def test_display_options_go_through_untouched(camera):
    camera.set_view(sky=True, sky_mode="day")
    assert camera.client.calls[-1] == ("view", {"sky": True, "sky_mode": "day"})


def test_changing_labels_also_asks_for_a_pass(camera):
    # Setting the preference does not recompute what is visible: measured against a real
    # renderer, labels=["peaks","place_names"] alone drew 0 labels and the same view after
    # refresh_labels drew 47. Two calls, in this order - the server acts on refresh_labels
    # before it applies labels, so one combined request would refresh the OLD set.
    camera.set_view(labels=["peaks", "roads"])
    assert camera.client.calls[-2] == ("view", {"labels": ["peaks", "roads"]})
    assert camera.client.calls[-1] == ("view", {"refresh_labels": True})


def test_options_without_labels_do_not_pay_for_a_pass(camera):
    camera.set_view(sky=False)
    assert camera.client.kinds().count("view") == 1


def test_the_frame_is_asked_for_in_the_chosen_format():
    camera = ViewerCamera(FakeClient(), image_format="jpg")
    assert camera.frame() == b"\xff\xd8\xff-jpeg-bytes"
    assert camera.client.calls[-1] == ("frame", "jpg")


def test_the_camera_only_ever_uses_the_rest_client():
    # The whole contract in one assertion: a stub with only the documented REST methods is
    # enough to drive everything. Anything reaching for the jar, the process or the
    # renderer's internals would fail here with AttributeError.
    camera = ViewerCamera(FakeClient(), bearing_deg=0, altitude_m=2000)
    camera.turn(30)
    camera.tilt(-5)
    camera.climb()
    camera.go_to(46.0, 7.7)
    camera.set_view(sky=False)
    camera.frame()
    assert set(camera.client.kinds()) == {"look", "altitude", "move_to", "view", "frame"}


# ------------------------------------------------------------------------ helpers

@pytest.mark.parametrize("given,expected", [
    (0, 0.0), (360, 0.0), (370, 10.0), (-90, 270.0), (720.5, 0.5),
])
def test_bearings_fold_into_a_circle(given, expected):
    assert wrap_bearing(given) == pytest.approx(expected)


@pytest.mark.parametrize("given,expected", [
    (0, 0.0), (-4, -4.0), (200, PITCH_LIMIT_DEG), (-200, -PITCH_LIMIT_DEG),
])
def test_pitch_is_clamped(given, expected):
    assert clamp_pitch(given) == pytest.approx(expected)


# ------------------------------------------------------------------------ the widget

def test_the_widget_explains_itself_when_ipywidgets_is_absent():
    pytest.importorskip  # (kept explicit: this test is about the absence, not the presence)
    try:
        import ipywidgets  # noqa: F401
    except ImportError:
        from peaknav.jupyter.viewer import PeakNavViewer
        with pytest.raises(ImportError, match="pip install"):
            PeakNavViewer(FakeClient())
    else:
        pytest.skip("ipywidgets is installed; the missing-dependency path cannot be taken")


def test_the_widget_builds_and_its_buttons_reach_the_rest_client():
    pytest.importorskip("ipywidgets",
                        reason="the widget layer needs ipywidgets: pip install peaknav[jupyter]")
    from peaknav.jupyter import PeakNavViewer

    client = FakeClient()
    viewer = PeakNavViewer(client, lat=46.0207, lon=7.7491, bearing_deg=230,
                           altitude_m=3200)
    assert viewer.widget is not None
    # Construction moved, aimed and drew once.
    assert "move_to" in client.kinds() and "frame" in client.kinds()

    before = len(client.calls)
    viewer.camera.turn(TURN_STEP_DEG)
    viewer.render()
    kinds = client.kinds()[before:]
    assert kinds == ["look", "frame"]
    assert viewer._image.value == b"\xff\xd8\xff-jpeg-bytes"


def test_a_failing_renderer_is_reported_in_the_widget_not_swallowed():
    pytest.importorskip("ipywidgets",
                        reason="the widget layer needs ipywidgets: pip install peaknav[jupyter]")
    from peaknav.jupyter import PeakNavViewer

    class Broken(FakeClient):
        def look(self, bearing_deg, pitch_deg):
            raise ConnectionError("renderer went away")

    viewer = PeakNavViewer(Broken())
    viewer.camera.bearing_deg = 0
    viewer._run(lambda: viewer.camera.turn(10))
    # A widget callback's exception would otherwise vanish into the browser console.
    assert "renderer went away" in viewer._status.value


# ---------------------------------------------------------------- boot diagnostics
#
# Not about the widget, but about what a notebook user sees when the renderer fails to
# come up - which, from a cell, is all they get. A silent hang and a crash must both say
# something useful rather than raising from the cleanup path.

class _Pipe:
    """The lines, then either end-of-output or a pipe that stays open and silent.

    A StringIO would reach EOF the moment it ran dry, which models a renderer that closed
    its output - not the case wanted here. A wedged renderer holds its pipe open and simply
    says nothing, and reading it blocks; that is what makes a timeout necessary at all.
    """

    def __init__(self, lines, hang):
        self._lines = list(lines)
        self._hang = hang

    def __iter__(self):
        import threading
        for line in self._lines:
            yield line
        if self._hang:
            threading.Event().wait()      # never returns, like a live and silent pipe


class FakeProc:
    """A subprocess-like object that prints what it is told to, then behaves as asked."""

    def __init__(self, lines, exit_code=None, hang=False):
        self.stdout = _Pipe(lines, hang)
        self._exit_code = exit_code
        self._hang = hang
        self.killed = False
        self.returncode = exit_code

    def poll(self):
        return None if self._hang else self._exit_code

    def kill(self):
        self.killed = True

    def wait(self, timeout=None):
        return self._exit_code


def _client_with(proc):
    from peaknav.headless import PeakNavHeadless
    client = PeakNavHeadless.__new__(PeakNavHeadless)
    client._proc = proc
    client._owned = True
    client.base_url = None
    return client


def test_a_renderer_that_serves_is_found():
    proc = FakeProc(["PeakNav headless renderer: 46, 7\n", "PEAKNAV_SERVE port=41123\n"])
    assert _client_with(proc)._await_port(5) == 41123


def test_a_wedged_renderer_times_out_instead_of_waiting_for_a_line_that_never_comes():
    from peaknav.headless import PeakNavError
    # Printed its banner, then nothing, and never exits - a graphics driver that will not
    # hand out a context looks exactly like this. Iterating the pipe would block here for
    # ever, whatever timeout was asked for.
    proc = FakeProc(["PeakNav headless renderer: 46, 7\n"], hang=True)
    client = _client_with(proc)
    with pytest.raises(PeakNavError) as failure:
        client._await_port(0.5)
    message = str(failure.value)
    assert "did not start within" in message
    assert "PeakNav headless renderer" in message, "the transcript is the diagnosis"
    assert proc.killed, "a half-started renderer must not be left running"


def test_a_renderer_that_dies_reports_its_status_and_its_output():
    from peaknav.headless import PeakNavError
    proc = FakeProc(["PeakNav headless renderer: 46, 7\n", "Exception in thread main\n"],
                    exit_code=1)
    with pytest.raises(PeakNavError) as failure:
        _client_with(proc)._await_port(5)
    message = str(failure.value)
    assert "Exception in thread main" in message
    # The old code raised AttributeError from its own cleanup, hiding all of this.
    assert "AttributeError" not in message


def test_the_viewer_is_displayed_as_a_widget_not_as_its_repr():
    pytest.importorskip("ipywidgets",
                        reason="the widget layer needs ipywidgets: pip install peaknav[jupyter]")
    from IPython.core.formatters import DisplayFormatter

    from peaknav.jupyter import PeakNavViewer

    viewer = PeakNavViewer(FakeClient())
    data, _meta = DisplayFormatter().format(viewer)

    # The symptom of getting this wrong is unmistakable and was shipped once: the notebook
    # printed `HBox(children=(Image(value=b'\xff\xd8...` where the controls should have been,
    # because the object offered no widget mimetype and the frontend fell back to the text.
    assert "application/vnd.jupyter.widget-view+json" in data, (
        "the viewer offers no widget view, so a notebook can only print its repr")
    assert data["application/vnd.jupyter.widget-view+json"]["model_id"] == viewer.widget.model_id
    # And `_ipython_display_` must stay away: IPython prefers it over every other display
    # method, and that preference is what hid the widget in the first place.
    assert not hasattr(viewer, "_ipython_display_")


def test_choosing_imagery_goes_out_as_one_view_call(camera):
    camera.set_satellite("LANDSAT")
    assert camera.client.calls[-1] == ("view", {"satellite_provider": "LANDSAT"})


def test_a_custom_tile_template_carries_its_attribution(camera):
    # The template path is how a renderer is pointed at OpenStreetMap or any other XYZ
    # server; the name and attribution travel with it, because the app shows them.
    camera.set_satellite(template="https://tile.openstreetmap.org/{z}/{x}/{y}.png",
                         name="OpenStreetMap", attribution="© OpenStreetMap contributors")
    assert camera.client.calls[-1] == ("view", {
        "satellite_template": "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        "satellite_name": "OpenStreetMap",
        "satellite_attribution": "© OpenStreetMap contributors"})
    # Imagery is not a label change, so it must not drag a label pass along with it.
    assert camera.client.kinds().count("view") == 1
