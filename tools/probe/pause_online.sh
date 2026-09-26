#!/usr/bin/env bash
# pause_online.sh — r106's gate: Escape on an online client, in two offscreen windows (Pause_Online_Probe).
# A host JVM and a --join client JVM over UDP on loopback ; fails on any FAIL or if the client never finished.
#
#   tools/probe/pause_online.sh             builds the fat jar first (./gradlew dist)
#   tools/probe/pause_online.sh --no-build  plays what desktop/build/libs holds
#
# Everything lands in desktop/build/pause_online/: host.log, join.log, client_*.png, host_score.png.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
jar="$root/desktop/build/libs/LaChasseGalerie-1.0.jar"
out="$root/desktop/build/pause_online"
[ "${1:-}" = --no-build ] || "$root/gradlew" -p "$root" -q dist
rm -rf "$out" && mkdir -p "$out/probe"
javac -cp "$jar" -d "$out/probe" "$root/tools/probe/Pause_Online_Probe.java"

run() { (cd "$root/desktop/assets" && exec "$root/tools/offscreen.sh" java --enable-native-access=ALL-UNNAMED -cp "$jar:$out/probe" Pause_Online_Probe "$1" "$out") >"$out/$1.log" 2>&1 ; }
run host & host=$!
trap 'kill $host 2>/dev/null || true' EXIT
run join || true
touch "$out/done"
wait $host || true

grep -h 'probe' "$out/join.log" "$out/host.log" | sed 's/^/  /' || true
status=0
grep -q 'FAIL' "$out/join.log" && status=1
grep -q 'client : done' "$out/join.log" || { echo "pause_online: FAILED — the client never finished (join.log)"; status=1; }
[ $status = 0 ] && echo "pause_online: ok — $out" || echo "pause_online: FAILED — $out"
exit $status
