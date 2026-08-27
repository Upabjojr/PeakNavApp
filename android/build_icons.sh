#!/usr/bin/env bash
# Regenerates the Android launcher icon PNGs from the PeakNav logo masters in
# assets_nonshared/icons - the same artwork peaknav.com, the Play Store listing, the iOS
# icons and the in-app emblem are produced from:
#   peaknav_logo_flat.png  512px, opaque  - the engraved compass badge on its paper plate
#   peaknav_logo.png       256px, alpha   - the badge alone, transparent outside the circle
# Nothing here is committed: *.png is gitignored repository-wide, so a fresh clone has
# res/mipmap-anydpi-v26/ic_launcher.xml pointing at a mipmap/ic_launcher_foreground that
# does not exist, and aapt fails resource linking for every build type. Run this before
# building the Android target.
set -euo pipefail
cd "$(dirname "$0")"

SRC_FLAT="../assets_nonshared/icons/peaknav_logo_flat.png"
SRC_ALPHA_DIR="../assets_nonshared/icons"

# density:pixels for the legacy full-bleed icon (@mipmap/ic_launcher, used by the
# release manifest on pre-8.0 launchers) - 48dp at each density bucket. The flat master
# already carries the paper plate, so it is a straight resize.
LEGACY=(
  "mdpi:48"
  "hdpi:72"
  "xhdpi:96"
  "xxhdpi:144"
  "xxxhdpi:192"
)

# density:canvas for the adaptive-icon foreground layer - a 108dp canvas at each
# density. Launchers mask away everything outside the middle ~66dp, so the emblem is
# rendered at 2/3 of the canvas and centred to sit inside that safe zone. The padding
# must stay transparent (the adaptive-icon XML supplies the plate colour as its own
# background layer), which sips cannot do - so the transparent master goes through
# rsvg-convert wrapped in a minimal SVG, whose page options pad without flattening.
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
  sips -z "$px" "$px" "$SRC_FLAT" --out "res/mipmap-$density/ic_launcher.png" >/dev/null
  echo "  mipmap-$density/ic_launcher.png ${px}x${px}"
done

# librsvg only loads referenced resources from the SVG's own directory, so the wrapper
# must sit next to the master it embeds - a temp file elsewhere renders an empty canvas.
WRAPPER="$SRC_ALPHA_DIR/.ic_launcher_foreground_wrapper.svg"
trap 'rm -f "$WRAPPER"' EXIT
cat > "$WRAPPER" <<'SVG'
<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink"
     width="256" height="256">
  <image xlink:href="peaknav_logo.png" width="256" height="256"/>
</svg>
SVG

for entry in "${FOREGROUND[@]}"; do
  IFS=':' read -r density canvas <<< "$entry"
  emblem=$(( canvas * 2 / 3 ))
  inset=$(( (canvas - emblem) / 2 ))
  mkdir -p "res/mipmap-$density"
  rsvg-convert -w "$emblem" -h "$emblem" \
    --page-width "$canvas" --page-height "$canvas" \
    --top "$inset" --left "$inset" \
    "$WRAPPER" -o "res/mipmap-$density/ic_launcher_foreground.png"
  echo "  mipmap-$density/ic_launcher_foreground.png ${canvas}x${canvas}"
done

echo "done."
