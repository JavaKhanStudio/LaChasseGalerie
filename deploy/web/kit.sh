#!/usr/bin/env bash
# Puts the gate kit (r89) on VPS_1: http://141.94.115.201:8080/play/ lists a
# zip per system - the game, its own Java and a launcher to double-click - so
# a machine in the real-internet gate needs a browser and nothing else.
#
#   deploy/web/kit.sh             tools/gate-kit/build.sh, then copy
#   deploy/web/kit.sh --no-build  copies what build/gate-kit holds
#
# The zips live in /opt/lachassegalerie/web/play, beside the page's site/, so a
# deploy.sh of the page never takes them away (docker-compose.yml mounts both).
# A kit speaks its checkout's lobby VERSION, like the page: deploy/lobby with it.
set -euo pipefail
here=$(dirname "$(readlink -f "$0")")
. "$here/../vps.sh"
kit="$here/../../build/gate-kit"

[ "${1:-}" = --no-build ] || "$here/../../tools/gate-kit/build.sh"
ls "$kit"/LaChasseGalerie-*.zip >/dev/null 2>&1 || { echo "no kit in $kit"; exit 1; }

dir=/opt/lachassegalerie/web
vps "sudo mkdir -p $dir/play.new && sudo chown -R ubuntu: $dir/play.new && rm -f $dir/play.new/*"
scp "${SSH_OPTS[@]}" -q "$kit"/LaChasseGalerie-*.zip "$here/play.html" "$VPS:$dir/play.new/"
vps "cd $dir/play.new && mv play.html index.html && sha256sum *.zip > SHA256SUMS"
vps "cd $dir && rm -rf play.old && { [ -d play ] && mv play play.old || true; } && mv play.new play && rm -rf play.old"
scp "${SSH_OPTS[@]}" -q "$here/docker-compose.yml" "$here/nginx.conf" "$VPS:$dir/"
vps "cd $dir && sudo docker compose up -d --force-recreate && sleep 2"
for f in index.html $(cd "$kit" && ls LaChasseGalerie-*.zip); do
  curl -sf -o /dev/null -r 0-0 -w "http://$VPS_IP:8080/play/$f -> %{http_code}\n" "http://$VPS_IP:8080/play/$f"
done
