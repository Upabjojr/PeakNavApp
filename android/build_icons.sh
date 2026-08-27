#!/usr/bin/env bash
# Regenerates the Android launcher icon PNGs from the SVG master in
# assets_nonshared/icons, the same master the in-app icons and the iOS app icon are
# rendered from. Nothing here is committed: *.png is gitignored repository-wide, so a
# fresh clone has res/mipmap-anydpi-v26/ic_launcher.xml pointing at a
# mipmap/ic_launcher_foreground that does not exist, and aapt fails resource linking
# for every build type. Run this before building the Android target.
set -euo pipefail
cd "$(dirname "$0")"

SRC="../assets_nonshared/icons/ic_launcher.svg"

# The plate the emblem sits on - the same value as res/values/ic_launcher_background.xml
# and the iOS icon background, so the app reads the same everywhere. Only the legacy
# icon is flattened onto it; the adaptive foreground stays transparent because the
# adaptive-icon XML supplies that color as its own background layer.
BACKGROUND="#CFC7BC"

# density:pixels for the legacy full-bleed icon (@mipmap/ic_launcher, used by the
# release manifest on pre-8.0 launchers) - 48dp at each density bucket.
LEGACY=(
  "mdpi:48"
  "hdpi:72"
  "xhdpi:96"
  "xxhdpi:144"
  "xxxhdpi:192"
)

# density:canvas for the adaptive-icon foreground layer - a 108dp canvas at each
# density. Launchers mask away everything outside the middle ~66dp, so the emblem is
# rendered at 2/3 of the canvas and centred to sit inside that safe zone.
FOREGROUND=(
  "mdpi:108"
  "hdpi:162"
  "xhdpi:216"
  "xxhdpi:324"
  "xxxhdpi:432"
)

command -v rsvg-convert >/dev/null || {
  echo "rsvg-convert not found - install it with 'brew install librsvg'" >&2
  exit 1
}

for entry in "${LEGACY[@]}"; do
  IFS=':' read -r density px <<< "$entry"
  mkdir -p "res/mipmap-$density"
  rsvg-convert -w "$px" -h "$px" -b "$BACKGROUND" "$SRC" -o "res/mipmap-$density/ic_launcher.png"
  echo "  mipmap-$density/ic_launcher.png ${px}x${px}"
done

for entry in "${FOREGROUND[@]}"; do
  IFS=':' read -r density canvas <<< "$entry"
  emblem=$(( canvas * 2 / 3 ))
  inset=$(( (canvas - emblem) / 2 ))
  mkdir -p "res/mipmap-$density"
  rsvg-convert -w "$emblem" -h "$emblem" \
    --page-width "$canvas" --page-height "$canvas" \
    --top "$inset" --left "$inset" \
    "$SRC" -o "res/mipmap-$density/ic_launcher_foreground.png"
  echo "  mipmap-$density/ic_launcher_foreground.png ${canvas}x${canvas}"
done

echo "done."
