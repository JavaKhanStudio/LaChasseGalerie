#!/usr/bin/env python3
"""Every raster of the game's logo, made from the one drawing: icon.svg at the repo root (r71).

Simon picked A, the canoe crossing the full moon above the pines, for the game and its board. The
board reads icon.svg by itself; this writes the rest from it, so an edit to the drawing is one run:

    desktop/assets/ui/icon_{16,32,64,128}.png   the window and taskbar icon (Utils_Launcher)
    desktop/assets/ui/logo.png                  256 px, above the title on the start menu (Vue_Menu)
    desktop/packaging/icon.png, icon.ico        512 px and 16..256 px, for ./gradlew jpackage

    python3 tools/logo_icons.py                 needs ImageMagick (librsvg)
"""
import pathlib, subprocess

ROOT = pathlib.Path(__file__).resolve().parent.parent
SVG = ROOT / "icon.svg"
UI = ROOT / "desktop" / "assets" / "ui"
PACK = ROOT / "desktop" / "packaging"


def raster(size, out):
    # Drawn at the size it is wanted, not shrunk from a big one: librsvg anti-aliases each size itself
    density = 72 * size / 128
    subprocess.run(["magick", "-background", "none", "-density", "%g" % density, str(SVG),
                    "-resize", "%dx%d" % (size, size), "-strip", "PNG32:%s" % out], check=True)
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


if __name__ == "__main__":
    main()
