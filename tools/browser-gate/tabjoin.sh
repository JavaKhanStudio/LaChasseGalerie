#!/usr/bin/env bash
# tabjoin.sh — r82's gate: a browser tab joins a desktop host's game as a player, all on this machine.
#
#   a lobby service      lobby/build/install (the deployable, no libGDX), UDP and WebSocket ports picked free
#   a desktop host       tools/probe/Lobby_Screen_Probe host -Dprobe.browser under cage (tools/offscreen.sh):
#                        hosts from Online play, writes its code, starts once the tab's row says its channel
#                        is open, logs every hero each second
#   a browser tab        headless Chrome (puppeteer-core, tabjoin.mjs) on html/build/war?lobby=<the front>:
#                        Online play, types the code, joins over WebRTC, presses a key and walks right
#
#   tools/browser-gate/tabjoin.sh             builds everything (an optimized GWT compile, ~30 s)
#   tools/browser-gate/tabjoin.sh --draft     a 10 s unoptimized GWT compile
#   tools/browser-gate/tabjoin.sh --no-build  plays what is already built
#   START_AFTER_MS=15000 tabjoin.sh ...        the host starts that long after the tab's row opened
#   REFUSED=1 tabjoin.sh ...                   a host with no WebRTC (-Dprobe.notabs, r85) : the tab must be
#                                              refused at once and stay on its lobby screen (tab_2_refused.png)
#   ONLINE=1 tabjoin.sh --no-build             r83 : the tab loads the page VPS_1 serves (deploy/web, PAGE= to
#                                              override) and both ends go through VPS_1's lobby ; the host's WebRTC
#                                              uses STUN, as the game's does. lobby.log is VPS_1's, from the start.
#   ONLINE=1 RELAY=1 tabjoin.sh --no-build     ... and the tab's call is relay-only (?relay) : coturn on VPS_1 or nothing
#   TOUCH=1 tabjoin.sh ...                     r87 : the tab is a PHONE on its side, touch only (tabtouch.mjs) : taps Online
#                                              play, types the code on the soft keyboard, taps the game under Open games,
#                                              walks with the touch pad. phone_*.png. GO=1 : joins with the keyboard's Go
#
# Fails unless the tab plays IN, draws snapshots with its own hero in them, its hero walks right in the
# tab AND on the host (host.log), the host's row for it said its route, and it joined ONCE (lobby.log, r86). Everything lands in
# html/build/tabjoin/: host_5_browser_open.png, host_4_run.png, tab_*.png, host.log, lobby.log, tab.json.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
here="$root/tools/browser-gate"
war="$root/html/build/war"
out="$root/html/build/tabjoin"
build=(:html:war)
compile=1
for arg in "$@"; do
	case "$arg" in
		--draft) build+=(-Pdraft) ;;
		--no-build) compile=0 ;;
		*) echo "unknown option: $arg" >&2; exit 2 ;;
	esac
done

online="${ONLINE:-}"
[ -n "$online" ] && build=(:desktop:dist)
if [ $compile = 1 ]; then
	"$root/gradlew" -p "$root" "${build[@]}" :desktop:dist :lobby:installDist -q
fi
[ -n "$online" ] || [ -f "$war/html/html.nocache.js" ] || { echo "no browser build in $war: run without --no-build" >&2; exit 1; }
[ -d "$here/node_modules/puppeteer-core" ] || (cd "$here" && npm ci --no-audit --no-fund --silent)

rm -rf "$out"; mkdir -p "$out/probe"
jar="$root/desktop/build/libs/LaChasseGalerie-1.0.jar"
javac -cp "$jar" -d "$out/probe" "$root/tools/probe/Lobby_Screen_Probe.java"

free() { python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])'; }
pids=()
trap 'kill "${pids[@]}" 2>/dev/null; wait 2>/dev/null' EXIT

if [ -n "$online" ]; then
	. "$root/deploy/vps.sh"
	since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
	service="$VPS_IP:7770"
	page="${PAGE:-http://$VPS_IP:8080/index.html}?mute&menu${RELAY:+&relay}"
	curl -sf -o /dev/null "${page%%\?*}" || { echo "tabjoin: FAILED — nothing serves $page (deploy/web/deploy.sh)" >&2; exit 1; }
