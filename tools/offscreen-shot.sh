#!/usr/bin/env bash
# offscreen-shot.sh — run a command on a headless display, and take a picture of it.
#
# WHY: offscreen.sh and offscreen.gradle keep a board agent's window off Simon's screen, so
# nobody sees it, the agent included. Visual work ends with a picture (atelier S5): this is
# how the agent gets one without the window ever reaching the screen.
#
# Copy this into the project as tools/offscreen-shot.sh, beside offscreen.sh.
#
#   tools/offscreen-shot.sh build/shots/run.png 40 ./gradlew :desktop:run --console=plain
#   atelier attach <ref> build/shots/run.png
#
# It starts ONE headless cage, runs the command in it (ATELIER_INSIDE_CAGE=1, so a wired
# Gradle build does not start a second), waits <seconds> for the first screen to draw, grabs
# the app's window (ffmpeg x11grab on the window cage reports active; GLFW games are X11
# clients of cage's XWayland), then stops the command with SIGINT. Not the root window: under
# cage's rootless XWayland it is black, a pointer on it (r1542). The wait counts from the launch: give a Gradle run the time it takes to
# compile. Several PNGs: list several waits, `10,25,40` writes run-1.png, run-2.png, run-3.png.
#
# It never shoots the real screen: without cage it stops (exit 2). A PNG of one colour (the
# game had not drawn yet, or drew nothing) is kept but fails (exit 3): wait longer.
set -uo pipefail

if [[ $# -lt 3 ]]; then
	echo "usage: offscreen-shot.sh <out.png> <seconds[,seconds...]> <command...>" >&2
	exit 2
fi
out=$1 waits=$2
shift 2
command -v cage >/dev/null 2>&1 || { echo "offscreen-shot.sh: cage not found; it never shoots your screen. Install cage." >&2; exit 2; }
for t in ffmpeg xprop; do
	command -v $t >/dev/null 2>&1 || { echo "offscreen-shot.sh: $t not found (dnf install ffmpeg-free xprop)." >&2; exit 2; }
done
mkdir -p "$(dirname "$out")"
out=$(realpath "$out")

# Inside cage: the command in the background, the shots on cage's own DISPLAY, then the stop.
# cage ends with this client. setsid gives the command its own group, so the SIGINT reaches
# a Gradle client and the game it forked alike.
# ALSOFT_DRIVERS=null: a non-Gradle command's sound is discarded (a wired Gradle task writes
# its own WAV, build/audio/<task>.wav, over this).
inner='
out=$1 waits=$2; shift 2
setsid "$@" &
pid=$!
IFS=, read -ra at <<<"$waits"
n=${#at[@]} i=0 t=0 rc=0
for w in "${at[@]}"; do
	i=$((i + 1))
	sleep $((w - t)); t=$w
	f=$out
	[ "$n" -gt 1 ] && f=${out%.png}-$i.png
	win=$(xprop -root _NET_ACTIVE_WINDOW 2>/dev/null | grep -o "0x[0-9a-f]*" | head -1)
	if [ -z "$win" ] || [ "$((win))" -eq 0 ]; then
		echo "offscreen-shot: no window at ${w}s (still compiling? the command ended?)" >&2; rc=3
	elif ffmpeg -loglevel error -f x11grab -draw_mouse 0 -window_id "$((win))" -i "$DISPLAY" \
			-frames:v 1 -y "$f" </dev/null; then
		# Distinct colours of a small copy: 1 is a blank screen.
		colours=$(ffmpeg -loglevel error -i "$f" -vf scale=64:-1 -f rawvideo -pix_fmt rgb24 - | od -An -v -tx1 -w3 | sort -u | wc -l)
		if [ "$colours" -le 1 ]; then
			echo "offscreen-shot: $f is one colour at ${w}s: nothing drawn yet; wait longer" >&2; rc=3
		else
			echo "offscreen-shot: $f at ${w}s ($colours colours in a 64-px copy)" >&2
		fi
	else
		echo "offscreen-shot: window $win would not grab at ${w}s" >&2; rc=3
	fi
done
kill -INT -- -"$pid" 2>/dev/null
for _ in $(seq 20); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
kill -KILL -- -"$pid" 2>/dev/null
exit $rc
'
WLR_BACKENDS=headless ATELIER_INSIDE_CAGE=1 ALSOFT_DRIVERS=${ALSOFT_DRIVERS:-null} \
	cage -- bash -c "$inner" offscreen-shot "$out" "$waits" "$@" 2>&1 | grep '^offscreen' >&2
rc=${PIPESTATUS[0]}
[[ -s "$out" || -s "${out%.png}-1.png" ]] || rc=3
exit "$rc"
