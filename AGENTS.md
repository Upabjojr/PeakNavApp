# AGENTS.md

Guidance for AI coding agents working in this repository. Human contributors may
find it useful too.

## Project

PeakNav ([peaknav.com](https://peaknav.com)) renders world mountains in 3D, with
paths, ways, and nearby peak names projected onto the terrain. It is a
[libGDX](https://libgdx.com/) cross-platform app built with Gradle, shipping on
Desktop and Android (iOS and HTML targets exist but are incomplete).

Terrain and OpenStreetMap data come from two HuggingFace datasets, retiled to the
[slippy map](https://wiki.openstreetmap.org/wiki/Slippy_map_tilenames) convention
(see `README.md`).

## Module layout

Gradle modules (`settings.gradle`): `core`, `desktop`, `android`, `ios`, `html`,
`headless`.

- **`core`** — all shared, platform-independent logic. Package root
  `com.peaknav`. Almost every change belongs here. Sub-packages of note:
  - `viewer/` — the libGDX app (`MapApp`), screens, camera, rendering, tiles.
  - `viewer/controller/` — `MapController` (the app-wide context, reached via
    `PeakNavUtils.getC()`), `CurrentLocation` (`getC().L`).
  - `compatibility/` — abstractions each platform implements (`NativeScreenCaller`,
    `LoadFactory`, etc.).
  - `elevation/`, `pbf/`, `database/`, `network/`, `satellite/`, `utils/`.
  - `skyline/` — matching a photograph's skyline to the terrain: `TerrainHorizon`
    (the horizon all around a point, from an `ElevationSampler`), `SkylineExtractor`
    (the sky/ground line in a picture: `SkyFeatures` + `SkyClassifier`, a
    gradient-boosted forest read from the resource `sky_model.bin` next to it, give
    every pixel a sky probability; `BoundaryFeatures` + `boundary_model.bin` score
    every position as "the skyline passes here"; a minimum-cost path traces the
    boundary; both forests are trained by `tools/skyline_train.py`) and
    `SkylineMatcher` (bearing, pitch and field of view by optimisation, with a
    calibrated "confident" verdict). Pure Java, no libGDX; `viewer/PhotoSkylineAligner`
    is the app-side glue that runs it when a geotagged photo is loaded and offers
    to point the camera, or on demand from the photo bar's match button; debug
    builds (`LoadFactory.isDebugBuild()`) get a third button that saves the photo,
    pose and overlay as a dataset sample under `LoadFactory.getDebugSamplesDir()`.
    `gesture/PhotoPin` is the pinned-point state the input controller rotates and
    zooms around while a photo is shown. Its accuracy is measured, not assumed: see the
    `skylineBenchmark` tool below, and keep the thresholds in `SkylineMatcher`
    tied to what the benchmark reports.
- **`desktop`** — LWJGL3 launcher (`DesktopLauncher`), Swing-based native screens.
- **`android`** — Android launcher/activity, fragments, native screens.
- **`ios`** — RoboVM launcher plus a real `IOSLoadFactory`: logging, caches, file
  writing, crash reports, a `libsqlite3` binding for the tile catalogue, and the
  `NativeScreenCallerIOS` surface. Builds and runs (verified in the iPhone
  simulator); see "iOS" under Build & run for what is still missing.
- **`html`** — GWT target.
- **`headless`** — drives the real renderer off-screen (`PeakNavRenderer`,
  `RenderCli`; driven by `snapshots/generate_snapshots.py`): programmatic camera, label/sky toggles, waits
  built on `PeakNavAppState` signals, snapshot capture. See `headless/README.md`;
  its test suite (`:headless:test`) boots the actual app.

## Cross-platform architecture

Shared code in `core` never talks to a platform API directly. Instead it goes
through interfaces/abstract classes in `com.peaknav.compatibility`, each with a
concrete per-platform implementation wired up at startup:

- **`NativeScreenCaller`** — native UI: file/gallery pickers, dialogs, toasts,
  sharing, permissions. Implemented by `NativeScreenCallerDesktop` (Swing /
  `JOptionPane`), `NativeScreenCallerAndroid` (`AlertDialog`, fragments) and
  `NativeScreenCallerIOS` (`UIAlertController` presented on the key window).
  Reached from shared code via `PeakNavUtils.getNativeScreenCaller()` — it can
  still be `null` before a platform has wired one in, so null-check before use.
- **`LoadFactory`** — provides platform services (SQLite, downloaders, graphics
  factory, logger, caches). Set into `MapApp` by each launcher.

**When you add a native capability**, add an `abstract` method to
`NativeScreenCaller` and implement it in *all three* of
`NativeScreenCallerDesktop`, `NativeScreenCallerAndroid` and
`NativeScreenCallerIOS`. iOS does subclass it now, so leaving it out breaks
`:ios:compileJava` — and only on a Mac, where that module tends to get built.

Common patterns from shared code:
- Navigate the camera to coordinates: `getC().L.setCurrentTargetCoords(lat, lon)`
  (also triggers a "download missing map data" prompt when needed).
- Show localized text: `PeakNavUtils.s("Some_Key")` (see i18n below).

## Build & run

Use the **wrapper** (`./gradlew`, Gradle 9.3.0), not a system `gradle` — a system
8.x will not build this project. The build needs JDK 17; pass it explicitly if your
default JDK is something else. The daemon is disabled by config, so builds are
one-shot and each command cold-starts.

```bash
J=-Dorg.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew :core:compileJava $J        # compile shared code (fast sanity check)
./gradlew :desktop:compileJava $J     # compile desktop
./gradlew :desktop:run $J             # run the desktop app (mainClass DesktopLauncher)
./gradlew :core:test $J               # JUnit 5 tests (core/src/test/java)
./gradlew :headless:test $J           # boots the real app off-screen; needs DISPLAY
```

- **Android** requires the SDK: set `ANDROID_HOME` or add `sdk.dir` to
  `local.properties`. Without it `:android:*` tasks fail (that is an environment
  issue, not a code error). Compile with
  `gradle :android:compileDebugJavaWithJavac`.
- **iOS** builds only on a Mac, and needs **full Xcode** — the Command Line Tools
  alone are not enough. RoboVM shells out to `xcrun`/`simctl` and rejects a
  developer directory that is not an Xcode bundle
  ("`/Library/Developer/CommandLineTools` does not appear to be a valid Xcode
  path"). Install Xcode, then `sudo xcode-select -switch /Applications/Xcode.app`.

  ```bash
  ./gradlew :ios:build                  # Java only — works on any OS, no Xcode needed
  ./gradlew :ios:launchIPhoneSimulator  # AOT-compile, link and run in the simulator
  ./gradlew :ios:createIPA              # device build; needs a signing identity
  ```

  `:ios:build` compiles the module's Java against `core` and is the check worth
  running from Linux or Windows — it catches the usual breakage (a new abstract
  method on `NativeScreenCaller`) without any Apple tooling. The native link is
  where the rest shows up: it needs the `natives-ios` jars for every gdx extension
  `core` uses (`gdx-platform`, `gdx-freetype-platform`, `gdx-box2d-platform` — see
  `ios/build.gradle`), since a Java-only compile passes happily without them.

  Before the first build, generate the app icons with `ios/build_icons.sh` (needs
  `brew install librsvg`). The asset catalogue names 18 PNGs that `.gitignore`
  excludes, so a fresh clone has none of them and `actool` produces an icon-less
  bundle.

  A matching **iOS platform** must be installed too, not just Xcode — `ibtool`
  compiles `LaunchScreen.storyboard` and fails with `iOS <version> Platform Not
  Installed` without it, *after* the AOT compile and link have both succeeded.
  Install it with `xcodebuild -downloadPlatform iOS` (a ~10 GB download; no sudo
  needed).

  The GUI Simulator is not required: `xcrun simctl` can boot, install, launch and
  screenshot headlessly, which is useful because Xcode's Simulator.app is fussy
  about matching the host macOS — an Xcode whose Simulator predates the host will
  die at launch with a missing-symbol dyld error while `simctl` carries on fine.

  ```bash
  xcrun simctl install booted ios/build/robovm.tmp/IOSLauncher.app
  xcrun simctl launch booted com.peaknav.viewer      # add --console-pty for logs
  xcrun simctl io booted screenshot shot.png
  ```

  Downloading data and search *are* built. The download methods are deliberately
  thin — the work is all `core`'s, exactly as on the desktop, and what a platform
  has to get right is running it off the render thread and clearing
  `setMapDataDownloadStarted` in a `finally` (left set, the missing-data prompt
  never fires again for the whole session). Search is a `UIAlertController` asking
  for text and a second one offering the results, not the scrolling screen the
  other platforms build; typing `lat, lon` navigates straight there.

  Two traps worth keeping in mind if you touch that flow:
  - The download consent (`P.setCollectDownloadInfo`) must be set **in the same
    task** as the download it enables. `PeakNavDownloadManager` skips every request
    without it, so a download that starts first shows progress and fetches nothing.
  - `OnlineSearch.failed()` never calls its listener, so anything waiting on a
    Nominatim response needs its own timeout or it waits forever.

  What is still missing, and will surface at runtime rather than at compile time:
  `getGraphicFactory()` returns `null` (no mapsforge backend for iOS, so no road
  and path layer — the 3D terrain, satellite imagery, labels and sky do not use
  it); and search finds only online results until `assets/geonames_index.362` is
  built. Everything else the shared UI reaches is implemented: GPS and the
  gyroscope camera via CoreLocation and CoreMotion (`LocationControllerIOS`,
  `OrientationPointerControllerIOS` — the latter a port of Android's
  `OrientationPointerController` with CoreMotion's reference frame
  (X = magnetic north, Y = west) swapped into the app's east-north-up world);
  the gallery/camera pickers through `UIImagePickerController` (geotags come
  from the `PHAsset`, since iOS strips GPS EXIF from picked files); the tutorial
  and app-info pages in a `WKWebView` over the bundled HTML; and GPX both from
  the in-app document picker and handed over by other apps (Info.plist document
  type + `IOSLauncher.openURL`).

### Points vs pixels

`IOSLauncher` sets `config.hdpiMode = HdpiMode.Pixels`. **Do not remove it.** The
backend's default is `Logical`, where `Gdx.graphics.getWidth()` returns points —
375 on a 2× phone with a 750-wide framebuffer. Shared code hands that value
straight to `Gdx.gl.glViewport` (`AbstractScreen`, `IntroScreen`,
`MapViewerScreen`), so the whole app renders into the bottom-left quarter of the
screen; and `DefaultIOSInput` scales touches by `pixelsPerPoint` whatever the mode
is, so input and rendering end up one display-scale apart and nothing is tappable.

Neither symptom can appear on the other targets — Android reports pixels, and a
non-retina desktop display has logical == pixels — so this is iOS-only by nature
and easy to reintroduce.

### What RoboVM's runtime does *not* have

This is the single biggest source of iOS-only breakage, and none of it fails at
compile time — `core` is compiled against a JDK, and only the AOT link and the
device runtime see the difference. RoboVM's class library is derived from
Android's libcore and predates Java 8:

- **No `java.util.function`, no `java.util.stream`, no `java.nio.file`, no
  `java.time`, no `Optional`.** The AOT compiler reports these as
  `phantom class` warnings and carries on; you find out at runtime.
- **No Java 8 statics**: `Integer.max`, `Float.min`, `Double.max`, `String.join`
  and friends. Use `Math.max`/`Math.min` and build strings by hand — they behave
  identically everywhere, so this costs the other platforms nothing.
- **No Java 8 default methods on collections**: `List.sort`, `Map.putIfAbsent`,
  `Map.getOrDefault`, `Map.computeIfAbsent`, `Collection.removeIf`,
  `Iterable.forEach`. Use `Collections.sort(list, cmp)`; for `putIfAbsent`,
  declare the reference as `ConcurrentMap` (a Java 5 interface RoboVM does have)
  rather than `Map`. A single `List.sort` in the label renderer threw
  `NoSuchMethodError` mid-frame once terrain existed, which aborted every frame
  before `stage.draw()` — the symptom was "all buttons and labels invisible but
  still clickable", three layers from the cause.
- **No Java 8 constants** like `Float.BYTES` (javac inlines them, so these are
  compile-audit noise rather than runtime crashes — but keep them out anyway).
- **No `Locale.getScript()`**, a Java 7 method Android added late.
- **No `java.io.File.toPath()`** either, so code cannot even reach `java.nio.file`.
  To rename a finished file into place, use
  `PeakNavUtils.getLoadFactory().getFileMover()` — a `com.peaknav.utils.FileMover`
  supplied per platform, `NioFileMover` (nio, atomic-and-replacing stated in the
  type system) on desktop/Android/headless and `RenameFileMover` (POSIX
  `rename(2)`, the same guarantee) on iOS. The downloader's direct
  `Files.move(REPLACE_EXISTING, ATOMIC_MOVE)` calls used to fail here, which meant
  every tile downloaded in full and then died on the last step.

Two consequences worth knowing before adding a dependency to `core`: Guava cannot
be used at all (its `Equivalence` implements `BiPredicate`, so building any cache
throws `NoClassDefFoundError` on launch — `com.peaknav.utils.LruCache` replaced
it), and ICU4J is pinned to 59.1 on iOS because 63+ call `Locale.getScript()`
directly. **A new dependency in `core` needs checking against this list**, since
`:ios:compileJava` will pass regardless.

The whole class of bug can be caught at a desk instead of on a device: compile
`core` (and `ios/src`) with the RoboVM runtime as the bootclasspath and javac
flags every missing API at once —

```sh
javac -nowarn -source 8 -target 8 \
  -bootclasspath ~/.gradle/caches/**/robovm-rt-2.3.25.jar \
  -cp "<runtimeClasspath>" -d /tmp/out $(find core/src/main/java -name '*.java')
```

The only expected errors are `NioFileMover`'s `java.nio.file` references: that
class is the desktop/Android implementation of `FileMover`, constructed only by
those platforms' `LoadFactory`s and never reachable from the iOS entry point
(and `ios/robovm.xml` force-links no `com.peaknav` classes, so RoboVM never
tries to compile it). Run this after touching `core` in any nontrivial way; it
is minutes cheaper than an AOT build and catches what the normal compile cannot.

Two more RoboVM-side traps, both handled in `ios/build.gradle`:

- **Pre-Java-6 `jsr`/`ret` bytecode** is rejected outright
  (`Unrecognized bytecode instruction: 168`). Lucene 3.6.2 is full of it, so the
  three Lucene jars are rewritten through ASM's `JSRInlinerAdapter` by the
  `inlineLuceneJsr` task. Other platforms use the untouched jars.
- **Soot, RoboVM's bytecode frontend, cannot read some modern bytecode**:
  "Exception reference used other than as the first statement of an exception
  handler". commons-compress hit this (and two runtime failures besides), which
  is why the project no longer uses it at all — downloads are unpacked with
  `java.util.zip.GZIPInputStream` + `com.peaknav.utils.TarReader` instead.
- Full asset/data setup (fonts, icons, Lucene geonames index) is described in
  `README.md`; a plain `:core`/`:desktop` compile does **not** need it.
- **Build-time data tools** live in their own source set, `core/src/tools/java`
  (package `com.peaknav.tools`), so nothing they pull in ships in the app. Each is
  exposed as a Gradle task in the `peaknav` group — currently
  `./gradlew :core:buildGeonamesIndex --args="cities500.txt alternateNamesV2.txt out_dir"`,
  which rebuilds the place-search index, and
  `./gradlew :core:skylineBenchmark --args="path/to/manifest.json"`, which runs the
  photo skyline matcher over a dataset of geotagged photos with a known camera
  heading and reports accuracy and the precision of its confident matches. The
  datasets are built by `tools/skyline_dataset.py` (GeoPose3K, or Wikimedia Commons
  photos carrying a `heading:`); `TestSkylineDataset` runs the same benchmark when one
  is installed under `~/.peaknav/skyline_dataset/` or `$PEAKNAV_SKYLINE_DATASET`, and
  skips otherwise. `skylineTrainingDump` writes the two forests' training rows (from
  a truth-ridge file made once from posed photos, or from pictures with a sky mask)
  with the app's own feature code, `tools/skyline_train.py` fits and exports a
  forest, and `skylineMaskEval` scores the extractor alone against hand-traced
  skylines (CH1 and the web set, never used for training). Changing `SkyFeatures` or
  `BoundaryFeatures` means retraining both: a model file records its feature count
  and the loader ignores a mismatch. Measure any change to the extractor on both the
  bearing benchmark and the mask score before keeping it; the numbers that matter are
  gross misses, not pixels. Put new data-prep tools here rather than in
  a test: an index builder hidden in `TestLuceneGeonames` meant a half-built index
  directory from an earlier run could fail the whole suite.

### Desktop installers

```bash
./gradlew :desktop:installers       # Windows .exe, macOS .dmg (x2), Linux .deb + .AppImage
```

Built with construo (application image + bundled JRE 17) wrapped by NSIS,
genisoimage, dpkg-deb and mksquashfs — all of which cross-compile, so every artefact
is produced from one machine whichever OS it runs. `jpackage` deliberately is not
used: it only emits an installer for the OS it runs on. Needs `nsis`,
`genisoimage` and `squashfs-tools` installed. See `desktop/packaging/README.md`,
especially the signing section — nothing is code-signed for Windows or macOS, and
that is not something the build can fix.

## Conventions

- **Java 8** source/target across non-Android modules. No records, `var`, switch
  expressions, or other 9+ syntax in `core`. Android may use newer APIs guarded by
  its min SDK.
- 4-space indentation; match the surrounding file's style, naming, and comment
  density.
- Keep new dependencies out of `core` unless clearly justified — the module
  intentionally stays lean and must run on Android's constraints
  (e.g. Lucene is pinned to **3.6.2**, the last Android-compatible release — do
  not bump it).
- Prefer writing shared, testable logic in `core` over duplicating it per platform.

## Internationalization

User-facing strings live in `assets/i18n/strings_<lang>.properties` for
**en, it, fr, de, es, pt, no**. There is no base `strings.properties`, and missing
keys render as `???key???` — so **add every new key to all 7 files**. Look strings
up with `PeakNavUtils.s("Key")`.

## Gotchas

- `getNativeScreenCaller()` may be `null` — guard it.
- `getGraphicFactory()` is `null` on iOS; `TileRenderer` treats that as "this
  platform has no path layer" and skips the mapsforge machinery. Anything new that
  reaches for the factory must do the same.
- The Gradle wrapper JAR is **not** in git (`.gitignore` ignores `/gradle/`), so
  `./gradlew` fails with "Unable to access jarfile" on a fresh clone. Regenerate it
  with a system Gradle 9.3.0: `gradle wrapper --gradle-version 9.3.0`.
- The Gradle daemon is off; expect each command to cold-start.
- Don't rely on the Android module compiling in a headless/SDK-less environment;
  verify Android changes against existing patterns in the `android` module.
