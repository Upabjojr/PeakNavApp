#!/usr/bin/env bash
# Regenerates icon PNGs in assets/icons from the SVG masters in assets_nonshared/icons.
# Each entry is "name WxH" so non-square / non-128 icons keep the size the app expects.
set -euo pipefail
cd "$(dirname "$0")"
SRC="."
OUT="../../assets/icons"

# name:width:height, from icons.txt (shared with the :core:generateIcons Gradle task).
ICONS=()
while IFS= read -r line; do
  case "$line" in ''|'#'*) continue ;; esac
  ICONS+=("$line")
done < "$SRC/icons.txt"

for entry in "${ICONS[@]}"; do
  IFS=':' read -r name w h <<< "$entry"
  svg="$SRC/$name.svg"
  if [[ -f "$svg" ]]; then
    rsvg-convert -w "$w" -h "$h" "$svg" -o "$OUT/$name.png"
    echo "  $name.png ${w}x${h}"
  fi
done

# The in-app launcher emblem (IntroScreen) is not drawn from an SVG here: it is the
# PeakNav logo itself, the same artwork peaknav.com serves, kept as a PNG master with
# its transparency so it sits on the intro screen's own background.
sips -z 192 192 "$SRC/peaknav_logo.png" --out "$OUT/ic_launcher.png" >/dev/null
echo "  ic_launcher.png 192x192"
echo "done."
