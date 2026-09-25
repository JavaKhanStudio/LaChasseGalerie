#!/usr/bin/env bash
# Puts the browser page on VPS_1, or brings it up to date there (r83):
# http://141.94.115.201:8080/ — the tab's lobby is VPS_1's own front by default.
#
#   deploy/web/deploy.sh             an optimized :html:war, copy, compose up
#   deploy/web/deploy.sh --no-build  copies what html/build/war holds
#   deploy/web/deploy.sh down        stop and remove it
#
# The page speaks the lobby protocol of the checkout it was built from: deploy
# deploy/lobby too when that VERSION moved, or the tab is told it is outdated.
set -euo pipefail
here=$(dirname "$(readlink -f "$0")")
. "$here/../vps.sh"
war="$here/../../html/build/war"

dir=/opt/lachassegalerie/web
vps "sudo mkdir -p $dir && sudo chown ubuntu: $dir"
compose="cd $dir && sudo docker compose"

if [ "${1:-}" = down ]; then
  vps "$compose down"
  exit 0
fi

[ "${1:-}" = --no-build ] || (cd "$here/../.." && ./gradlew --console=plain -q :html:war)
[ -f "$war/html/html.nocache.js" ] || { echo "no browser build in $war"; exit 1; }
vps "rm -rf $dir/site.new"
scp "${SSH_OPTS[@]}" -q -r "$war" "$VPS:$dir/site.new"
scp "${SSH_OPTS[@]}" -q "$here/docker-compose.yml" "$here/nginx.conf" "$VPS:$dir/"
vps "cd $dir && rm -rf site.old && { [ -d site ] && mv site site.old || true; } && mv site.new site"
vps "$compose pull -q && $compose up -d --force-recreate && sleep 2 && $compose ps --format '{{.Name}} {{.State}} {{.Status}}'"
curl -sf -o /dev/null -w "http://$VPS_IP:8080/index.html -> %{http_code}, %{size_download} B\n" "http://$VPS_IP:8080/index.html"
