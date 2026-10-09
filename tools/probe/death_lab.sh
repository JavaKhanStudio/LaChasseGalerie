#!/usr/bin/env bash
# death_lab.sh — r98's renders : the death lab (--lab death) offscreen, every style of a page dying at once.
#   tools/probe/death_lab.sh [--no-build] [--page N]
#     page 1  ->  desktop/build/death_lab/death_sheet.png, death.gif, alive.png
#     page N  ->  desktop/build/death_lab_pN/ the same
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
jar="$root/desktop/build/libs/LaChasseGalerie-1.0.jar"
build=1 page=1
while [ $# -gt 0 ]; do
	case "$1" in
		--no-build) build=0 ;;
		--page) page="$2"; shift ;;
		*) echo "usage: death_lab.sh [--no-build] [--page N]" >&2; exit 2 ;;
	esac
	shift
done
out="$root/desktop/build/death_lab"
[ "$page" = 1 ] || out="${out}_p$page"
[ "$build" = 0 ] || "$root/gradlew" -p "$root" -q dist
rm -rf "$out" && mkdir -p "$out/probe" "$out/gif"
javac -cp "$jar" -d "$out/probe" "$root/tools/probe/Death_Lab_Probe.java"
(cd "$root/desktop/assets" && "$root/tools/offscreen.sh" java --enable-native-access=ALL-UNNAMED -cp "$jar:$out/probe" Death_Lab_Probe "$out" "$page") >"$out/probe.log" 2>&1
# The world band the monsters die in, each frame flipped the right way up ; a soul rises higher, so page 2 shows more sky
band=1280x380+0+110
[ "$page" = 1 ] || band=1280x520+0+0
crop() { magick "$1" -flip -crop "$band" +repage "$2"; }
for f in "$out"/sheet_*.png; do crop "$f" "${f%.png}_c.png"; done
magick "$out"/sheet_*_c.png -append -resize 70% "$out/death_sheet.png"
magick "$out/alive.png" -flip -resize 50% "$out/alive_small.png"
magick -delay 3 -loop 0 "$out"/gif/f*.png -flip -crop "$band" +repage -resize 60% "$out/death.gif"
echo "death_lab: $out/death_sheet.png $out/death.gif"
