#!/usr/bin/env python3
"""The logo options side by side (r71): each at 256 px, then at 64, 32 and 16 px on a
dark strip and a light one — the sizes the board's chip and the window icon draw them at.

    python3 tools/logo_sheet.py [out.png]      needs ImageMagick (librsvg) and Pillow
"""
import pathlib, subprocess, sys, tempfile
from PIL import Image, ImageDraw, ImageFont

LOGO = pathlib.Path(__file__).resolve().parent.parent / "docs" / "logo"
OPTIONS = [("a-canot-lune", "A  Le canot sous la lune"),
           ("b-hache-aviron", "B  La hache et l'aviron"),
           ("c-lune-canot", "C  La lune-canot")]
FONT = "/usr/share/fonts/liberation-sans-fonts/LiberationSans-Bold.ttf"


def raster(svg, size, tmp):
    out = pathlib.Path(tmp) / ("%s-%d.png" % (svg.stem, size))
    subprocess.run(["magick", "-background", "none", str(svg), "-resize", "%dx%d" % (size, size),
                    str(out)], check=True)
    return Image.open(out)


def main(out):
    im = Image.new("RGB", (3 * 300 + 40, 520), "#1b1d24")
    d = ImageDraw.Draw(im)
    big, small = ImageFont.truetype(FONT, 20), ImageFont.truetype(FONT, 12)
    with tempfile.TemporaryDirectory() as tmp:
        for i, (name, title) in enumerate(OPTIONS):
            svg, x = LOGO / (name + ".svg"), 20 + i * 300
            d.text((x + 22, 14), title, fill="#e8eaf2", font=big)
            ic = raster(svg, 256, tmp)
            im.paste(ic, (x + 22, 48), ic)
            for row, bg in enumerate(("#1b1d24", "#f4f4f6")):
                y = 330 + row * 90
                d.rectangle([x + 10, y - 8, x + 290, y + 76], fill=bg)
                xx = x + 30
                for s in (64, 32, 16):
                    ic = raster(svg, s, tmp)
                    im.paste(ic, (xx, y + (64 - s) // 2), ic)
                    xx += s + 34
                d.text((xx - 6, y + 26), "64/32/16", fill="#888", font=small)
    im.save(out)
    print(out)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "logo-options.png")
