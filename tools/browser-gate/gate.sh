#!/usr/bin/env bash
# gate.sh — the browser gate (r81): build the html module, serve it, and play it in headless Chrome
# until a key press joins a player. Headless always: nothing opens on the screen.
#
#   tools/browser-gate/gate.sh             an optimized compile (~30 s), then the gate
#   tools/browser-gate/gate.sh --draft     a 10 s unoptimized compile
#   tools/browser-gate/gate.sh --no-build  gate whatever html/build/war already holds
#
# Needs node and a Chrome (CHROME=, default /usr/bin/google-chrome); puppeteer-core is installed
# here on first run and never downloads a browser. Screenshots and gate.json: html/build/gate/.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
here="$root/tools/browser-gate"
war="$root/html/build/war"
out="$root/html/build/gate"
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

mkdir -p "$out"
port=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')
"$(dirname "$(readlink -f "$(command -v java)")")/jwebserver" -b 127.0.0.1 -p "$port" -d "$war" >"$out/server.log" 2>&1 &
server=$!
trap 'kill $server 2>/dev/null' EXIT
for _ in $(seq 50); do curl -sf -o /dev/null "http://127.0.0.1:$port/index.html" && break; sleep 0.1; done

node "$here/gate.mjs" "http://127.0.0.1:$port/index.html?mute" "$out"
