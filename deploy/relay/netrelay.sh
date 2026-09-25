#!/usr/bin/env bash
# The game's relay client through the relay on VPS_1, over the internet (r45):
# mints a ten-minute credential ON the box (the secret never leaves it) and
# runs ./gradlew netrelay with it in the environment.
set -euo pipefail
here=$(dirname "$(readlink -f "$0")")
. "$here/../vps.sh"
read -r RELAY_USER RELAY_PASS < <(vps "U=\"\$(( \$(date +%s) + 600 )):netrelay\"; . /opt/lachassegalerie/relay/.env; printf '%s %s\\n' \"\$U\" \"\$(printf %s \"\$U\" | openssl dgst -sha1 -hmac \"\$TURN_SECRET\" -binary | base64)\"")
export RELAY="$VPS_IP:3478" RELAY_USER RELAY_PASS
cd "$here/../.."
exec ./gradlew --console=plain -q netrelay