else
	udp=$(free); ws=$(free); web=$(free)
	"$root/lobby/build/install/lobby/bin/lobby" "$udp" "$ws" >"$out/lobby.log" 2>&1 & pids+=($!)
	"$(dirname "$(readlink -f "$(command -v java)")")/jwebserver" -b 127.0.0.1 -p "$web" -d "$war" >"$out/server.log" 2>&1 & pids+=($!)
	for _ in $(seq 50); do curl -sf -o /dev/null "http://127.0.0.1:$web/index.html" && grep -q "WS $ws" "$out/lobby.log" && break; sleep 0.1; done
	service="127.0.0.1:$udp"
	page="http://127.0.0.1:$web/index.html?mute&menu&lobby=127.0.0.1:$ws"
fi

(cd "$root/desktop/assets" && exec "$root/tools/offscreen.sh" java --enable-native-access=ALL-UNNAMED -Dprobe.browser=true -Dprobe.startAfter="${START_AFTER_MS:-1000}" -Dprobe.notabs="${REFUSED:+true}" \
	-Dprobe.stun="${online:+true}" -cp "$jar:$out/probe" Lobby_Screen_Probe host "$out" "$service") >"$out/host.log" 2>&1 & host=$!
pids+=($host)

status=0
echo "tabjoin: the tab opens $page"
tab=tabjoin.mjs; [ -n "${TOUCH:-}" ] && tab=tabtouch.mjs
REFUSED="${REFUSED:-}" GO="${GO:-}" node "$here/$tab" "$page" "$out" || status=$?
# The host exits by itself once the tab wrote tab_done
for _ in $(seq 150); do kill -0 $host 2>/dev/null || break; sleep 0.1; done
wait $host 2>/dev/null || status=1

[ -n "$online" ] && vps "sudo docker logs --since $since lachassegalerie-lobby 2>&1" >"$out/lobby.log"
grep -E "browser's row|row for the tab|second [0-9]+ :|no tabs :|FAIL" "$out/host.log" | sed 's/^/host: /'
if [ -n "${REFUSED:-}" ]; then
	grep -q "no tabs : true, browser rows 0, offers held 0" "$out/host.log" || { echo "tabjoin: FAILED — the host took the tab, or its offer"; status=1; }
	[ $status = 0 ] && echo "tabjoin: ok, refused — $out" || echo "tabjoin: FAILED — see $out"
	exit $status
fi
row=$(grep -o "row for the tab says : .*" "$out/host.log" || true)
[ -n "$row" ] || { echo "tabjoin: FAILED — the host never showed the tab's row open"; status=1; }
# One call however long the host waits before Start (r86) : a second JOINING is a tab that timed its host out and offered again
joins=$(grep -c "JOINING .* from ws/" "$out/lobby.log" || true)
echo "tabjoin: the tab joined $joins time(s)"
[ "$joins" = 1 ] || { echo "tabjoin: FAILED — the tab joined $joins times, not once : it lost its host while waiting for Start"; status=1; }
tab_player=$(python3 -c "import json; print(json.load(open('$out/tab.json')).get('player', 0))" 2>/dev/null || echo 0)
# The tab's hero on the HOST: its x in the first and last second it is logged
python3 - "$out/host.log" "$tab_player" <<'PY' || status=1
import re, sys
log, player = sys.argv[1], sys.argv[2]
xs = [float(m.group(1)) for line in open(log) for m in [re.search(r"Player " + player + r" at \(([-0-9.]+),", line)] if m and " second " in line]
if player == "0" or len(xs) < 2:
    print(f"tabjoin: FAILED — the host never logged player {player}'s hero twice: {xs}")
    sys.exit(1)
print(f"tabjoin: on the host, player {player}'s hero went from x={xs[0]:.2f} to max x={max(xs):.2f} over {len(xs)} seconds")
if max(xs) - xs[0] < 0.5:
    print("tabjoin: FAILED — the tab's hero did not move on the host")
    sys.exit(1)
PY
[ $status = 0 ] && echo "tabjoin: ok — $out" || echo "tabjoin: FAILED — see $out"
exit $status
