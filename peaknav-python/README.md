# peaknav

Python tools for [PeakNav](https://peaknav.com), the 3D mountain viewer — in two
halves that share nothing but a namespace:

* **`peaknav.terrain`** — pure Python. The elevation of any coordinate on Earth,
  from the same compressed ASTER dataset the app renders — with summit heights
  corrected against surveyed values — downloaded once per area (a ~30 MB archive
  covers a 4×4 block of tiles) and cached. One dependency: Pillow.
* **`peaknav.headless`** — a standard-library client for the real PeakNav renderer
  running off-screen: camera control, view options, rendered frames. Needs a Java
  runtime, a display (the window is hidden, but GL still needs one), and the renderer
  jar — which is fetched on demand rather than shipped; see below.
* **`peaknav.jupyter`** — an interactive view inside a notebook, driven entirely over
  the renderer's REST API. `pip install peaknav[jupyter]`.

```python
>>> from peaknav.terrain import elevation_at
>>> elevation_at(45.9417, 7.7480)          # the Breithorn
4160
```

(The dataset carries summit corrections against surveyed heights. Raw ASTER, like
every stereo DEM, rounds off sharp spires — it clipped the Matterhorn to about
4040 m — but the corrected data tops out at 4484, the surveyed 4478 to within the
encoding's 4 m step. At ~30 m pixels the summit can still sit a pixel or two from
the coordinate you have for a peak — the classic Matterhorn coordinate reads
4312 — so scan a few arcseconds around a spire's coordinate for its true top;
`examples/01_elevation.ipynb` shows how.)

```python
from peaknav.headless import PeakNavHeadless

with PeakNavHeadless(45.9763, 7.6586) as nav:
    nav.move_to(45.9763, 7.6586, download_timeout_ms=600_000, await_tiles_ms=120_000)
    nav.look(bearing_deg=230, pitch_deg=-4)
    nav.set_altitude_asl(3200)
    nav.set_view(sky=True, sky_mode="day", labels=["peaks", "roads"])
    nav.wait(tiles_timeout_ms=60_000, settle_ms=1_000)
    nav.save_frame("matterhorn.png")
```

The renderer speaks plain HTTP, self-described at `/openapi.json` — anything that
can `curl` can drive it; this client adds process lifecycle (the JVM dies with the
`with` block) and nothing magical.

## Install

```bash
pip install peaknav                        # once published; from source:
pip install -e "peaknav-python[dev]"
```

## Examples

Four notebooks in [`examples/`](examples/), in increasing order of what they need from the
machine:

| Notebook | Shows | Needs |
| --- | --- | --- |
| `01_elevation.ipynb` | elevation of any coordinate, a profile along a line, the tile encoding | network only |
| `02_renderer_over_rest.ipynb` | driving the renderer over REST, frames back — no widgets | Java, a display, the jar |
| `03_interactive_widget.ipynb` | the `PeakNavViewer` widget, and driving it from code | the above + `peaknav[jupyter]` |
| `04_panorama_sweep.ipynb` | scripted rendering: a full-circle panorama and a short flight | Java, a display, the jar |

They are stored without outputs — rendered frames would dominate every diff, and a stale
picture beside changed code is worse than none.

## Tests

```bash
pytest                                     # doctests in every module, plus tests/
PEAKNAV_NETWORK_TESTS=1 pytest tests/test_terrain.py     # also hit the live dataset
```

The doctests are the documentation's examples, so an example that stops working
fails the suite rather than misleading a reader. Tests that need the network, the
jar, or a display skip themselves with a reason.

## In a notebook

```python
from peaknav.headless import PeakNavHeadless
from peaknav.jupyter import PeakNavViewer

nav = PeakNavHeadless(46.0207, 7.7491)          # or .attach("http://127.0.0.1:8080")
PeakNavViewer(nav, bearing_deg=230, pitch_deg=-4, altitude_m=3200)
```

That last line is the widget: pan and tilt buttons, a height control, coordinates to
type, display toggles and the rendered view. `pip install peaknav[jupyter]` — it needs
ipywidgets, which the base package does not install.

The widget is **a REST client and nothing else**. Every control becomes a documented
HTTP call on the renderer's own server (`POST /camera`, `POST /position`, `POST /view`,
`GET /frame`), made through the client you hand it. It never starts a renderer, never
looks for a jar and never touches a subprocess — so it drives one you started, or one
already running elsewhere, with no difference in the code.

The two layers are separable on purpose:

* `peaknav.jupyter.camera.ViewerCamera` — where the camera is and what each movement
  sends. Plain Python, no dependencies; usable from a script, and what the tests drive
  with a stub client to check exactly which REST calls come out.
* `peaknav.jupyter.viewer.PeakNavViewer` — the ipywidgets face on it.

Moving the camera from another cell is fine; call `viewer.sync_from_camera()` afterwards
so the sliders and the picture agree again. For a single picture with no controls at all,
`peaknav.jupyter.show(nav)` needs only IPython.

## The renderer jar

The renderer is 75 MB of Java. Putting it inside the wheel would make everyone who
only wants `peaknav.terrain` — pure Python, one dependency — download it too, so it is
found rather than shipped. First hit wins:

1. the `jar=` argument to `PeakNavHeadless`;
2. `$PEAKNAV_HEADLESS_JAR`, to point a whole session at one build;
3. `headless/build/libs/` of a PeakNavApp checkout, so a developer's own build always
   beats a download;
4. the cache, `$XDG_CACHE_HOME/peaknav/jars` (`%LOCALAPPDATA%` on Windows);
5. the release asset, downloaded into that cache, once.

`$PEAKNAV_NO_DOWNLOAD` forbids step 5 — on a build machine that should not reach the
network it turns a silent 75 MB fetch into an error naming what is missing. A download
is checked before it is cached: structurally (a readable zip containing the renderer's
entry point, which is what catches a truncated file or the wrong asset) and, when a
digest is pinned for that version, against it.

```python
from peaknav.headless import ensure_jar
ensure_jar()          # fetch it now rather than on the first render
```

**No release carries the renderer jar yet.** `peaknav-1.1.0.jar` on the releases page
is the desktop application and has no renderer inside it. Until
`peaknav-headless-<version>.jar` is attached to a release, use a local build
(`./gradlew :headless:renderJar`) or `$PEAKNAV_HEADLESS_JAR`; step 5 fails with a
message saying exactly that. When publishing one, raise `JAR_VERSION` in
`peaknav/headless/jar.py` to that release and record its `sha256sum` in `KNOWN_SHA256`
in the same commit.

## Documentation

Built from the docstrings — no separate prose to drift out of date:

```bash
pdoc peaknav peaknav.terrain peaknav.headless -o docs/   # static HTML into docs/
```

## Build for PyPI

```bash
python -m build                            # sdist + wheel into dist/
twine upload dist/*
```

## The elevation encoding, briefly

Each zoom-8 slippy tile is a JPEG + PNG pair: the PNG names each pixel's 1024 m
band (`128 + floor(e/1024)`), the JPEG the position inside it in 4 m steps, with
odd bands flipped so band edges stay smooth gradients that JPEG compresses without
ringing. `peaknav.terrain.decode_elevation` is the four-line inverse, doctested
against an independent port of the dataset's encoder. The dataset ships the pairs
packed one `.tar.gz` per zoom-6 tile — the same archives the app downloads — which
the module unpacks into its cache on first use of an area. Summit queries default
to the max of the four surrounding pixels (ASTER's ~30 m posting rarely centres a
summit on one); pass `sample="bilinear"` for slopes and profiles.
