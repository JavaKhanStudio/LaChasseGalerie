# Sourced by deploy/*/: where VPS_1 is and how to reach it. Override any of
# these in the environment; the defaults are Simon's machine.
#   VPS_1 = Fountain of Dreams' OVH box (atelier-phone d2).
VPS_IP=${VPS_IP:-141.94.115.201}
VPS=${VPS:-ubuntu@$VPS_IP}
VPS_KEY=${VPS_KEY:-$HOME/Documents/GitHub/OnceUponATime/HQ/staging_deploy_key}
VPS_KNOWN=${VPS_KNOWN:-$HOME/Documents/GitHub/OnceUponATime/HQ/vps_known_hosts.txt}
SSH_OPTS=(-i "$VPS_KEY" -o IdentitiesOnly=yes -o UserKnownHostsFile="$VPS_KNOWN" -o BatchMode=yes)
vps() { ssh "${SSH_OPTS[@]}" "$VPS" "$@"; }
