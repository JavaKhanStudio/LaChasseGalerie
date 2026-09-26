#!/usr/bin/env python3
"""Every raster of the game's logo, made from the one drawing: icon.svg at the repo root (r71).

Simon picked A, the canoe crossing the full moon above the pines, for the game and its board. The
board reads icon.svg by itself; this writes the rest from it, so an edit to the drawing is one run:

    desktop/assets/ui/icon_{16,32,64,128}.png   the window and taskbar icon (Utils_Launcher)
    desktop/assets/ui/logo.png                  256 px, above the title on the start menu (Vue_Menu)
    desktop/packaging/icon.png, icon.ico        512 px and 16..256 px, for ./gradlew jpackage
    desktop/packaging/icon.icns                 16..1024 px, for jpackage on macOS (r100)

    python3 tools/logo_icons.py                 needs ImageMagick (librsvg) and Pillow
"""
import pathlib, struct, subprocess, tempfile

from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parent.parent
SVG = ROOT / "icon.svg"
UI = ROOT / "desktop" / "assets" / "ui"
PACK = ROOT / "desktop" / "packaging"


def raster(size, out, say=True):
    # Drawn at the size it is wanted, not shrunk from a big one: librsvg anti-aliases each size itself
    density = 72 * size / 128
    subprocess.run(["magick", "-background", "none", "-density", "%g" % density, str(SVG),
                    "-resize", "%dx%d" % (size, size), "-strip", "PNG32:%s" % out], check=True)
    if say:
        print(out.relative_to(ROOT))


def main():
    for s in (16, 32, 64, 128):
        raster(s, UI / ("icon_%d.png" % s))
    raster(256, UI / "logo.png")
    raster(512, PACK / "icon.png")
    ico = PACK / "icon.ico"
    subprocess.run(["magick", "-background", "none", "-density", "%g" % (72 * 256 / 128), str(SVG),
                    "-define", "icon:auto-resize=256,128,64,48,32,16", str(ico)], check=True)
    print(ico.relative_to(ROOT))
    icns(PACK / "icon.icns")


# .icns entries: type -> pixels. The PNG ones are what iconutil writes for 32 px and up (@2x included);
# 16 and 32 px at 1x are the classic RGB + 8-bit mask pair, which every macOS reads
ICNS_PNG = (("ic11", 32), ("ic12", 64), ("ic07", 128), ("ic13", 256), ("ic08", 256), ("ic14", 512),
            ("ic09", 512), ("ic10", 1024))
ICNS_RGB = (("is32", "s8mk", 16), ("il32", "l8mk", 32))


def packbits(channel):
    # The icns run-length code, literals only: a byte n < 128 says n + 1 raw bytes follow
    out = bytearray()
    for i in range(0, len(channel), 128):
        chunk = channel[i:i + 128]
        out += bytes([len(chunk) - 1]) + chunk
    return bytes(out)


def icns(out):
    # Written here rather than with iconutil (a Mac) or Pillow (it has no 16 or 32 px at 1x). Every
    # size is its own librsvg render, like the PNGs above, never a shrink of the big one
    entries = []
    with tempfile.TemporaryDirectory() as tmp:
        def render(size):
            png = pathlib.Path(tmp) / ("%d.png" % size)
            if not png.exists():
                raster(size, png, say=False)
            return png
        for rgb, mask, size in ICNS_RGB:
            r, g, b, a = Image.open(render(size)).convert("RGBA").split()
            entries.append((rgb, b"".join(packbits(c.tobytes()) for c in (r, g, b))))
            entries.append((mask, a.tobytes()))
        for kind, size in ICNS_PNG:
            entries.append((kind, render(size).read_bytes()))
    body = b"".join(k.encode() + struct.pack(">I", 8 + len(d)) + d for k, d in entries)
    out.write_bytes(b"icns" + struct.pack(">I", 8 + len(body)) + body)
    print(out.relative_to(ROOT))


if __name__ == "__main__":
    main()
