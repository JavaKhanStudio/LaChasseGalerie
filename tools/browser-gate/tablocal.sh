#!/usr/bin/env bash
# tablocal.sh — r102's gate: a PHONE plays Local play alone in the tab, touch only (tablocal.mjs).
#
# Serves html/build/war on localhost and drives it in headless Chrome as a phone on its side: taps Local play,
# must be in the run at once with the touch pad on screen, walks with it until the hero drowns, taps the river
# to come back, pauses with the pad's ‖ and taps Resume. Nothing opens on the screen; no host, no lobby.
#
#   tools/browser-gate/tablocal.sh             an optimized compile (~30 s), then the gate
#   tools/browser-gate/tablocal.sh --draft     a 10 s unoptimized compile
#   tools/browser-gate/tablocal.sh --no-build  plays what html/build/war already holds
#
# Screenshots local_*.png and local.json land in html/build/tablocal/.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
here="$root/tools/browser-gate"
war="$root/html/build/war"
out="$root/html/build/tablocal"
build=(:html:war)
for arg in "$@"; do
	case "$arg" in
		--draft) build+=(-Pdraft) ;;
		--no-build) build=() ;;
		*) echo "unknown option: $arg" >&2; exit 2 ;;
	esac
done

[ ${#build[@]} -gt 0 ] && "$root/gradlew" -p "$root" "${build[@]}" -q
[ -f "$war/html/html.nocache.js" ] || { echo "no browser build in $war: run without --no-build" >&2; exit 1; }
[ -d "$here/node_modules/puppeteer-core" ] || (cd "$here" && npm ci --no-audit --no-fund --silent)

rm -rf "$out"; mkdir -p "$out"
port=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')
"$(dirname "$(readlink -f "$(command -v java)")")/jwebserver" -b 127.0.0.1 -p "$port" -d "$war" >"$out/server.log" 2>&1 &
server=$!
trap 'kill $server 2>/dev/null || true' EXIT
for _ in $(seq 50); do curl -sf -o /dev/null "http://127.0.0.1:$port/index.html" && break; sleep 0.1; done

node "$here/tablocal.mjs" "http://127.0.0.1:$port/index.html?mute&menu" "$out"
