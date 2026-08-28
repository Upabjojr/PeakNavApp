#!/usr/bin/env bash
# Regenerates the iOS app icon and launch-screen artwork from the PeakNav logo masters in
# assets_nonshared/icons - the same artwork peaknav.com and the Play Store listing use:
#   peaknav_logo_flat.png  512px, opaque  - the engraved compass badge on its paper plate
#   peaknav_logo.png       256px, alpha   - the badge alone, transparent outside the circle
# Nothing here is committed: *.png is gitignored repository-wide, so a fresh clone has an
# asset catalogue full of filenames pointing at files that do not exist, and actool has
# nothing to compile. Run this before building the iOS target.
#
# The sizes are not a free choice - each one is a filename referenced by
# ios/data/Media.xcassets/AppIcon.appiconset/Contents.json, and a name in Contents.json with
# no file behind it is what produces an app that installs with a blank icon.
set -euo pipefail
cd "$(dirname "$0")"

# The opaque master feeds every app icon: iOS app icons may not carry an alpha channel at
# all - App Store validation rejects them, and a transparent icon renders black on the home
# screen - which is why the flattened 512px rendition is the source, not the transparent one.
# Only the 1024px marketing icon is an upscale; every size a device actually displays is a
# downscale. Rebuild that one from larger artwork before an App Store submission.
SRC_ICON="../assets_nonshared/icons/peaknav_logo_flat.png"
# The launch screen sits the emblem on the storyboard's own background, so it keeps its
# transparency and comes from the alpha-carrying master.
SRC_LOGO="../assets_nonshared/icons/peaknav_logo.png"

ICONSET="data/Media.xcassets/AppIcon.appiconset"
LOGOSET="data/Media.xcassets/Logo.imageset"

# filename:pixels - the rendered size, not the point size in Contents.json.
ICONS=(
  "iphone-notification-icon-20@2x:40"
  "iphone-notification-icon-20@3x:60"
  "iphone-spotlight-settings-icon-29@2x:58"
  "iphone-spotlight-settings-icon-29@3x:87"
  "iphone-spotlight-icon-40@2x:80"
  "iphone-spotlight-icon-40@3x:120"
  "iphone-app-icon-60@2x:120"
  "iphone-app-icon-60@3x:180"
  "ipad-notifications-icon-20@1x:20"
  "ipad-notifications-icon-20@2x:40"
  "ipad-settings-icon-29@1x:29"
  "ipad-settings-icon-29@2x:58"
  "ipad-spotlight-icon-40@1x:40"
  "ipad-spotlight-icon-40@2x:80"
  "ipad-app-icon-76@1x:76"
  "ipad-app-icon-76@2x:152"
  "ipad-pro-app-icon-83.5@2x:167"
  "app-store-icon-1024@1x:1024"
)

LOGOS=(
  "peaknav@1x:128"
  "peaknav@2x:256"
  "peaknav@3x:384"
)

for entry in "${ICONS[@]}"; do
  IFS=':' read -r name px <<< "$entry"
  sips -z "$px" "$px" "$SRC_ICON" --out "$ICONSET/$name.png" >/dev/null
  echo "  $name.png ${px}x${px}"
done

# The 1024 App Store marketing icon is the one size LARGER than the 512 source art, so a
# plain resize only magnifies its softness. This dedicated master was produced from the
# 512 master by a CoreImage Lanczos upscale plus a light unsharp mask (opaque, no alpha),
# which is as crisp as a 512 source allows - regenerate it from higher-resolution artwork
# if that ever exists. Every device-displayed size above is a downscale and stays a plain
# resize of the 512 master.
cp "../assets_nonshared/icons/peaknav_logo_1024.png" "$ICONSET/app-store-icon-1024@1x.png"
echo "  app-store-icon-1024@1x.png 1024x1024 (Lanczos master)"

for entry in "${LOGOS[@]}"; do
  IFS=':' read -r name px <<< "$entry"
  sips -z "$px" "$px" "$SRC_LOGO" --out "$LOGOSET/$name.png" >/dev/null
  echo "  $name.png ${px}x${px}"
done

echo "done."
