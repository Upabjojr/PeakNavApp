# Trademark and artwork

The **source code and assets** of PeakNav are licensed under the
[GNU General Public License, version 3](./LICENSE). Those rights are unconditional and
nothing in this document takes them away.

The GPL licenses copyright in software. It has never granted rights to a project's name or
logo — GPLv3 says so explicitly (§7e). This document sets out the
little that is *not* covered, so that the position is stated rather than implied.

## Reserved

Copyright © Francesco Bonazzi. All rights reserved. Not licensed under the GPL:

* The name **PeakNav**.
* The **PeakNav logo**.
* The **application launcher icon**: `assets_nonshared/icons/ic_launcher.svg`,
  `assets/icons/ic_launcher.png`, and the launcher resources under `android/res/`
  (`mipmap-*/ic_launcher*`, `drawable/ic_launcher_debug.xml`,
  `values/ic_launcher_background.xml`).

That is the whole list. Everything else in the repository is under the GPL, including the
button and interface icons and the rendered images of the application in `assets/snapshots/`
and `snapshots/images/`.

## What you may do without asking

* Build, run and modify PeakNav, privately or commercially.
* Redistribute and publish the source code and assets, modified or not, under the GPL —
  this repository in full.
* Publish your own application built from this code on any platform, **under your own name
  and with your own launcher icon**. A fork needs one regardless: the application loads it at
  start-up, and Android will not build without it.
* Use, modify and redistribute the interface icons and the rendered images.

## What requires written permission

Using the name **PeakNav**, the PeakNav logo, or the application launcher icon — for a
release of your own, in an app store listing, or as the identity of a derived work.

Ask: peaknav.info@gmail.com — permission is not unreasonably withheld.

## Not covered by this document

The map, terrain and place-name data are **not** the author's to reserve, and this document
makes no claim over them. They derive from OpenStreetMap (ODbL), Wikidata (CC0), the GHS
Urban Centre Database (CC BY 4.0), ASTER GDEM (METI/NASA) and GeoNames (CC BY 4.0), and
remain governed by those licenses — including ODbL's share-alike obligation on derived
databases. Likewise the third-party assets shipped with the application (Liberation Fonts,
the Mapsforge render-theme symbols and patterns, the GeoNames search index) carry their own
licenses, listed in [THIRD_PARTY_LICENSES.md](./THIRD_PARTY_LICENSES.md).
