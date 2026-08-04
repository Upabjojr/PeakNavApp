# Headless renderer

Builds the app as a library that draws off-screen, so views can be produced from code
instead of from a person at a keyboard — for screenshots, documentation images, and as a
way to exercise the real renderer from a test.

```java
try (PeakNavRenderer r = PeakNavRenderer.start(1600, 1000)) {
    r.moveTo(46.0207, 7.7491);     // Zermatt
    r.awaitTilesLoaded(120_000);   // terrain, tiles AND satellite imagery
    r.aim(210f, -2f);              // bearing 210° (SSW), 2° below the horizon
    r.awaitLabelsRendered(30_000); // at least one label actually drawn
    r.capture(new File("matterhorn.png"));
}
```

Or from a shell:

```bash
./gradlew :headless:run --args="--lat 46.0207 --lon 7.7491 --bearing 210 --out out.png"
./gradlew :headless:renderJar        # standalone fat jar, no Gradle needed to run it
python3 snapshots/generate_snapshots.py   # the snapshot set (see snapshots/manifest.tsv)
```

Several views can be captured from one boot, which is far cheaper than starting the app
per image — start-up and the first tile load dominate. A shot may carry its own
viewpoint height as a third field:

```bash
--shot 210,-2,matterhorn.png --shot 20,-8,0.9,north-wide.png
```

## It is not libGDX's headless backend

`gdx-backend-headless` stubs graphics out entirely: no GL context, so it cannot draw a
single terrain pixel. This module uses the ordinary LWJGL3 backend with the window created
**hidden** (`setInitialVisible(false)`). That gives a real GL context whose frames can be
read back, while nothing is ever mapped onto a display. A display connection is still
required, because GLFW needs one to create a context — run it from a graphical session.

## No prompts, ever

Headless means headless: there is nobody to answer a dialog, and a modal one would sit
invisibly on the display and stall the run forever (the missing-map-data prompt used to do
exactly that). `FileSnapshotWriter` — installed as the app's `NativeScreenCaller` —
overrides every UI-raising method: prompts are suppressed and logged to stderr, and
`moveTo()` passes `checkMissing=false` so moving the viewpoint never asks anything.
Fetching data is an explicit call, `downloadMissingData(lat, lon, timeout)` (CLI:
`--download`), which goes through `CheckMissingData` directly, downloads only what the
area is missing, and waits for the result. The test suite asserts that a render over an
area with no data raises zero prompts.

## Notes on the implementation

- **Threading.** `Lwjgl3Application`'s constructor does not return until the app exits, so
  it runs on its own thread. Every API method hops onto the render thread and blocks until
  the work is done, so callers write straight-line code.
- **Capture is the app's own snapshot.** `capture()` triggers
  `MapViewerScreen.takeSnapshot()` — the same path as the share button, taken at the same
  point in the frame (after terrain and labels, before the interface is drawn, so no
  buttons appear in the image) — and saves through the same
  `NativeScreenCallerDesktop.savePixmapToFile`, so a scripted capture and the share button
  cannot drift apart. Alpha is forced opaque on save: GL blending writes destination
  alpha, and copying it into the PNG made translucent label plates look like holes.
- **Aiming goes through the app's camera API.** The camera is owned by
  `MapViewerScreen.moveCameraAction`; writing `cam.direction` directly is overwritten by
  whatever move that action has queued. `aim()` therefore calls
  `setCameraVectors(null, direction, up, immediate=true)` — the same call the gyroscope
  path uses — which applies the pose and clears the queue. World axes are ENU
  (+X east, +Y north, +Z up), the convention behind `atan2(camDir.y, camDir.x)` in
  `DataRetrieveThreadManager`.
- **Order matters for elevation.** While tiles load, the app flies the camera onto the
  terrain itself; a height set before that fly is overwritten by it. Set elevation after
  `awaitTilesLoaded`, as the CLI does (per-shot elevations handle this automatically).
- **Height has two modes, and you want metres.** `MapViewerScreen` exposes
  `setCameraElevationMeters` (the primitive: a height above the terrain, in metres) and
  `setCameraElevationBar` (a wrapper that first bends a 0-1 slider position through
  `Interpolation.exp5In`, so the bar gives fine control near the ground and kilometres per
  drag high up). The curve is steeper than it looks - bar 0.45 is about **2.5 km** up, not
  450 m - so anything computing a viewpoint should say metres and let the interface keep the
  curve. On the CLI that is `--elevation-m 2500`, or a per-shot height with a trailing `m`:
  `--shot 210,-4,2500m,out.png`.

## Waiting on facts, not guesses

`PeakNavAppState` carries signals written where the work actually happens, so "the view
is finished" is observable:

- `getPendingSatelliteWork()` — a counter bracketing each satellite tile fetch-and-draw
  (`TileRendererRunnerSatellite`); 0 means all requested imagery has arrived and been
  drawn.
- `getVisibleLabelCount()` — how many labels the last frame drew (`LabelRenderer`).

On top of these, `awaitTilesLoaded(timeout)` returns once map-data loading is idle, no
satellite work is pending, and tile updates have been quiet for 2 s; and
`awaitLabelsRendered(timeout)` returns once at least one label is actually on screen.
`settle(millis)` remains for a fixed dwell.

## What can be controlled

| | |
| --- | --- |
| `moveTo(lat, lon)` | viewpoint position (never prompts) |
| `aim(bearing, pitch)` | compass bearing (0 = north) and pitch (positive looks up) |
| `setElevationMeters(m)` | viewpoint height in metres above the ground |
| `setElevation(fraction)` | viewpoint height as a position on the app's elevation bar |
| `setLabel(Label, on)` / `clearLabels()` | peaks, place names, cities, mountain ranges, islands, lakes, alpine huts, roads, pistes, navigation |
| `setSky` / `setConstellations` / `setSunShading` / `setHorizonCompass` | sky and shading |
| `downloadMissingData(lat, lon, timeout)` | fetch what the area is missing, and wait |
| `awaitTilesLoaded` / `awaitLabelsRendered` / `settle` | waiting |
| `suppressedPrompts()` | how many UI prompts were intercepted (tests assert 0) |
| `capture(file)` | write a PNG or JPEG, chosen by extension |

## Tests

`./gradlew :headless:test` boots the real app once and runs the suite against it:
camera aiming (ENU conversion, level horizon), label toggles, PNG/JPEG capture,
image orientation and opacity, the no-prompt guarantee, and the label-rendered signal.
The class skips itself when `DISPLAY` is unset.
