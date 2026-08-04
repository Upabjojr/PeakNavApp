"""PeakNav inside a Jupyter notebook: an interactive view, driven over the REST API.

    from peaknav.headless import PeakNavHeadless
    from peaknav.jupyter import PeakNavViewer

    nav = PeakNavHeadless(46.0207, 7.7491)        # or .attach("http://127.0.0.1:8080")
    PeakNavViewer(nav, bearing_deg=230, pitch_deg=-4, altitude_m=3200)

The widget is a REST client and only that: every button is one or two documented HTTP
calls on the renderer's own server, made through the client you hand it. It never starts a
renderer and never looks for a jar, so it works just as well against one that is already
running somewhere else.

Two layers, because only one of them needs a notebook:

* :mod:`peaknav.jupyter.camera` - where the camera is and what each movement sends. Plain
  Python, no dependencies, testable without any of this.
* :mod:`peaknav.jupyter.viewer` - the ipywidgets face on it. Needs ``ipywidgets``:
  ``pip install 'peaknav[jupyter]'``.
"""

from .camera import ViewerCamera
from .viewer import PeakNavViewer, show

__all__ = ["PeakNavViewer", "ViewerCamera", "show"]
