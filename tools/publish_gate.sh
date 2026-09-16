#!/usr/bin/env bash
# This board's publish gate: `[coordinator] gate` in the atelier checkout's
# projects/lachassegalerie.toml.
#
# The worker runs it on a detached worktree of the commit it is about to push,
# with ATELIER_CHECKOUT naming the real checkout. Exit 0 and that commit goes
# out; anything else and a Publish pass is queued with this script's last lines.
#
# It is the recipe every Publish pass ran by hand (r65): build, smoke, nettest
# (which runs netsession, netprocs and netlobby after it), dist. There is no CI;
# the #build pack's gates are all there is. Then no tracked file may have
# changed: a check that writes the tree is not a check.
#
# Nothing is borrowed from ATELIER_CHECKOUT: the dependencies are in ~/.gradle,
# which every tree shares, and .gradle/ and build/ belong to the tree they are in.
#
# Smoke's seed-1 numbers are printed, not held: a gameplay change moves them on
# purpose, and a change that claims not to must be read against the upstream tip.
#
#   tools/publish_gate.sh      # by hand, from a clean worktree of the commit
set -uo pipefail
cd "$(dirname "$(readlink -f "$0")")/.."

STEPS=(build smoke nettest dist)

echo "gate: $(git rev-parse --short HEAD), ${#STEPS[@]} steps"
LOG=$(mktemp)
trap 'rm -f "$LOG"' EXIT
for step in "${STEPS[@]}"; do
  start=$SECONDS
  if ./gradlew --console=plain "$step" > "$LOG" 2>&1; then
    echo "  ok   $step  ($((SECONDS - start))s)"
    [ "$step" = smoke ] && grep '^SMOKE done' "$LOG" | tail -1 | sed 's/^SMOKE done in [0-9]* ms: */       /'
  else
    # The worker hands on only the last lines, and nettest's finalizers keep
    # talking after it fails: so the tail first, then what failed, then the verdict.
    tail -15 "$LOG" | cut -c1-300
    echo "gate: what failed in $step:"
    grep -E 'FAIL|error:|Exception|Error:' "$LOG" | grep -vE '^(BUILD FAILED|FAILURE:)' \
      | head -20 | cut -c1-300 | sed 's/^/  | /'
    echo "  FAIL $step  ($((SECONDS - start))s)"
    exit 1
  fi
done

changed=$(git status --porcelain --untracked-files=no)
if [ -n "$changed" ]; then
  echo "gate: the checks changed tracked files:"
  echo "$changed"
  exit 1
fi
echo "gate: passed in ${SECONDS}s"
