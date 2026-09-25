#!/usr/bin/env bash
# Puts the lobby service on VPS_1, or brings it up to date there (r45).
#
#   deploy/lobby/deploy.sh          build, copy, compose up
#   deploy/lobby/deploy.sh down     stop and remove it
#
# Needs the relay deployed first (deploy/relay/deploy.sh): the lobby mints the
# relay's credentials from the secret in /opt/lachassegalerie/relay/.env.
set -euo pipefail
here=$(dirname "$(readlink -f "$0")")
. "$here/../vps.sh"

dir=/opt/lachassegalerie/lobby
vps "sudo mkdir -p $dir && sudo chown ubuntu: $dir"
compose="cd $dir && sudo docker compose --env-file ../relay/.env"

if [ "${1:-}" = down ]; then
  vps "$compose down"
  exit 0
fi

(cd "$here/../.." && ./gradlew --console=plain -q :lobby:installDist)
vps "test -f /opt/lachassegalerie/relay/.env" || { echo "no relay secret on the box: run deploy/relay/deploy.sh first"; exit 1; }
vps "rm -rf $dir/app.new"
scp "${SSH_OPTS[@]}" -q -r "$here/../../lobby/build/install/lobby" "$VPS:$dir/app.new"
scp "${SSH_OPTS[@]}" -q "$here/docker-compose.yml" "$VPS:$dir/"
vps "cd $dir && rm -rf app.old && { [ -d app ] && mv app app.old || true; } && mv app.new app"
vps "$compose pull -q && $compose up -d --force-recreate && sleep 3 && $compose ps --format '{{.Name}} {{.State}} {{.Status}}' && sudo ss -ulpn | grep ':7770 ' && $compose logs --tail 5 lobby"
