#!/usr/bin/env bash
# death_lab.sh — r98's renders : the death lab (--lab death) offscreen, every style dying at once.
#   tools/probe/death_lab.sh [--no-build]  ->  desktop/build/death_lab/death_sheet.png, death.gif, alive.png
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
jar="$root/desktop/build/libs/LaChasseGalerie-1.0.jar"
out="$root/desktop/build/death_lab"
[ "${1:-}" = --no-build ] || "$root/gradlew" -p "$root" -q dist
rm -rf "$out" && mkdir -p "$out/probe" "$out/gif"
javac -cp "$jar" -d "$out/probe" "$root/tools/probe/Death_Lab_Probe.java"
(cd "$root/desktop/assets" && "$root/tools/offscreen.sh" java --enable-native-access=ALL-UNNAMED -cp "$jar:$out/probe" Death_Lab_Probe "$out") >"$out/probe.log" 2>&1
# The world band the monsters die in, each frame flipped the right way up
crop() { magick "$1" -flip -crop 1280x380+0+110 +repage "$2"; }
for f in "$out"/sheet_*.png; do crop "$f" "${f%.png}_c.png"; done
magick "$out"/sheet_*_c.png -append -resize 70% "$out/death_sheet.png"
magick "$out/alive.png" -flip -resize 50% "$out/alive_small.png"
magick -delay 3 -loop 0 "$out"/gif/f*.png -flip -crop 1280x380+0+110 +repage -resize 60% "$out/death.gif"
echo "death_lab: $out/death_sheet.png $out/death.gif"
