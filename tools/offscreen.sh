#!/usr/bin/env bash
# offscreen.sh — run a command so its window never appears or steals focus.
#
# WHY: an agent checking its work opens the game on the desktop, in front of whatever Simon
# was doing, with the music in his speakers. Running it inside `cage` (a headless wlroots
# compositor) gives it its own invisible display — GPU rendering is preserved, you just never
# see the window.
#
# The sound goes nowhere too (ALSOFT_DRIVERS=null).
#
# Falls back to running normally if cage is missing, or if you set CHASSE_NO_OFFSCREEN=1
# because you actually want to watch.
#
# `./gradlew :desktop:run` does this by itself when a board agent runs it
# (gradle/offscreen.gradle). This wrapper is for everything else that opens a window - the
# dist jar, a scratch program.
#
# Usage:
#   tools/offscreen.sh java -jar desktop/build/libs/LaChasseGalerie-1.0.jar
#
# Same script as ../onboard/tools/offscreen.sh.
set -uo pipefail

if [[ $# -eq 0 ]]; then
	echo "usage: tools/offscreen.sh <command...>" >&2
	exit 2
fi

if [[ "${CHASSE_NO_OFFSCREEN:-0}" != "1" ]] && command -v cage >/dev/null 2>&1; then
	# WLR_BACKENDS=headless: no DRM lease, no physical output, nothing on screen.
	# cage brings up its own XWayland, which is what LWJGL's GLFW connects to.
	# CHASSE_INSIDE_CAGE tells gradle/offscreen.gradle not to start a second one inside.
	# ALSOFT_DRIVERS=null: OpenAL Soft, which LWJGL ships, plays into a device that discards
	# it; a Gradle task inside still writes its own WAV.
	exec env WLR_BACKENDS=headless CHASSE_INSIDE_CAGE=1 ALSOFT_DRIVERS=null cage -- "$@"
fi

if [[ "${CHASSE_NO_OFFSCREEN:-0}" != "1" ]]; then
	echo "offscreen.sh: cage not found, running on your display instead" >&2
fi
exec "$@"
