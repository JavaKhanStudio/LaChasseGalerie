#!/usr/bin/env bash
# Puts the relay (coturn) on VPS_1, or brings it up to date there (r45).
#
#   deploy/relay/deploy.sh            copy, write the secret once, compose up
#   deploy/relay/deploy.sh down       stop and remove it (the secret stays)
#
# VPS_1 is Fountain of Dreams' OVH box; nothing of FoD's is touched: the relay
# lives in /opt/lachassegalerie/relay and is one container. The .env there
# holds TURN_SECRET, made here on the first deploy and never rewritten, so
# credentials the lobby service mints keep working across deploys.
set -euo pipefail
here=$(dirname "$(readlink -f "$0")")
. "$here/../vps.sh"

dir=/opt/lachassegalerie/relay
vps "sudo mkdir -p $dir && sudo chown ubuntu: $dir"

if [ "${1:-}" = down ]; then
  vps "cd $dir && sudo docker compose down"
  exit 0
fi

scp "${SSH_OPTS[@]}" -q "$here/turnserver.conf" "$here/docker-compose.yml" "$VPS:$dir/"
# The secret is made ON the box and never leaves it
vps "cd $dir && if [ ! -f .env ]; then umask 077; printf 'TURN_SECRET=%s\nRELAY_PUBLIC_IP=%s\n' \"\$(head -c 32 /dev/urandom | base64 | tr -d '/+=')\" $VPS_IP > .env; echo 'secret: made'; else echo 'secret: kept'; fi"
vps "cd $dir && sudo docker compose pull -q && sudo docker compose up -d && sleep 2 && sudo docker compose ps --format '{{.Name}} {{.State}} {{.Status}}' && sudo ss -ulpn | grep -E ':3478 ' | head -4 && sudo docker compose logs --tail 40 relay | grep -iE 'error|cannot|listener|relay' | head -20"
