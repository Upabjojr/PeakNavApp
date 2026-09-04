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
  "icon_compass_horizon:128:128"
  "icon_compass_corner:128:128"
  "icon_compass_location:128:128"
  "icon_checkbox_satellite:128:128"
  "icon_checkbox_roads:128:128"
  "icon_checkbox_place_names:128:128"
  "icon_checkbox_alpine_huts:145:134"
  "icon_checkbox_peak_names:128:128"
  "icon_checkbox_islands:128:128"
  "icon_checkbox_mountain_ranges:128:128"
  "icon_checkbox_lakes:128:128"
  "icon_gpx_play:128:128"
  "icon_gpx_pause:128:128"
  "icon_gpx_clear:128:128"
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
  "icon_orbit:128:128"
  "icon_match_photo:128:128"
  "icon_save_sample:128:128"
  "icon_unpin:128:128"
  "icon_open_coordinate:128:128"
  "icon_sky_grid:128:128"
  "icon_sky_ecliptic:128:128"
  "icon_sky_labels:128:128"
  "icon_loc_pin:128:256"
  # These eight are loaded by the app exactly like the rest but were missing from this
  # list, so a build from a clean checkout came up short of the icons it asks for.
  "icon_checkbox_sky:128:128"
  "icon_info:128:128"
  "icon_sky_constellations:128:128"
  "icon_sky_mode:128:128"
  "icon_sky_time:128:128"
  "icon_slider_alpha:128:128"
  "icon_units:128:128"
  # 30x50 is not a free choice: WidgetTextures.getNinePatchDrawable builds this one with
  # hard-coded 10px splits on all four sides, which land on the bar in the artwork only at
  # the SVG's own size. Rendering it larger stretches the wrong pixels.
  "slider_nine_patch:30:50"
)

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
