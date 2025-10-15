# PeakNavApp

[PeakNav](https://peaknav.com) is an app to view world mountains in 3D.

Explore the mountains, see paths and ways projected onto their 3D shapes, as well as the names of the nearby peaks!

Currently available both as mobile app for Android and Desktop app.

<a href="https://play.google.com/store/apps/details?id=com.peaknav">
  <img class="imgPlay" alt="Get it on Google Play" src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" style="width: 25%;">
</a>

<iframe width="560" height="315" src="https://www.youtube.com/embed/y4WspQmcwQw?si=OhTLQxpW3ncb3jTb?autoplay=0" title="YouTube video player" frameborder="0" allow="accelerometer; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" referrerpolicy="strict-origin-when-cross-origin" allowfullscreen=""></iframe>

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
* Create an Apache Lucene search index for geographical names and store it in the `./assets/geonames_index.362/` folder.
  * Use Apache Lucene version 3.6.2 (the last version compatible with Android).
  * Include the following string fields in the index: _name_, _asciiname_, _lat\_store_, _lon\_store_, and _population\_store_.
  * Refer to `./core/src/test/java/TestLuceneGeonames.java` for an example of how to generate the index.
* Build the project with Gradle — this process is straightforward when using Android Studio, and supports both Android and Desktop builds.
