# Licenses of Linked External Projects

This application includes links to external applications and makes use of external data providers, each of which is governed by its own licensing terms.

### Data Source Providers

* Map data, including vector and point objects, are © the
  [OpenStreetMap contributors](https://www.openstreetmap.org/copyright), available under the
  [Open Database License (ODbL)](https://opendatacommons.org/licenses/odbl/).
* Terrain elevation is derived from **ASTER GDEM**, a product of **METI and NASA**,
  distributed by NASA's Land Processes Distributed Active Archive Center at the
  [U.S. Geological Survey](https://usgs.gov/) EROS Center.
* We acknowledge the use of imagery provided by services from NASA's Global Imagery Browse Services (GIBS), part of NASA's Earth Observing System Data and Information System (EOSDIS).
* GeoNames geographical database provided by
  [GeoNames](https://www.geonames.org/about.html), available under
  [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
* Named areas — mountain ranges, islands, lakes and towns, with their coordinates, extents
  and summit elevations — are derived from [Wikidata](https://www.wikidata.org), available
  under the [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) public domain
  dedication.
* Built-up extents of towns are derived from the
  [GHS Urban Centre Database](https://human-settlement.emergency.copernicus.eu/) of the
  European Commission Joint Research Centre, available under
  [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
* Star positions are taken from the **Yale Bright Star Catalogue, 5th Revised Edition**
  (Hoffleit & Warren, 1991), which is in the public domain.
* Constellation figures and names are derived from **d3-celestial** by Olaf Frohn
  ([github.com/ofrohn/d3-celestial](https://github.com/ofrohn/d3-celestial)), used under the
  BSD 2-Clause License (full text reproduced below).

### Open Source Software Acknowledgments:

This application incorporates the following open-source software components, each distributed under its respective license:

* **Libgdx** is licensed under the [Apache License 2.0](https://github.com/libgdx/libgdx/blob/master/LICENSE).
* **Mapsforge** library is under [LGPL v3 license](http://www.gnu.org/licenses/lgpl-3.0).
* **OSM-binary** is licensed under [LGPL v3 license](https://github.com/openstreetmap/OSM-binary/blob/master/LICENSE).
* **osmdroid** is licensed under [Apache License 2.0](https://github.com/osmdroid/osmdroid/blob/master/LICENSE).
* **PNGJ** is licensed under [Apache License](https://github.com/alexdupre/pngj/blob/master/README.md).
* **google/gson** is licensed under the
    [Apache License 2.0](https://github.com/google/gson/blob/main/LICENSE).
* **Guava** is licensed under the
    [Apache License 2.0](https://github.com/google/guava/blob/master/LICENSE).
* **svgSalamander** is licensed under the
    [BSD license](https://github.com/blackears/svgSalamander/blob/master/licenses/license-BSD.txt).
* **AndroidSVG** is licensed under the
    [Apache License v2.0](https://bigbadaboom.github.io/androidsvg/).
* **Transliteration** is provided by [International Components for Unicode](https://github.com/unicode-org/icu/blob/main/LICENSE).
* **Apache Lucene** is licensed under the [Apache License v2.0](https://github.com/apache/lucene?tab=Apache-2.0-1-ov-file#readme)
* **Liberation Fonts** are licensed under their own [license](https://github.com/liberationfonts/liberation-fonts/blob/main/LICENSE)
* **Sun, Moon and planet positions** are computed using the public-domain algorithms of
  Paul Schlyter, "How to compute planetary positions"
  ([stjarnhimlen.se/comp/ppcomp.html](http://stjarnhimlen.se/comp/ppcomp.html)).
* **LWJGL 3** (the desktop backend) is licensed under the
  [BSD 3-Clause License](https://github.com/LWJGL/lwjgl3/blob/master/LICENSE.md), and ships
  the native libraries it binds: **GLFW** ([zlib/libpng](https://www.glfw.org/license.html)),
  **OpenAL Soft** ([LGPL 2.1](https://github.com/kcat/openal-soft/blob/master/COPYING)) and
  **stb** ([public domain / MIT](https://github.com/nothings/stb/blob/master/LICENSE)).
* **FreeType** is used for font rasterization, under the
  [FreeType License](https://gitlab.freedesktop.org/freetype/freetype/-/blob/master/docs/FTL.TXT):
  portions of this software are copyright © The FreeType Project
  ([www.freetype.org](https://www.freetype.org)); all rights reserved.
* **Box2D** by Erin Catto is licensed under the
  [MIT License](https://github.com/erincatto/box2d/blob/main/LICENSE).
* **JOgg** and **JOrbis** by JCraft (OGG/Vorbis decoding) are licensed under
  [LGPL 2.1](https://github.com/jcraft/jorbis/blob/master/LICENSE.txt).
* **SQLite** is in the [public domain](https://www.sqlite.org/copyright.html); the desktop
  build reaches it through **sqlite-jdbc** by Xerial, licensed under the
  [Apache License 2.0](https://github.com/xerial/sqlite-jdbc/blob/master/LICENSE).
* **Apache Commons** Compress, Codec, IO and Lang are licensed under the
  [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).
* **Protocol Buffers** is licensed under the
  [BSD 3-Clause License](https://github.com/protocolbuffers/protobuf/blob/main/LICENSE).
* **kXML 2** and **XmlPull** are licensed under the
  [BSD License](https://github.com/stefanhaustein/kxml2) and placed in the public domain
  respectively.
* **Snowball** stemmers (bundled with Lucene) are licensed under the
  [BSD License](https://snowballstem.org/license.html).
* **Error Prone**, **J2ObjC** and **JSpecify** annotations are licensed under the
  [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).
* **AndroidX** libraries (Android build) are licensed under the
  [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).
* The desktop installers bundle a Java runtime, **Eclipse Temurin (OpenJDK)**, licensed
  under [GPL v2 with the Classpath Exception](https://openjdk.org/legal/gplv2+ce.html); its
  own license and notice files are included inside the package, under `runtime/legal/`.

### d3-celestial License (constellation data)

```
Copyright (c) 2015, Olaf Frohn
All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are
permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of
   conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice, this list of
   conditions and the following disclaimer in the documentation and/or other materials
   provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR
TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```
