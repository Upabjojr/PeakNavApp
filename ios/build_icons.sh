#!/usr/bin/env bash
# Regenerates the iOS app icon and launch-screen artwork from the SVG master in
# assets_nonshared/icons, the same master assets_nonshared/icons/build_icons.sh renders the
# in-app icons from. Nothing here is committed: *.png is gitignored repository-wide, so a
# fresh clone has an asset catalogue full of filenames pointing at files that do not exist,
# and actool has nothing to compile. Run this before building the iOS target.
#
# The sizes are not a free choice - each one is a filename referenced by
# ios/data/Media.xcassets/AppIcon.appiconset/Contents.json, and a name in Contents.json with
# no file behind it is what produces an app that installs with a blank icon.
set -euo pipefail
cd "$(dirname "$0")"

SRC="../assets_nonshared/icons/ic_launcher.svg"
ICONSET="data/Media.xcassets/AppIcon.appiconset"
LOGOSET="data/Media.xcassets/Logo.imageset"

# The plate the emblem sits on. Shared with Android's ic_launcher_background so the app
# reads the same on both platforms - and, more to the point, iOS app icons may not carry an
# alpha channel at all: App Store validation rejects them, and a transparent icon renders
# black on the home screen. rsvg-convert -b composites onto this and drops the alpha, which
# is why there is no separate flattening step here.
BACKGROUND="#CFC7BC"

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

# The launch screen emblem. Alpha is fine here - it sits on the storyboard's own background,
# so it is rendered without -b and keeps its transparency.
LOGOS=(
  "peaknav@1x:128"
  "peaknav@2x:256"
  "peaknav@3x:384"
)

command -v rsvg-convert >/dev/null || {
  echo "rsvg-convert not found - install it with 'brew install librsvg'" >&2
  exit 1
}

for entry in "${ICONS[@]}"; do
  IFS=':' read -r name px <<< "$entry"
  rsvg-convert -w "$px" -h "$px" -b "$BACKGROUND" "$SRC" -o "$ICONSET/$name.png"
  echo "  $name.png ${px}x${px}"
done

for entry in "${LOGOS[@]}"; do
  IFS=':' read -r name px <<< "$entry"
  rsvg-convert -w "$px" -h "$px" "$SRC" -o "$LOGOSET/$name.png"
  echo "  $name.png ${px}x${px}"
done

echo "done."
