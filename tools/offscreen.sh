#!/usr/bin/env bash
# offscreen.sh — run a command so its windows never appear on Simon's screen or steal focus,
# and its sound never reaches his speakers.
#
# WHY: a board agent checking its work opens the app on the desktop, in front of whatever
# Simon was doing, and the music plays with it. Inside `cage` (a headless wlroots compositor)
# the app gets an invisible display of its own. GPU rendering is kept; nobody sees the window.
#
# Copy this into the project as tools/offscreen.sh. The project's own build should go
# offscreen BY ITSELF when ATELIER_AGENT is set (offscreen.gradle beside this file, or the
# equivalent for its build); this wrapper is for what the build does not launch: a packaged
# build, a fat jar, a scratch program, a Godot binary.
#
#   tools/offscreen.sh java -jar desktop/build/libs/Game.jar
#   tools/offscreen.sh godot --path . res://scenes/lab.tscn
#
# Runs it normally if ATELIER_NO_OFFSCREEN=1 because you actually want to watch, or if cage is
# missing and no board agent (ATELIER_AGENT) is running it; an agent without cage stops (exit 2).
set -uo pipefail

if [[ $# -eq 0 ]]; then
	echo "usage: offscreen.sh <command...>" >&2
	exit 2
fi

if [[ "${ATELIER_NO_OFFSCREEN:-0}" != "1" ]] && command -v cage >/dev/null 2>&1; then
	# WLR_BACKENDS=headless: no DRM lease, no physical output, nothing on screen.
	# cage brings up its own XWayland, which is what LWJGL's GLFW connects to.
	# ATELIER_INSIDE_CAGE tells offscreen.gradle not to start a second cage inside this one.
	# ALSOFT_DRIVERS=null: OpenAL Soft (every LWJGL/libGDX game) plays into a device that
	# discards it. To KEEP the sound, use capture-audio.sh inside this instead.
	exec env WLR_BACKENDS=headless ATELIER_INSIDE_CAGE=1 ALSOFT_DRIVERS=null cage -- "$@"
fi

if [[ "${ATELIER_NO_OFFSCREEN:-0}" != "1" ]]; then
	# A board agent stops here: falling back to the screen is what this script is for stopping.
	if [[ -n "${ATELIER_AGENT:-}" ]]; then
		echo "offscreen.sh: cage not found: $1 would open on the screen. Install cage, or set ATELIER_NO_OFFSCREEN=1." >&2
		exit 2
	fi
	echo "offscreen.sh: cage not found, running on your display instead" >&2
fi
exec "$@"
