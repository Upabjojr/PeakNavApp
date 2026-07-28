#!/usr/bin/env python3
"""Generates the desktop launcher icons construo needs, from the app medallion.

construo wants a PNG for the Windows executable and an ICNS for the macOS .app;
NSIS wants an ICO for the installer itself. All three come from the same 512px
circular medallion the Android launcher icon was cut from, so the app looks the
same on every platform.

The medallion is inset slightly rather than bleeding to the canvas edge: macOS
sizes Dock icons on the assumption that artwork sits inside a margin, so a
full-bleed circle reads as visibly larger than its neighbours.
"""

import struct
from io import BytesIO
from pathlib import Path

from PIL import Image

#: Repository root: this file lives at <root>/desktop/packaging/make_icons.py.
ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "assets/icons/ic_launcher.png"
OUT_DIR = ROOT / "desktop/icons"

#: Share of the canvas the medallion occupies; the rest is transparent margin.
CONTENT_SCALE = 0.92

#: ICNS chunk types that take a PNG payload, mapped to their pixel size.
#: (ic11/ic12 are the 16pt/32pt retina slots, ic10 is 512pt@2x.)
ICNS_TYPES = {
    "ic11": 32, "ic12": 64, "ic07": 128, "ic13": 256,
    "ic08": 256, "ic14": 512, "ic09": 512, "ic10": 1024,
}

ICO_SIZES = [16, 24, 32, 48, 64, 128, 256]

#: Sizes installed into the freedesktop hicolor theme by the .deb. Pre-rendered
#: rather than downscaled at install time so the small sizes, where the medallion's
#: ring is a couple of pixels wide, are resampled from the 512px original once.
LINUX_ICON_SIZES = [16, 24, 32, 48, 64, 128, 256, 512]


def render(size):
    """The medallion on a transparent square canvas of the given size."""
    inner = max(1, int(round(size * CONTENT_SCALE)))
    # Resize from the 512px original in one step: repeated resizes soften the
    # ring, and LANCZOS straight from the source keeps the tick marks crisp.
    medallion = Image.open(SOURCE).convert("RGBA").resize((inner, inner), Image.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    offset = (size - inner) // 2
    canvas.paste(medallion, (offset, offset), medallion)
    return canvas


def write_icns(path):
    """ICNS is a flat container: 'icns', total length, then typed chunks.

    Pillow only saves ICNS on macOS (it shells out to iconutil), so the eight
    bytes of header per chunk are written by hand here instead.
    """
    chunks = b""
    for ostype, size in ICNS_TYPES.items():
        buffer = BytesIO()
        render(size).save(buffer, format="PNG")
        payload = buffer.getvalue()
        chunks += ostype.encode("ascii") + struct.pack(">I", len(payload) + 8) + payload
    path.write_bytes(b"icns" + struct.pack(">I", len(chunks) + 8) + chunks)


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    render(512).save(OUT_DIR / "logo.png")
    write_icns(OUT_DIR / "logo.icns")
    # Pillow writes a real multi-resolution ICO, so Windows picks the right size
    # for the desktop, the taskbar and Alt-Tab rather than rescaling one.
    render(256).save(OUT_DIR / "logo.ico",
                     sizes=[(s, s) for s in ICO_SIZES])

    linux_dir = OUT_DIR / "linux"
    linux_dir.mkdir(exist_ok=True)
    for size in LINUX_ICON_SIZES:
        render(size).save(linux_dir / f"{size}.png")

    for name in ("logo.png", "logo.icns", "logo.ico"):
        print(f"{name}: {(OUT_DIR / name).stat().st_size:,} bytes")
    print(f"linux/: {len(LINUX_ICON_SIZES)} sizes "
          f"({', '.join(str(s) for s in LINUX_ICON_SIZES)})")


if __name__ == "__main__":
    main()
