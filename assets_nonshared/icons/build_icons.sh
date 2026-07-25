#!/usr/bin/env bash
# Regenerates icon PNGs in assets/icons from the SVG masters in assets_nonshared/icons.
# Each entry is "name WxH" so non-square / non-128 icons keep the size the app expects.
set -euo pipefail
cd "$(dirname "$0")"
SRC="."
OUT="../../assets/icons"

# name:width:height  (only the icons we (re)generate from SVG here)
ICONS=(
  "icon_back:128:128"
  "icon_x:128:128"
  "icon_options:128:128"
  "icon_options_checked:128:128"
  "icon_search:128:128"
  "icon_checkbox_large_fonts:128:128"
  "icon_camera:128:128"
  "icon_gallery:128:128"
  "icon_change_user:128:128"
  "icon_help:128:128"
  "icon_share:128:128"
  "icon_compass:120:120"
  "icon_checkbox_satellite:128:128"
  "icon_checkbox_roads:128:128"
  "icon_checkbox_place_names:128:128"
  "icon_checkbox_alpine_huts:145:134"
  "icon_checkbox_peak_names:128:128"
  "icon_checkbox_islands:128:128"
  "icon_checkbox_mountain_ranges:128:128"
  "icon_checkbox_large_towns:128:128"
  "icon_checkbox_download_data:128:128"
  "icon_checkbox_download_data2:128:128"
  "icon_checkbox_sun:256:256"
  "icon_gyro:128:128"
  "icon_gyro_pressed:128:128"
  "icon_map:256:256"
  "icon_elevation_button:170:256"
  "icon_here:128:128"
  "icon_here_gps:128:128"
  "icon_go_to_dest:128:128"
  "icon_loc_pin:128:256"
  "ic_launcher:192:192"
)

for entry in "${ICONS[@]}"; do
  IFS=':' read -r name w h <<< "$entry"
  svg="$SRC/$name.svg"
  if [[ -f "$svg" ]]; then
    rsvg-convert -w "$w" -h "$h" "$svg" -o "$OUT/$name.png"
    echo "  $name.png ${w}x${h}"
  fi
done
echo "done."
