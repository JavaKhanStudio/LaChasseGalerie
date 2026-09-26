#!/usr/bin/env bash
# The gate kit (r89): the game ready to put on a computer that has nothing -
# no checkout, no JDK, no terminal. One zip per system, each holding the game's
# jar, its own Java runtime (Temurin 21 JRE) and a launcher to double-click:
#
#   Windows  PLAY.bat       Linux  play.sh       macOS  PLAY.command
#
# The launcher opens the menu with --report, so Online play tells VPS_1's lobby
# log what that machine sees (its NAT verdict, every lobby row's route): the
# real-internet gate is read there by an agent, never collected as files.
# docs/online-gate.md is the runbook; deploy/web/kit.sh puts the zips on
# http://141.94.115.201:8080/play/.
#
#   tools/gate-kit/build.sh             :desktop:dist, then the zips
#   tools/gate-kit/build.sh --no-build  the zips from the jar already built
#
# Out: build/gate-kit/LaChasseGalerie-<system>.zip. The JREs are downloaded
# once into build/gate-kit/jre-cache. The jar carries every desktop native
# libGDX and LWJGL have; WebRTC's only for the machine that built it (rtc/),
# so a host on another system cannot take BROWSER tabs - desktop players, yes.
set -euo pipefail
root=$(cd "$(dirname "$(readlink -f "$0")")/../.." && pwd)
out=$root/build/gate-kit
cache=$out/jre-cache
jar=$root/desktop/build/libs/LaChasseGalerie-1.0.jar
java_major=21
mkdir -p "$cache"

[ "${1:-}" = --no-build ] || (cd "$root" && ./gradlew --console=plain -q :desktop:dist)
[ -f "$jar" ] || { echo "no game jar at $jar"; exit 1; }

# system adoptium-os adoptium-arch archive
systems=(
  "windows-x64 windows x64 zip"
  "linux-x64 linux x64 tar.gz"
  "macos-arm64 mac aarch64 tar.gz"
  "macos-x64 mac x64 tar.gz"
)

readme() {
cat <<TXT
La chasse-galerie - network test build (r89)

Nothing to install: Java is inside this folder.

1. Unzip this folder anywhere and open it.
2. Start the game: $1
3. Menu > Online play. Host a game, or type the code a friend gives you.

That is all. While Online play is open, the game sends what it sees of this
network (the connection test and whether each player is direct, relayed or
reconnecting) to the game's server, so nobody has to copy files or take
screenshots. It sends nothing else, and only from this build.

The window can be closed at any time. To remove the game, delete the folder.
TXT
}

for entry in "${systems[@]}"; do
  read -r system os arch kind <<<"$entry"
  archive=$cache/jre-$java_major-$system.$kind
  if [ ! -s "$archive" ]; then
    echo "downloading the Java $java_major runtime for $system"
    curl -fsSL -o "$archive.part" "https://api.adoptium.net/v3/binary/latest/$java_major/ga/$os/$arch/jre/hotspot/normal/eclipse"
    mv "$archive.part" "$archive"
  fi

  name=LaChasseGalerie-$system
  stage=$out/$name
  rm -rf "$stage" "$out/$name.zip"
  mkdir -p "$stage/jre"
  if [ "$kind" = zip ]; then
    unzip -q "$archive" -d "$stage/jre.tmp"
  else
    mkdir -p "$stage/jre.tmp" && tar -xzf "$archive" -C "$stage/jre.tmp"
  fi
  # The archive holds one folder (jdk-21...-jre); on macOS the runtime is in its Contents/Home
  top=$(find "$stage/jre.tmp" -mindepth 1 -maxdepth 1 -type d | head -1)
  [ -d "$top/Contents/Home" ] && top=$top/Contents/Home
  mv "$top"/* "$stage/jre/" && rm -rf "$stage/jre.tmp"
  cp "$jar" "$stage/LaChasseGalerie.jar"

  case $system in
    windows-*)
      # javaw: no console window. Run from the folder, whatever the double-click's working directory was
      printf '%s\r\n' '@echo off' 'cd /d "%~dp0"' \
        'start "" "%~dp0jre\bin\javaw.exe" --enable-native-access=ALL-UNNAMED -jar "%~dp0LaChasseGalerie.jar" --menu --report' \
        > "$stage/PLAY.bat"
      readme "double-click PLAY.bat (if Windows says it protected your PC: More info > Run anyway)." | sed 's/$/\r/' > "$stage/README.txt"
      ;;
    linux-*)
      printf '%s\n' '#!/bin/sh' 'cd "$(dirname "$0")"' \
        'exec ./jre/bin/java --enable-native-access=ALL-UNNAMED -jar LaChasseGalerie.jar --menu --report "$@"' \
        > "$stage/play.sh"
      chmod +x "$stage/play.sh"
      readme "double-click play.sh (or run ./play.sh in a terminal)." > "$stage/README.txt"
      ;;
    macos-*)
      printf '%s\n' '#!/bin/sh' 'cd "$(dirname "$0")"' \
        'xattr -dr com.apple.quarantine . 2>/dev/null' \
        'exec ./jre/bin/java -XstartOnFirstThread --enable-native-access=ALL-UNNAMED -jar LaChasseGalerie.jar --menu --report "$@"' \
        > "$stage/PLAY.command"
      chmod +x "$stage/PLAY.command"
      readme "right-click PLAY.command > Open > Open (the first time macOS asks; after that a double-click)." > "$stage/README.txt"
      ;;
  esac

  (cd "$out" && zip -qr -9 "$name.zip" "$name")
  rm -rf "$stage"
  echo "$out/$name.zip $(du -h "$out/$name.zip" | cut -f1)"
done
