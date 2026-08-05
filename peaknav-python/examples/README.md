# Example notebooks

Four notebooks, in increasing order of what they demand from the machine. Start with the
first one — it needs nothing but the package.

| Notebook | What it shows | Needs |
| --- | --- | --- |
| [`01_elevation.ipynb`](01_elevation.ipynb) | `peaknav.terrain`: the elevation of any coordinate (summits corrected against surveyed heights), a profile along a line, and the tile encoding underneath | network (each area's ~30 MB archive is downloaded once and cached) |
| [`02_renderer_over_rest.ipynb`](02_renderer_over_rest.ipynb) | Driving the renderer over its REST API and reading frames back — no widgets | Java, a display, the renderer jar |
| [`03_interactive_widget.ipynb`](03_interactive_widget.ipynb) | The `PeakNavViewer` widget, and driving it from code | the above, plus `peaknav[jupyter]` |
| [`04_panorama_sweep.ipynb`](04_panorama_sweep.ipynb) | Scripted rendering: a full-circle panorama and a short flight, assembled with Pillow — no widgets | Java, a display, the renderer jar |

```bash
pip install -e ..                # or: pip install peaknav
pip install -e '..[jupyter]'     # only for notebook 03
jupyter lab                      # or: jupyter notebook
```

## What "needs a display" means

The renderer creates its window hidden, but GL still needs a display connection to hand
out a context. So notebooks 02–04 work on a desktop session and not over a bare SSH
connection; on a headless machine, run the kernel under `Xvfb`.

They also need the renderer jar. It is not shipped inside the wheel — 75 MB is no way to
package Python — so it is looked for in a checkout you have built
(`./gradlew :headless:renderJar`), then in a cache, and fetched from the release if it is
in neither. `peaknav.headless.jar` documents the order and the ways to steer it, including
`$PEAKNAV_HEADLESS_JAR` to point at one you already have.

## Notebook 01 is the one that always works

No Java, no display, no jar: pure Python and Pillow. If you are checking that an install
is sound, run that one first.

## They are stored without outputs

Deliberately — the outputs are megabytes of rendered image, they would dominate every
diff, and a stale picture next to changed code is worse than no picture. Run the cells to
see them.
