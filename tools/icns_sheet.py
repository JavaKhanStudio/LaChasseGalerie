#!/usr/bin/env python3
"""Every image inside desktop/packaging/icon.icns on one sheet, each read back from its own entry (r100).

    python3 tools/icns_sheet.py [out.png]       default /tmp/icns_sheet.png

Reads with IcnsFile.getimage, not Image.open + .size: that one loads the biggest entry and shrinks it,
so a missing or broken 16 px entry would still look fine. Each small one is also drawn at 8x.
"""
import pathlib, sys

from PIL import Image, ImageChops, ImageDraw, IcnsImagePlugin

ROOT = pathlib.Path(__file__).resolve().parent.parent
ICNS = ROOT / "desktop" / "packaging" / "icon.icns"


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "/tmp/icns_sheet.png"
    icns = IcnsImagePlugin.IcnsFile(open(ICNS, "rb"))
    sizes = sorted(icns.itersizes(), key=lambda s: (s[0] * s[2], s[2]))
    tiles = []
    for w, h, scale in sizes:
        im = icns.getimage((w, h, scale)).convert("RGBA")
        ref = ROOT / "desktop" / "assets" / "ui" / ("icon_%d.png" % im.width)
        same = "" if not ref.exists() else " =ui" if ImageChops.difference(
            im, Image.open(ref).convert("RGBA")).getbbox() is None else " !=ui"
        tiles.append(("%dx%d@%dx" % (w, h, scale), "%d px%s" % (im.width, same), im))
        print("%-12s %5d px%s" % (tiles[-1][0], im.width, same))
    small = [t for t in tiles if t[2].width <= 32]
    width = 20 + sum(min(t[2].width, 256) + 20 for t in tiles)
    sheet = Image.new("RGBA", (max(width, 20 + 276 * len(small)), 640), (40, 44, 56, 255))
    draw, x = ImageDraw.Draw(sheet), 20
    for i, (name, px, im) in enumerate(tiles):
        view = im if im.width <= 256 else im.resize((256, 256), Image.LANCZOS)
        sheet.alpha_composite(view, (x, 20))
        # labels alternate rows: the small tiles are narrower than their text
        draw.text((x, 285 + 28 * (i % 2)), name, fill="white")
        draw.text((x, 298 + 28 * (i % 2)), px, fill=(170, 180, 200))
        x += view.width + 20
    x = 20
    for name, px, im in small:
        sheet.alpha_composite(im.resize((im.width * 8, im.height * 8), Image.NEAREST), (x, 350))
        draw.text((x, 350 + im.height * 8 + 6), name + " at 8x", fill="white")
        x += 276
    sheet.save(out)
    print(out)


if __name__ == "__main__":
    main()
