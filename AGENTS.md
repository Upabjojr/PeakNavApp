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

Gradle modules (`settings.gradle`): `core`, `desktop`, `android`, `ios`, `html`.

- **`core`** — all shared, platform-independent logic. Package root
  `com.peaknav`. Almost every change belongs here. Sub-packages of note:
  - `viewer/` — the libGDX app (`MapApp`), screens, camera, rendering, tiles.
  - `viewer/controller/` — `MapController` (the app-wide context, reached via
    `PeakNavUtils.getC()`), `CurrentLocation` (`getC().L`).
  - `compatibility/` — abstractions each platform implements (`NativeScreenCaller`,
    `LoadFactory`, etc.).
  - `elevation/`, `pbf/`, `database/`, `network/`, `satellite/`, `utils/`.
- **`desktop`** — LWJGL3 launcher (`DesktopLauncher`), Swing-based native screens.
- **`android`** — Android launcher/activity, fragments, native screens.
- **`ios`** — RoboVM launcher. Largely a stub (`LoadFactory` returns `null`s).
- **`html`** — GWT target.

## Cross-platform architecture

Shared code in `core` never talks to a platform API directly. Instead it goes
through interfaces/abstract classes in `com.peaknav.compatibility`, each with a
concrete per-platform implementation wired up at startup:

- **`NativeScreenCaller`** — native UI: file/gallery pickers, dialogs, toasts,
  sharing, permissions. Implemented by `NativeScreenCallerDesktop` (Swing /
  `JOptionPane`) and `NativeScreenCallerAndroid` (`AlertDialog`, fragments).
  Reached from shared code via `PeakNavUtils.getNativeScreenCaller()` — **this can
  be `null` on iOS**, so null-check before use.
- **`LoadFactory`** — provides platform services (SQLite, downloaders, graphics
  factory, logger, caches). Set into `MapApp` by each launcher.

**When you add a native capability**, add an `abstract` method to
`NativeScreenCaller` and implement it in *both* `NativeScreenCallerDesktop` and
`NativeScreenCallerAndroid`. iOS does not subclass it, so it needs no change.

Common patterns from shared code:
- Navigate the camera to coordinates: `getC().L.setCurrentTargetCoords(lat, lon)`
  (also triggers a "download missing map data" prompt when needed).
- Show localized text: `PeakNavUtils.s("Some_Key")` (see i18n below).

## Build & run

There is no committed Gradle wrapper jar. Use a system `gradle` (Gradle 8.x, matching
`com.android.tools.build:gradle:8.9.3`), or the daemon is disabled by config, so
builds are one-shot.

```bash
gradle :core:compileJava            # compile shared code (fast sanity check)
gradle :desktop:compileJava         # compile desktop
gradle :desktop:run                 # run the desktop app (mainClass DesktopLauncher)
gradle :core:test                   # JUnit 5 tests (core/src/test/java)
```

- **Android** requires the SDK: set `ANDROID_HOME` or add `sdk.dir` to
  `local.properties`. Without it `:android:*` tasks fail (that is an environment
  issue, not a code error). Compile with
  `gradle :android:compileDebugJavaWithJavac`.
- Full asset/data setup (fonts, icons, Lucene geonames index) is described in
  `README.md`; a plain `:core`/`:desktop` compile does **not** need it.

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

- `getNativeScreenCaller()` may be `null` (iOS) — guard it.
- The Gradle daemon is off; expect each command to cold-start.
- Don't rely on the Android module compiling in a headless/SDK-less environment;
  verify Android changes against existing patterns in the `android` module.
