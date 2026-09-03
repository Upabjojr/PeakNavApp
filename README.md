# PeakNavApp

[PeakNav](https://peaknav.com) is an app to view world mountains in 3D.

Explore the mountains, see paths and ways projected onto their 3D shapes, as well as the names of the nearby peaks!

Available for **Android**, **Windows**, **macOS** and **Linux**.

<!-- GitHub's markdown sanitizer drops `style` and `class` attributes, so the badge is
     sized with the `width` attribute, which it does keep. -->
[<img alt="Get it on Google Play" src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" width="200">](https://play.google.com/store/apps/details?id=com.peaknav)

<!-- An <iframe> is stripped entirely by GitHub, so the video is a thumbnail that links
     to YouTube instead of an embedded player. -->
[![Watch PeakNav in action](https://img.youtube.com/vi/y4WspQmcwQw/hqdefault.jpg)](https://www.youtube.com/watch?v=y4WspQmcwQw)

## Download

**Android** — [Google Play](https://play.google.com/store/apps/details?id=com.peaknav)

**Desktop** — [the latest release](https://github.com/Upabjojr/PeakNavApp/releases/latest):

| Platform | File |
| --- | --- |
| Windows 10/11 (64-bit) | `peaknav-<version>-windows-x64-setup.exe` |
| macOS, Apple silicon | `peaknav-<version>-macos-aarch64.dmg` |
| macOS, Intel | `peaknav-<version>-macos-x86_64.dmg` |
| Debian / Ubuntu | `peaknav_<version>_amd64.deb` |
| Any other Linux | `peaknav-<version>-x86_64.AppImage` |
| Anything else, or you already have Java | `peaknav-<version>.jar` |

Every desktop download bundles its own Java runtime, so **no Java installation is
required**. The `.jar` is the exception — it needs Java 17 or later
(`java -jar peaknav-<version>.jar`) — and it is also the only build that covers ARM
and 32-bit machines, which have no installer of their own.

The desktop builds are not code-signed yet, so the first launch needs one extra step:
on Windows, SmartScreen shows "Windows protected your PC" — click *More info*, then
*Run anyway*; on macOS, right-click the app and choose *Open* rather than
double-clicking. Downloaded maps and settings are kept outside the installation
(`%APPDATA%\PeakNav`, `~/Library/Application Support/PeakNav`, or `~/.peaknav`), so
upgrading or reinstalling never costs you your data.

## Python package

PeakNav is also on PyPI, as [`peaknav`](https://pypi.org/project/peaknav/):

```bash
pip install peaknav
```

Three modules, in increasing order of what they need from the machine:

* **`peaknav.terrain`** — the elevation of any coordinate on Earth, in pure Python
  (one dependency: Pillow). It reads the same compressed ASTER dataset the app
  renders — with summit heights corrected against surveyed values — downloading
  tiles per area and caching them locally.

  ```python
  >>> from peaknav.terrain import elevation_at
  >>> elevation_at(45.9417, 7.7480)          # the Breithorn
  4160
  ```

* **`peaknav.headless`** — the real PeakNav renderer running off-screen, driven
  from Python: camera control, view options, rendered frames — for scripted
  snapshots, panoramas and videos. Needs Java 17+ and a display; the renderer jar
  (`peaknav-headless-<version>.jar`, attached to every
  [GitHub release](https://github.com/Upabjojr/PeakNavApp/releases)) is downloaded
  on first use, verified against a pinned digest, and cached — or point
  `$PEAKNAV_HEADLESS_JAR` at your own build. The renderer speaks plain HTTP
  (self-described at `/openapi.json`), so anything that can `curl` can drive it too.

  ```python
  from peaknav.headless import PeakNavHeadless

  with PeakNavHeadless(45.9763, 7.6586) as nav:
      nav.look(bearing_deg=230, pitch_deg=-4)
      nav.set_altitude_asl(3200)
      nav.save_frame("matterhorn.png")
  ```

* **`peaknav.jupyter`** *(experimental)* — an interactive PeakNav view inside a
  Jupyter notebook, with pan/tilt controls, altitude, coordinates and display
  toggles (`pip install "peaknav[jupyter]"`).

The package sources, example notebooks and developer documentation live in
[`peaknav-python/`](./peaknav-python/).

## Datasets

This app works with data of two datasets (currently hosted on HuggingFace repository):

* [global-elevation-aster-slippy-tiles-tar-gz](https://huggingface.co/datasets/PeakNav/global-elevation-aster-slippy-tiles-tar-gz) contains the ASTER elevation dataset with a clever compression algorithm, retiled according to the [slippy tiles](https://wiki.openstreetmap.org/wiki/Slippy_map_tilenames) convention.
* [global-openstreetmap-extraction-slippy-tiles-tar](https://huggingface.co/datasets/PeakNav/global-openstreetmap-extraction-slippy-tiles-tar) contains data extracted from OpenStreetMap, also retiled according to the slippy map convention.

## Gallery

<img src="./assets/snapshots/s_aletchhorn_003.jpg" alt="Aletchhorn 003" width="80%" style="margin-left: 10%;" />
<img src="./assets/snapshots/s_banff_002.jpg" alt="Banff 002" width="80%" style="margin-left: 10%;" />
<img src="./assets/snapshots/s_banff_lake_003.jpg" alt="Banff Lake 003" width="80%" style="margin-left: 10%;" />
<img src="./assets/snapshots/s_brenta_003.jpg" alt="Brenta 003" width="80%" style="margin-left: 10%;" />
<img src="./assets/snapshots/s_care_alto_001.jpg" alt="Care Alto 001" width="80%" style="margin-left: 10%;" />
<img src="./assets/snapshots/s_juneau_glacier_001.jpg" alt="Juneau Glacier 001" width="80%" style="margin-left: 10%;" />
<img src="./assets/snapshots/s_monte_bianco_001.jpg" alt="Monte Bianco 001" width="80%" style="margin-left: 10%;" />
<img src="./assets/snapshots/s_monte_rosa_001.jpg" alt="Monte Rosa 001" width="80%" style="margin-left: 10%;" />
<img src="./assets/snapshots/s_monte_rosa_002.jpg" alt="Monte Rosa 002" width="80%" style="margin-left: 10%;" />
<img src="./assets/snapshots/s_new_zealand_003.jpg" alt="New Zealand 003" width="80%" style="margin-left: 10%;" />
<img src="./assets/snapshots/s_new_zealand_005.jpg" alt="New Zealand 005" width="80%" style="margin-left: 10%;" />
<img src="./assets/snapshots/s_valle_daosta.jpg" alt="Valle d'Aosta" width="80%" style="margin-left: 10%;" />

## Build

To build the project, follow these steps:

* Download the [Liberation Fonts](https://github.com/liberationfonts/liberation-fonts) .ttf files and extract them into the `./assets/liberation_fonts/` folder.
* Convert all .svg files located in `./assets_nonshared/icons/` to .png format, and place the resulting files in the `./assets/icons/` folder.
* Build the Apache Lucene search index for geographical names into `./assets/geonames_index.362/`.
  Download `cities500.txt` and `alternateNamesV2.txt` from
  [GeoNames](https://download.geonames.org/export/dump/), then:

  ```bash
  ./gradlew :core:buildGeonamesIndex \
      --args="cities500.txt alternateNamesV2.txt assets/geonames_index.362"
  ```

  * Lucene is pinned to 3.6.2, the last version compatible with Android — don't bump it.
  * The index carries the string fields _name_, _asciiname_, _lat\_store_, _lon\_store_ and
    _population\_store_. Each place is one document whose _name_ field holds every name it can
    be searched by — the local form, a selection of translations, and accent-free spellings —
    so "Venezia" and "Venice" both find the same city and the stored data is paid for once.
  * The builder lives in its own source set, `core/src/tools/java`, so none of it ships in the
    app.
  * Mountain peaks can then be added from OpenStreetMap extracts ([Geofabrik](https://download.geofabrik.de/)
    `.osm.pbf` files), without rebuilding from the GeoNames dumps:

    ```bash
    pip install osmium
    python3 tools/extract_osm_peaks.py peaks.tsv /path/to/pbf_dir
    ./gradlew :core:addPeaksToIndex --args="peaks.tsv assets/geonames_index.362"
    ```

    A third argument sets a minimum elevation; peaks with a Wikipedia article are kept
    regardless of it. Search results show peaks with their elevation — "Matterhorn (4478 m)" —
    and rank them below any city sharing their name.
* Build the project with Gradle — this process is straightforward when using Android Studio, and supports both Android and Desktop builds.

### Headless renderer

The [`headless/`](./headless/) module builds the app as a library that draws off-screen,
for producing views from code instead of from a person at a keyboard — screenshots,
panoramas, videos, documentation images:

```bash
./gradlew :headless:renderJar    # standalone fat jar, no Gradle needed to run it
java -jar headless/build/libs/peaknav-headless-<version>.jar \
    --lat 46.0207 --lon 7.7491 --bearing 210 --out matterhorn.png
```

The same jar is attached to every [GitHub release](https://github.com/Upabjojr/PeakNavApp/releases)
as `peaknav-headless-<version>.jar`, so rendering from a script does not require building
the project. It still needs a display connection (the window is created hidden, but GL
needs one), and `--serve [port]` starts a REST server describing itself at
`/openapi.json`, so it can be driven from Python — or anything that speaks HTTP. Given
together with `--frame`, the server runs alongside the frame loop instead of replacing
it, so a script can watch a video render — `GET /objects` lists the peaks, huts, places
and area labels the renderer has loaded and which of them are on the current frame
(`snapshots/generate_videos.py --probe` does exactly that). `--gpx <file>` draws a GPX
track on the terrain and `--fov` sets the lens. The
`peaknav` Python package, described in the [Python package](#python-package) section
above, is exactly such a client; see [`headless/README.md`](./headless/README.md) for
the Java API and implementation notes.

### Photo skyline matching

When a photograph is placed behind the terrain (gallery or camera buttons) and the app
knows where it was taken, it tries to work out which way the camera pointed: the skyline
traced in the picture is matched against the terrain's horizon around that spot, and if
the match is unambiguous the app offers to turn its camera to the same bearing, pitch and
field of view, so the mountains line up with the photo. Classical image processing and
plain optimisation, no neural network; the code is `com.peaknav.skyline` in `core`.

How well it works is measured on photographs with a known camera heading, which
`tools/skyline_dataset.py` gathers - from [GeoPose3K](https://cphoto.fit.vutbr.cz/geoPose3K/)
(exact poses, a 38 GB download you point the script at) or from Wikimedia Commons photos
whose location template carries a `heading:` (downloaded on the spot, with their licences
recorded) - and the `skylineBenchmark` task reports:

```bash
python3 tools/skyline_dataset.py commons --category "Mountains of Switzerland" --limit 60
./gradlew :core:skylineBenchmark --args="~/.peaknav/skyline_dataset/commons/manifest.json"
```

On GeoPose3K's hand-annotated photos the bearing comes out within 10 degrees for about
half of them, and when the matcher calls a match confident - the only case in which the
app asks - it is right 95% of the time. The elevation tiles of the photographed areas
must be on disk (the app's own `~/.peaknav` cache, or the Python package's).

### Desktop installers

```bash
./gradlew :desktop:installers
```

Produces all of the downloads listed above into `desktop/build/installers/`: the Windows
setup executable, both macOS disk images, the Debian package and the AppImage. They are
built with construo (application image plus a bundled JRE 17) wrapped by NSIS,
genisoimage, dpkg-deb and mksquashfs — all of which cross-compile, so every artefact can
be produced from a single machine whichever OS it runs. Needs `nsis`, `genisoimage` and
`squashfs-tools` installed; see [`desktop/packaging/README.md`](./desktop/packaging/README.md)
for details and the signing caveats.

## License

The source code and assets are licensed under the
[GNU General Public License, version 3](https://www.gnu.org/licenses/gpl-3.0.html) —
Copyright © Francesco Bonazzi. See [LICENSE](./LICENSE).

Three things are **not** covered by it: the name **PeakNav**, the PeakNav logo, and the
application launcher icon — see [TRADEMARK-AND-ASSETS.md](./TRADEMARK-AND-ASSETS.md).
Everything else, the interface icons and the rendered images included, is under the GPL. You
may fork and publish the code freely under your own name and launcher icon.

The map data, terrain, imagery and third-party libraries it builds on carry their own
licenses and attribution requirements, listed in
[THIRD_PARTY_LICENSES.md](./THIRD_PARTY_LICENSES.md) and reproduced on the app's own
License page.
